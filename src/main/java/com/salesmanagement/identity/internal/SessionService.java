package com.salesmanagement.identity.internal;

import com.salesmanagement.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces the single-active-session-per-user rule (FR-5).
 *
 * <p><b>The rule:</b> at any point in time, a user may have at most one valid
 * access token. When the same user logs in from a second device, the first
 * session is revoked immediately — its {@code jti} is blacklisted and the new
 * token becomes the only valid session.
 *
 * <p><b>What is stored in the active-session registry:</b> the raw token string,
 * not the {@code jti}. The raw token is kept so that when a session is replaced,
 * its {@code jti} can be extracted via {@link JwtService#extractJti} and written
 * to {@link TokenBlacklistStore}. Storing only the {@code jti} would be insufficient
 * because {@link TokenBlacklistStore} also needs the expiry, which requires parsing
 * the token anyway.
 *
 * <p><b>What the blacklist stores:</b> the {@code jti} UUID — not the raw token.
 * This matches what {@code JwtAuthFilter} passes to
 * {@link com.salesmanagement.shared.security.TokenBlacklistChecker#isBlacklisted},
 * which reads {@code claims.getId()} from the incoming request's token.
 *
 * <p><b>Storage limitation:</b> the active-session registry is an in-memory
 * {@link ConcurrentHashMap}. Sessions do not survive a server restart. For
 * production, replace with a Redis {@code HSET} or a dedicated
 * {@code active_sessions} database table — the change is confined to this class.
 *
 * <p>This class is public so {@link AuthController} can inject it across
 * sub-packages within the identity module. Spring Modulith's {@code verify()}
 * still prevents any class outside the identity module from importing it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final TokenBlacklistStore blacklistStore;
    private final JwtService          jwtService;

    /**
     * Key: userId. Value: raw JWT string of the currently valid access token.
     * A missing entry means the user has no active session.
     */
    private final Map<Long, String> activeSessions = new ConcurrentHashMap<>();

    // ─── Session lifecycle ────────────────────────────────────────────────────

    /**
     * Registers a new session for the given user, revoking any existing session.
     *
     * <p>Called by {@link AuthController} after a successful login or token refresh.
     * If a previous session exists, its {@code jti} is extracted and written to
     * {@link TokenBlacklistStore} before the new token is registered — there is
     * no window in which both tokens are simultaneously valid.
     *
     * @param userId   the database primary key of the user starting a session
     * @param newToken the freshly issued access token to register as active
     */
    public void registerSession(Long userId, String newToken) {
        String previousToken = activeSessions.put(userId, newToken);

        if (previousToken != null) {
            String jti    = jwtService.extractJti(previousToken);
            Date   expiry = jwtService.extractExpiry(previousToken);
            blacklistStore.blacklist(jti, expiry);
            log.info("Previous session revoked for userId={} — FR-5 enforced", userId);
        }

        log.debug("Session registered for userId={}", userId);
    }

    /**
     * Invalidates the current session for the given user.
     *
     * <p>Called by {@link AuthController} on {@code POST /api/auth/logout}.
     * The token's {@code jti} is extracted and blacklisted immediately so the
     * token cannot be reused even within its remaining lifetime.
     *
     * <p>Calling this method twice for the same user is safe — a missing session
     * entry is treated as a no-op, handling network retries gracefully.
     *
     * @param userId the database primary key of the user logging out
     * @param token  the access token to invalidate
     */
    public void invalidateSession(Long userId, String token) {
        String removedToken = activeSessions.remove(userId);

        if (removedToken == null) {
            log.debug("Logout for userId={} with no active session — no-op", userId);
            return;
        }

        String jti    = jwtService.extractJti(token);
        Date   expiry = jwtService.extractExpiry(token);
        blacklistStore.blacklist(jti, expiry);
        log.info("Session invalidated for userId={}", userId);
    }

    // ─── Queries ──────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the given token is the currently registered
     * active session for the specified user.
     *
     * <p>A defence-in-depth check on top of JWT signature, expiry, and blacklist
     * validation. A token can pass all three and still be rejected here if the
     * user has since logged in from another device, making this the older,
     * now-replaced session.
     *
     * @param userId the database primary key of the user making the request
     * @param token  the access token to verify
     * @return {@code true} if this token is the current active session
     * @throws BusinessException {@code 403} if the user has no active session at all
     */
    public boolean isCurrentSession(Long userId, String token) {
        String activeToken = activeSessions.get(userId);

        if (activeToken == null) {
            throw BusinessException.forbidden(
                    "No active session found for user: " + userId,
                    "NO_ACTIVE_SESSION");
        }

        return activeToken.equals(token);
    }
}