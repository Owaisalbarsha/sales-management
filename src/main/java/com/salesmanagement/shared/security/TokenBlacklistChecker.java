package com.salesmanagement.shared.security;

/**
 * Seam between JwtAuthFilter (shared) and the token blacklist implementation
 * that lives in the identity module.
 *
 * Why an interface here rather than a direct Redis call in JwtAuthFilter:
 *
 *   JwtAuthFilter is in shared. It cannot import from identity/internal (R-1).
 *   It cannot import Redis directly (shared shouldn't carry that dependency).
 *   Solution: define the contract in shared, implement it in identity.
 *
 * Spring bean resolution:
 *   - shared/config provides a @Bean NoOpTokenBlacklistChecker that always
 *     returns false. This allows the app to start even before the identity module
 *     provides its implementation.
 *   - identity/internal provides a @Primary @Bean RealTokenBlacklistChecker
 *     backed by Redis (or the DB fallback) that overrides the no-op.
 *   - JwtAuthFilter injects TokenBlacklistChecker — Spring injects whichever
 *     implementation is @Primary at runtime.
 *
 * Contract:
 *   isBlacklisted(jti) returns true if the JWT with this ID has been explicitly
 *   revoked (user logged out, session replaced by FR-5 single-session rule,
 *   or admin force-logout via BR-7). The filter rejects blacklisted tokens
 *   even if the signature and expiry are otherwise valid.
 */
public interface TokenBlacklistChecker {

    /**
     * @param jti the JWT ID claim (unique identifier per token)
     * @return true if the token has been revoked and must be rejected
     */
    boolean isBlacklisted(String jti);
}