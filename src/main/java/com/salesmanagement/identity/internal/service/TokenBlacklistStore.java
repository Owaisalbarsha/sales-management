package com.salesmanagement.identity.internal.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for revoked JWT token identifiers within the identity module.
 *
 * <p>This component is the single source of truth for token revocation state.
 * It serves two consumers within the identity module:
 * <ul>
 *   <li>{@link SessionService} — writes entries when a session is replaced
 *       (FR-5 single-session rule) or invalidated (logout).</li>
 *   <li>{@link RealTokenBlacklistChecker} — reads entries to implement the
 *       {@link com.salesmanagement.shared.security.TokenBlacklistChecker}
 *       contract consumed by {@code JwtAuthFilter} in shared.</li>
 * </ul>
 *
 * <p><b>Key:</b> the {@code jti} UUID claim embedded in every JWT by
 * . {@code JwtAuthFilter} extracts this via
 * {@code claims.getId()} and passes it to
 * {@link com.salesmanagement.shared.security.TokenBlacklistChecker#isBlacklisted}.
 * Keying on {@code jti} rather than the raw token string keeps entries small —
 * a UUID is 36 characters; a full JWT is several hundred.
 *
 * <p><b>Value:</b> the token's expiry timestamp in milliseconds, used by
 * {@link #evictExpiredEntries()} to remove entries that are no longer needed.
 * A token past its expiry is already rejected by {@code JwtAuthFilter}'s
 * signature verification — keeping its blacklist entry beyond that point
 * wastes memory without adding security value.
 *
 * <p><b>Production upgrade path:</b> replace with a Redis {@code SET} with
 * TTL per entry. Both callers inject this component by type — swapping the
 * implementation requires no changes to either caller.
 */
@Slf4j
@Component
public class TokenBlacklistStore {

    /**
     * Key: {@code jti} UUID of the revoked token.
     * Value: expiry timestamp in milliseconds (epoch), used for cleanup.
     */
    private final Map<String, Long> blacklistedJtis = new ConcurrentHashMap<>();

    /**
     * Adds a token's {@code jti} to the blacklist.
     *
     * <p>Called by {@link SessionService} when a session is replaced or
     * invalidated. The expiry is stored so {@link #evictExpiredEntries()}
     * can remove the entry once the token would have expired anyway.
     *
     * @param jti    the UUID {@code jti} claim of the token to revoke
     * @param expiry the token's expiry date; used solely for cleanup scheduling
     */
    public void blacklist(String jti, Date expiry) {
        blacklistedJtis.put(jti, expiry.getTime());
        log.debug("Token blacklisted: jti={}, expires={}", jti, expiry);
    }

    /**
     * Returns {@code true} if the given {@code jti} has been explicitly revoked.
     *
     * <p>Called by {@link RealTokenBlacklistChecker} on every authenticated request.
     * The lookup is O(1) and lock-free for concurrent reads.
     *
     * @param jti the UUID {@code jti} claim to check
     * @return {@code true} if this token identifier has been blacklisted
     */
    public boolean isBlacklisted(String jti) {
        return blacklistedJtis.containsKey(jti);
    }

    /**
     * Removes entries whose tokens have already expired.
     *
     * <p>Wire to a {@code @Scheduled(fixedRate = 300_000)} method in a
     * {@code @Configuration} class within the identity module. A 5-minute
     * interval is sufficient given the 15-minute access token lifetime —
     * expired entries are redundant (the filter rejects expired tokens before
     * reaching the blacklist check) but harmless until evicted.
     */
    public void evictExpiredEntries() {
        long now     = System.currentTimeMillis();
        int  before  = blacklistedJtis.size();
        blacklistedJtis.entrySet().removeIf(entry -> entry.getValue() < now);
        int  evicted = before - blacklistedJtis.size();
        if (evicted > 0) {
            log.debug("Evicted {} expired blacklist entries", evicted);
        }
    }
}