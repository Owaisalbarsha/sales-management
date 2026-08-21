package com.salesmanagement.identity.internal.service;

import com.salesmanagement.shared.security.TokenBlacklistChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Production implementation of {@link TokenBlacklistChecker}, backed by
 * {@link TokenBlacklistStore}.
 *
 * <p>Marked {@code @Primary} so Spring injects this bean — not the
 * {@code NoOpTokenBlacklistChecker} from {@code SharedConfig} — into
 * {@code JwtAuthFilter}. {@code SharedConfig} uses
 * {@code @ConditionalOnMissingBean}, so the no-op is never created
 * when this class is on the classpath.
 *
 * <p>This class is deliberately thin — a pure adapter between the shared
 * interface and the internal store. No logic, no transformation. If the
 * store says blacklisted, this says blacklisted.
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class RealTokenBlacklistChecker implements TokenBlacklistChecker {

    private final TokenBlacklistStore blacklistStore;

    @Override
    public boolean isBlacklisted(String jti) {
        boolean result = blacklistStore.isBlacklisted(jti);
        if (result) {
            log.info("Blacklisted token rejected: jti={}", jti);
        }
        return result;
    }
}