package com.salesmanagement.identity.internal.controller;

import com.salesmanagement.identity.api.UserLoggedOutEvent;
import com.salesmanagement.identity.internal.service.TokenBlacklistStore;
import com.salesmanagement.identity.internal.entity.User;
import com.salesmanagement.identity.internal.dto.ChangePasswordRequest;
import com.salesmanagement.identity.internal.dto.LoginRequest;
import com.salesmanagement.identity.internal.dto.LoginResponse;
import com.salesmanagement.identity.internal.service.JwtService;
import com.salesmanagement.identity.internal.service.LoginAttemptService;
import com.salesmanagement.identity.internal.service.SessionService;
import com.salesmanagement.identity.internal.service.UserService;
import com.salesmanagement.shared.api.ApiResponse;
import com.salesmanagement.shared.exception.BusinessException;
import com.salesmanagement.shared.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * REST controller handling authentication lifecycle: login, token refresh, and logout.
 *
 * <p>This controller is the only entry point for unauthenticated requests in the
 * identity module. All three endpoints are explicitly permitted in
 * {@code SecurityConfig} — no JWT is required to reach them. Every other endpoint
 * in the system requires a valid Bearer token.
 *
 * <p><b>Responsibility boundary:</b> this controller orchestrates the authentication
 * flow by delegating to the appropriate services. It does not contain business logic.
 * The sequence for login is:
 * <ol>
 *   <li>{@link AuthenticationManager} delegates to {@link UserService#loadUserByUsername}
 *       to verify credentials via Spring Security's standard pipeline.</li>
 *   <li>{@link UserService#getByPhoneNumber} loads the full {@link User} entity needed
 *       for token generation.</li>
 *   <li>{@link JwtService} issues the access and refresh tokens.</li>
 *   <li>{@link SessionService} revokes any previous session and registers the new one,
 *       enforcing the single-session rule (FR-5).</li>
 * </ol>
 *
 * <p><b>Error handling:</b> Spring Security's {@link BadCredentialsException} and
 * {@link DisabledException} are caught here and converted to {@link BusinessException}
 * so the {@code GlobalExceptionHandler} in shared produces a consistent
 * {@code ApiResponse} error envelope — not Spring Security's default HTML error page.
 *
 * <p>This class is public so Spring's component scan can proxy it across
 * sub-packages within the identity module. Spring Modulith's {@code verify()}
 * still prevents any class outside the identity module from importing it.
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserService           userService;
    private final JwtService            jwtService;
    private final SessionService        sessionService;
    private final LoginAttemptService loginAttemptService;
    private final TokenBlacklistStore blacklistStore;
    /** Publishes UserLoggedOutEvent so the notification module can drop device tokens (D2). */
    private final ApplicationEventPublisher events;

    // ─── Endpoints ────────────────────────────────────────────────────────────

    /**
     * Authenticates a user and issues a new session with access and refresh tokens.
     *
     * <p>On success, any previously active session for this user is revoked
     * immediately (FR-5). The response carries both tokens and the user's
     * identity fields so the client can bootstrap its UI without a second request.
     *
     * <p>Both wrong-password and inactive-account failures surface as the same
     * generic message — "Invalid email or password" — to prevent user enumeration.
     * {@link UserService#loadUserByUsername} enforces this by throwing
     * {@link org.springframework.security.core.userdetails.UsernameNotFoundException}
     * for all negative cases regardless of their specific cause.
     *
     * @param request validated login credentials
     * @return {@code 200 OK} with {@link LoginResponse} on success
     * @throws BusinessException {@code 401} if credentials are invalid or account is inactive
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody LoginRequest request) {

        // ── Lockout check — before any authentication work ──────────────
        if (loginAttemptService.isLocked(request.phoneNumber())) {
            long minutes = loginAttemptService.getRemainingLockoutMinutes(request.phoneNumber());
            throw BusinessException.badRequest(
                    "Account temporarily locked due to too many failed attempts. Try again in "
                            + minutes + " minutes.",
                    "ACCOUNT_LOCKED");
        }

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.phoneNumber().toLowerCase(),
                            request.password()));
        } catch (BadCredentialsException | DisabledException e) {
            // ── Record the failure ──────────────────────────────────────
            loginAttemptService.recordFailure(request.phoneNumber());
            throw BusinessException.badRequest(
                    "Invalid email or password",
                    "INVALID_CREDENTIALS");
        }

        // ── Login succeeded — clear any previous failures ───────────────
        loginAttemptService.recordSuccess(request.phoneNumber());

        User user            = userService.getByPhoneNumber(authentication.getName());
        String accessToken   = jwtService.generateAccessToken(user);
        String refreshToken  = jwtService.generateRefreshToken(user);

        sessionService.registerSession(user.getId(), accessToken, refreshToken);

        log.info("Login successful: userId={}, role={}", user.getId(), user.getRole());

        return ResponseEntity.ok(ApiResponse.ok(buildLoginResponse(user, accessToken, refreshToken)));
    }

    /**
     * Issues a new access token and refresh token using a valid refresh token.
     *
     * <p>The user is reloaded from the database — not reconstructed from the token —
     * so any status or role changes made by an ADMIN during the session take effect
     * immediately at refresh time. A suspended or deactivated user cannot obtain a
     * new access token even if their refresh token has not expired yet.
     *
     * <p>The old session is revoked and a new one registered, keeping the
     * single-session invariant intact across refreshes (FR-5).
     *
     * @param bearerToken the {@code Authorization} header containing the refresh token
     * @return {@code 200 OK} with a fresh {@link LoginResponse} on success
     * @throws BusinessException {@code 400} if the refresh token is invalid or expired
     * @throws BusinessException {@code 400} if the account is no longer active
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(
            @RequestHeader("Authorization") String bearerToken) {

        String refreshToken = extractBearerToken(bearerToken);

        if (!jwtService.isRefreshToken(refreshToken)) {
            throw BusinessException.badRequest(
                    "Provided token is not a valid refresh token",
                    "NOT_A_REFRESH_TOKEN");
        }

        // ── Blacklist check — the filter is skipped for this endpoint ────
        String jti = jwtService.extractJti(refreshToken);
        if (blacklistStore.isBlacklisted(jti)) {
            throw BusinessException.badRequest(
                    "Refresh token has been revoked",
                    "REVOKED_REFRESH_TOKEN");
        }

        User user = userService.getByPhoneNumber(jwtService.extractSubject(refreshToken));

        if (!user.canLogin()) {
            throw BusinessException.forbidden(
                    "Account is no longer active",
                    "ACCOUNT_INACTIVE");
        }

        String newAccessToken  = jwtService.generateAccessToken(user);
        String newRefreshToken = jwtService.generateRefreshToken(user);

        sessionService.registerSession(user.getId(), newAccessToken, newRefreshToken);

        log.info("Token refreshed: userId={}", user.getId());

        return ResponseEntity.ok(ApiResponse.ok(buildLoginResponse(user, newAccessToken, newRefreshToken)));
    }

    /**
     * Terminates the current session by blacklisting the submitted token.
     *
     * <p>After this call, the token is immediately invalid — even if its
     * cryptographic expiry has not elapsed. The client must discard both
     * the access and refresh tokens and redirect to the login screen.
     *
     * <p>Calling this endpoint twice with the same token (e.g. after a
     * network retry) is safe — {@link SessionService#invalidateSession}
     * treats a missing session as a no-op.
     *
     * <p>On logout a {@link UserLoggedOutEvent} is published so the notification
     * module can delete this user's FCM device tokens (D2), stopping pushes to a
     * device the user deliberately signed out of. The event is published after the
     * session is invalidated; its consumer runs post-commit in its own transaction,
     * so token cleanup can never fail or delay the logout response.
     *
     * @param bearerToken the {@code Authorization} header containing the access token to invalidate
     * @return {@code 200 OK} with no body on success
     * @throws BusinessException {@code 400} if the Authorization header is missing or malformed
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @RequestHeader("Authorization") String bearerToken) {

        String token  = extractBearerToken(bearerToken);
        long   userId = jwtService.extractUserId(token);

        sessionService.invalidateSession(userId);

        events.publishEvent(new UserLoggedOutEvent(userId, Instant.now()));

        log.info("Logout successful: userId={}", userId);

        return ResponseEntity.ok(ApiResponse.noContent("Logged out successfully"));
    }

    @PatchMapping("/password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request) {

        Long userId = SecurityUtils.getCurrentUserId();
        userService.changePassword(userId, request.currentPassword(), request.newPassword());
        return ResponseEntity.ok(ApiResponse.noContent("Password changed successfully"));
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Strips the {@code "Bearer "} prefix from an Authorization header value.
     *
     * <p>Centralised here so every endpoint that receives a Bearer token uses
     * the same extraction and validation logic. A missing or malformed header
     * is a client error — surfaced as {@code 400 Bad Request}.
     *
     * @param bearerToken the raw value of the {@code Authorization} header
     * @return the raw JWT string without the {@code "Bearer "} prefix
     * @throws BusinessException {@code 400} if the header is null or does not start with "Bearer "
     */
    private String extractBearerToken(String bearerToken) {
        if (bearerToken == null || !bearerToken.startsWith("Bearer ")) {
            throw BusinessException.badRequest(
                    "Authorization header is missing or malformed — expected 'Bearer <token>'",
                    "INVALID_AUTH_HEADER");
        }
        return bearerToken.substring(7);
    }

    /**
     * Constructs a {@link LoginResponse} from a loaded user and freshly issued tokens.
     * Extracted to avoid duplicating the record construction across login and refresh.
     *
     * @param user         the fully loaded user entity
     * @param accessToken  the newly issued access token
     * @param refreshToken the newly issued refresh token
     * @return a fully populated {@link LoginResponse}
     */
    private LoginResponse buildLoginResponse(User user, String accessToken, String refreshToken) {
        return new LoginResponse(
                user.getId(),
                user.getName(),
                user.getPhoneNumber(),
                user.getRole(),
                accessToken,
                refreshToken);
    }
}
