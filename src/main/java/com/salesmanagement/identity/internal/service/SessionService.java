package com.salesmanagement.identity.internal.service;

import com.salesmanagement.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces the single-active-session-per-user rule (FR-5).
 *
 * <p><b>The rule:</b> at any point in time, a user may have at most one valid
 * session — meaning one access token and one refresh token. When the same user
 * logs in from a second device, both tokens from the previous session are
 * blacklisted immediately.
 *
 * <p><b>Why both tokens must be tracked:</b> blacklisting only the access token
 * leaves the old refresh token alive. When the old device's access token expires
 * after 15 minutes, its HTTP interceptor silently calls {@code /api/auth/refresh}
 * with the still-valid refresh token and gets a new session — bypassing
 * single-session enforcement entirely. Blacklisting both tokens on re-login
 * closes this gap.
 *
 * <p><b>Storage:</b> in-memory {@link ConcurrentHashMap}. Does not survive
 * restart. Production upgrade: Redis {@code HSET} per user.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final TokenBlacklistStore blacklistStore;
    private final JwtService          jwtService;

    /**
     * Key: userId.
     * Value: both tokens from the current active session.
     */
    private final Map<Long, TokenPair> activeSessions = new ConcurrentHashMap<>();

    // ─── Session lifecycle ────────────────────────────────────────────────────

    /**
     * Registers a new session, revoking any existing session for this user.
     *
     * <p>Both the previous access token and the previous refresh token are
     * blacklisted. The new pair becomes the only valid session.
     *
     * @param userId       the user starting a new session
     * @param accessToken  the freshly issued access token
     * @param refreshToken the freshly issued refresh token
     */
    public void registerSession(Long userId, String accessToken, String refreshToken) {
        TokenPair previous = activeSessions.put(userId, new TokenPair(accessToken, refreshToken));

        if (previous != null) {
            blacklistToken(previous.accessToken);
            blacklistToken(previous.refreshToken);
            log.info("Previous session revoked for userId={} — FR-5 enforced", userId);
        }

        log.debug("Session registered for userId={}", userId);
    }

    /**
     * Invalidates the current session on logout.
     *
     * <p>Both tokens are blacklisted. Calling twice is safe — a missing
     * session is a no-op.
     *
     * @param userId the user logging out
     */
    public void invalidateSession(Long userId) {
        TokenPair removed = activeSessions.remove(userId);

        if (removed == null) {
            log.debug("Logout for userId={} with no active session — no-op", userId);
            return;
        }

        blacklistToken(removed.accessToken);
        blacklistToken(removed.refreshToken);
        log.info("Session invalidated for userId={}", userId);
    }

    // ─── Queries ──────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the given token is part of the currently
     * active session for the specified user.
     *
     * @param userId the user making the request
     * @param token  the access token to verify
     * @return {@code true} if this token belongs to the current active session
     * @throws BusinessException {@code 403} if the user has no active session
     */
    public boolean isCurrentSession(Long userId, String token) {
        TokenPair active = activeSessions.get(userId);

        if (active == null) {
            throw BusinessException.forbidden(
                    "No active session found for user: " + userId,
                    "NO_ACTIVE_SESSION");
        }

        return active.accessToken.equals(token);
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Extracts the {@code jti} from a token and writes it to the blacklist.
     * Handles the case where a token might already be expired (e.g. the old
     * access token expired naturally before the user re-logged in).
     */
    private void blacklistToken(String token) {
        try {
            String jti    = jwtService.extractJti(token);
            java.util.Date expiry = jwtService.extractExpiry(token);
            blacklistStore.blacklist(jti, expiry);
        } catch (Exception e) {
            // Token already expired — no need to blacklist, it's dead anyway
            log.debug("Skipped blacklisting already-expired token: {}", e.getMessage());
        }
    }

    /**
     * Holds both tokens for a single active session.
     */
    private record TokenPair(String accessToken, String refreshToken) {}
}