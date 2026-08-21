package com.salesmanagement.identity.internal.scheduler;

import com.salesmanagement.identity.internal.service.TokenBlacklistStore;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically evicts expired entries from the token blacklist
 * so the in-memory map does not grow unbounded.
 *
 * Runs every 5 minutes. Expired entries are harmless (the filter
 * rejects expired tokens by signature before checking the blacklist)
 * but consume memory unnecessarily.
 *
 * Requires @EnableScheduling on the application class or a
 * configuration class within the identity module.
 */
@Component
@RequiredArgsConstructor
public class BlacklistCleanupScheduler {

    private final TokenBlacklistStore blacklistStore;

    @Scheduled(fixedRate = 300_000) // 5 minutes
    public void cleanup() {
        blacklistStore.evictExpiredEntries();
    }
}