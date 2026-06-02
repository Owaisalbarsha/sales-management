package com.salesmanagement.identity.internal;

import com.salesmanagement.shared.exception.BusinessException;
import com.salesmanagement.shared.security.JwtTokenValidator;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * Issues, parses, and validates JSON Web Tokens for the identity module.
 *
 * <p>This service is the single point of JWT logic in the entire application.
 * No other class generates or parses tokens. {@link AuthController} calls the
 * generation methods at login and refresh. The {@link JwtTokenValidator} interface
 * — implemented here and injected into {@code JwtAuthFilter} in the shared kernel —
 * provides the validation side without creating a dependency from {@code shared}
 * into {@code identity/internal}.
 *
 * <p><b>Token types:</b> two distinct JWTs are issued per session.
 * <ul>
 *   <li><b>Access token</b> — short-lived (default 15 min), sent on every API request
 *       in the {@code Authorization: Bearer} header. Contains userId, email, role,
 *       status, and a unique {@code jti} so {@code JwtAuthFilter} can reconstruct
 *       {@link com.salesmanagement.shared.security.UserPrincipal} and check the
 *       blacklist without a database round-trip.</li>
 *   <li><b>Refresh token</b> — long-lived (default 7 days), used only on
 *       {@code POST /api/auth/refresh}. Omits {@code role} and {@code status}
 *       so that {@link AuthController} is forced to reload the user from the
 *       database at refresh time, picking up any changes made by an ADMIN.</li>
 * </ul>
 *
 * <p><b>jti claim:</b> every token receives a randomly generated UUID as its
 * JWT ID ({@code jti}). {@code JwtAuthFilter} extracts this via
 * {@code claims.getId()} and passes it to {@link com.salesmanagement.shared.security.TokenBlacklistChecker}.
 * {@link # TokenBlacklistStore} and {@link SessionService} key the blacklist on
 * this same UUID — not the raw token string — keeping blacklist entries small.
 *
 * <p><b>Key derivation:</b> {@code Keys.hmacShaKeyFor(secret.getBytes(UTF_8))},
 * matching exactly what {@code JwtAuthFilter} uses. The secret is read as a raw
 * string, not Base64-decoded. It must be at least 32 characters (256 bits).
 *
 * <p><b>Property:</b> {@code jwt.secret} — matches the {@code @Value} in
 * {@code JwtAuthFilter}. Any mismatch produces a different signing key and
 * causes every token to fail signature verification at the filter.
 *
 * <p>This class is public so {@link AuthController} and {@link SessionService}
 * can inject it across sub-packages within the identity module. Spring Modulith's
 * {@code verify()} still prevents any class outside the identity module from
 * importing it.
 */
@Slf4j
@Service
public class JwtService implements JwtTokenValidator {

    /**
     * Raw secret string used to derive the HMAC-SHA-256 signing key.
     * Must be at least 32 characters. Stored in {@code application-local.properties}
     * under {@code jwt.secret} — never committed to version control.
     */
    @Value("${jwt.secret}")
    private String secret;

    /**
     * Access token lifetime in milliseconds.
     * Default: 900,000 ms (15 minutes).
     */
    @Value("${jwt.access-token-expiry-ms:900000}")
    private long accessTokenExpiryMs;

    /**
     * Refresh token lifetime in milliseconds.
     * Default: 604,800,000 ms (7 days).
     */
    @Value("${jwt.refresh-token-expiry-ms:604800000}")
    private long refreshTokenExpiryMs;

    // ─── Token generation ─────────────────────────────────────────────────────

    /**
     * Issues a short-lived access token for the given user.
     *
     * <p>The token payload includes:
     * <ul>
     *   <li>{@code jti}    — UUID, unique per token; used as the blacklist key</li>
     *   <li>{@code sub}    — the user's email (standard JWT subject claim)</li>
     *   <li>{@code userId} — database primary key; read by {@code JwtAuthFilter}
     *                        to build {@link com.salesmanagement.shared.security.UserPrincipal}
     *                        without a database query</li>
     *   <li>{@code role}   — role name; read by the filter for the same reason</li>
     *   <li>{@code status} — account status name; the filter rejects INACTIVE
     *                        and SUSPENDED accounts at the token level</li>
     *   <li>{@code type: "access"} — prevents a refresh token being submitted
     *                                as an access token</li>
     * </ul>
     *
     * @param user the authenticated user for whom the token is issued
     * @return a signed, compact JWT string
     */
    String generateAccessToken(User user) {
        return buildToken(user, accessTokenExpiryMs, Map.of(
                "userId", user.getId(),
                "role",   user.getRole().name(),
                "status", user.getStatus().name(),
                "type",   "access"
        ));
    }

    /**
     * Issues a long-lived refresh token for the given user.
     *
     * <p>Omits {@code role} and {@code status} deliberately — when the client
     * presents this token to obtain a new access token, {@link AuthController}
     * reloads the user from the database, picking up any changes made by an ADMIN.
     * Embedding role/status would allow a suspended user to keep refreshing
     * for 7 days undetected.
     *
     * @param user the authenticated user for whom the token is issued
     * @return a signed, compact JWT string
     */
    String generateRefreshToken(User user) {
        return buildToken(user, refreshTokenExpiryMs, Map.of(
                "userId", user.getId(),
                "type",   "refresh"
        ));
    }

    /**
     * Shared token construction logic used by both generation methods.
     *
     * <p>A UUID {@code jti} is generated for every token via {@link UUID#randomUUID()}.
     * This is set via {@code id(jti)} which maps to the standard {@code jti} claim,
     * readable by {@code JwtAuthFilter} through {@code claims.getId()}.
     *
     * @param user        the subject of the token
     * @param expiryMs    token lifetime in milliseconds from now
     * @param extraClaims additional claims to embed in the payload
     * @return a signed, compact JWT string
     */
    private String buildToken(User user, long expiryMs, Map<String, Object> extraClaims) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())   // jti — blacklist key
                .claims(extraClaims)
                .subject(user.getEmail())
                .issuedAt(new Date(now))
                .expiration(new Date(now + expiryMs))
                .signWith(signingKey())
                .compact();
    }

    // ─── JwtTokenValidator — called by JwtAuthFilter in shared ───────────────

    /**
     * Returns {@code true} if the token has a valid signature and has not expired.
     *
     * <p>Any {@link JwtException} subclass results in {@code false}. The reason
     * is logged at DEBUG level only — not surfaced to callers — to avoid leaking
     * token internals in error responses.
     *
     * <p>This method never throws.
     *
     * @param token the raw JWT string
     * @return {@code true} if the token is valid and unexpired
     */
    @Override
    public boolean isTokenValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Extracts the {@code sub} claim — the user's email.
     *
     * <p>Must only be called after {@link #isTokenValid} returns {@code true}.
     *
     * @param token a validated JWT string
     * @return the email address embedded in the {@code sub} claim
     */
    @Override
    public String extractEmail(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * Extracts the {@code userId} custom claim — the database primary key.
     *
     * <p>Cast via {@link Number} rather than directly to {@link Long} because
     * Jackson (used by JJWT internally) maps small integers to {@code Integer},
     * not {@code Long}. A direct {@code (Long)} cast throws
     * {@link ClassCastException} for any ID below {@link Integer#MAX_VALUE}.
     *
     * @param token a validated JWT string
     * @return the user's database primary key
     */
    @Override
    public long extractUserId(String token) {
        Number userId = (Number) parseClaims(token).get("userId");
        return userId.longValue();
    }

    /**
     * Extracts the {@code exp} claim as a {@link Date}.
     *
     * <p>Used by {@link SessionService} to set the blacklist TTL on the
     * {@code jti} entry — ensuring blacklisted tokens are evicted from
     * {@link # TokenBlacklistStore} the moment they would have expired naturally.
     *
     * @param token a validated JWT string
     * @return the token's expiry timestamp
     * @throws BusinessException {@code 400} if the token is invalid
     */
    @Override
    public Date extractExpiry(String token) {
        if (!isTokenValid(token)) {
            throw BusinessException.badRequest(
                    "Cannot extract expiry from an invalid token",
                    "INVALID_TOKEN");
        }
        return parseClaims(token).getExpiration();
    }

    /**
     * Extracts the {@code jti} claim — the unique token identifier.
     *
     * <p>Used by {@link SessionService} to obtain the blacklist key when
     * revoking a token. {@link # TokenBlacklistStore} is keyed on this value,
     * matching what {@code JwtAuthFilter} passes to
     * {@link com.salesmanagement.shared.security.TokenBlacklistChecker#isBlacklisted}.
     *
     * @param token a validated JWT string
     * @return the UUID string embedded in the {@code jti} claim
     */
    String extractJti(String token) {
        return parseClaims(token).getId();
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    /**
     * Parses and verifies the token signature, returning the claims payload.
     *
     * @param token the raw JWT string to parse
     * @return the verified {@link Claims} payload
     * @throws JwtException if the token is malformed, expired, or has an invalid signature
     */
    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Derives the HMAC-SHA-256 signing key from the raw secret string.
     *
     * <p>Uses {@code secret.getBytes(UTF_8)} — matching exactly the key derivation
     * in {@code JwtAuthFilter}. Any deviation (e.g. Base64-decoding the secret)
     * produces a different key and causes every token to fail verification.
     *
     * @return the HMAC-SHA-256 signing key
     */
    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}