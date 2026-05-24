package com.salesmanagement.shared.config;

import com.salesmanagement.shared.security.TokenBlacklistChecker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared infrastructure configuration.
 *
 * Provides default (fallback) beans that every module relies on.
 * Business modules override these defaults with @Primary beans when
 * they need real implementations.
 *
 * Currently provides:
 *   - NoOpTokenBlacklistChecker: allows the application to start and the
 *     JwtAuthFilter to function even before the identity module's real
 *     blacklist implementation (backed by Redis or DB) is provided.
 *     @ConditionalOnMissingBean ensures the no-op is replaced the moment
 *     identity registers its @Primary implementation.
 */
@Slf4j
@Configuration
public class SharedConfig {

    /**
     * Default TokenBlacklistChecker — always says "not blacklisted".
     *
     * This is intentionally permissive because:
     * 1. At startup (before identity module is fully configured) this prevents
     *    NPEs in JwtAuthFilter.
     * 2. In @ApplicationModuleTest for modules other than identity, this
     *    prevents test failures caused by a missing bean.
     *
     * The identity module's RealTokenBlacklistChecker is @Primary and takes
     * precedence in the full application context. @ConditionalOnMissingBean
     * guarantees this no-op is never active when the real one is present.
     */
    @Bean
    @ConditionalOnMissingBean(TokenBlacklistChecker.class)
    public TokenBlacklistChecker noOpTokenBlacklistChecker() {
        log.warn(
                "Using no-op TokenBlacklistChecker — token blacklisting is DISABLED. " +
                        "This is expected in tests and during initial startup. " +
                        "Ensure identity module provides the real implementation in production."
        );
        return jti -> false;
    }
}