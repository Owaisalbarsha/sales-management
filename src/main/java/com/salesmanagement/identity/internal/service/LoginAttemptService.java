package com.salesmanagement.identity.internal.service;

import com.salesmanagement.identity.internal.controller.AuthController;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks failed login attempts and enforces a temporary lockout after
 * repeated failures — a brute-force mitigation measure.
 *
 * <p><b>The rule:</b> after {@value #MAX_ATTEMPTS} consecutive failed login
 * attempts for the same phoneNumber, further login requests for that phoneNumber are
 * rejected immediately for {@value #LOCKOUT_MINUTES} minutes — without
 * even checking the password. This makes brute-force attacks impractical
 * because the attacker must wait after every 5 guesses.
 *
 * <p><b>Why per-phoneNumber, not per-IP:</b> IP-based throttling is easily
 * bypassed with rotating proxies. phoneNumber-based throttling protects the
 * specific account being targeted regardless of the attacker's IP.
 * The downside — a legitimate user locked out by someone else trying
 * their phoneNumber — is acceptable in a closed internal system where the
 * ADMIN can intervene.
 *
 * <p><b>Lifecycle:</b>
 * <ul>
 *   <li>Every failed login increments the counter for that phoneNumber.</li>
 *   <li>A successful login resets the counter to zero immediately.</li>
 *   <li>After {@value #MAX_ATTEMPTS} failures, the phoneNumber is locked for
 *       {@value #LOCKOUT_MINUTES} minutes from the time of the last failure.</li>
 *   <li>After the lockout window expires, the counter resets and the
 *       user can try again.</li>
 * </ul>
 *
 * <p><b>Storage:</b> in-memory {@link ConcurrentHashMap}. Lockout state
 * does not survive a server restart — acceptable for a single-node
 * deployment. Production upgrade: Redis with TTL-based expiry.
 *
 * <p><b>No database interaction:</b> this service never touches the
 * {@code User} entity or {@code UserStatus}. Account lockout here is
 * transient and separate from the permanent SUSPENDED status that an
 * ADMIN sets via {@code PATCH /api/users/{id}/status}. They are two
 * different concepts — one is automated and temporary, the other is
 * manual and deliberate.
 */
@Slf4j
@Service
public class LoginAttemptService {

    /** Maximum consecutive failed attempts before lockout. */
    private static final int MAX_ATTEMPTS = 5;

    /** Minutes the account stays locked after exceeding MAX_ATTEMPTS. */
    private static final int LOCKOUT_MINUTES = 15;

    /**
     * Tracks failed attempts per phoneNumber.
     * Key: lowercase phoneNumber.
     * Value: mutable record holding attempt count and last failure time.
     */
    private final Map<String, AttemptRecord> attempts = new ConcurrentHashMap<>();

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the given phoneNumber is currently locked out
     * due to exceeding the maximum allowed failed attempts.
     *
     * <p>Called by {@link AuthController} <b>before</b> delegating to
     * {@code AuthenticationManager}. If locked, the controller rejects
     * the request immediately without touching the database or running
     * BCrypt — saving resources against sustained attacks.
     *
     * <p>If the lockout window has expired, the record is cleared and
     * the method returns {@code false}, allowing the user to try again.
     *
     * @param phoneNumber the login phoneNumber to check (case-insensitive)
     * @return {@code true} if the account is temporarily locked out
     */
    public boolean isLocked(String phoneNumber) {
        AttemptRecord record = attempts.get(phoneNumber.toLowerCase());

        if (record == null) {
            return false;
        }

        if (record.count < MAX_ATTEMPTS) {
            return false;
        }

        // Lockout window has expired — reset and allow
        if (record.lastFailure.plusSeconds(LOCKOUT_MINUTES * 60L).isBefore(Instant.now())) {
            attempts.remove(phoneNumber.toLowerCase());
            log.info("Lockout expired for phoneNumber={}, attempts reset", phoneNumber);
            return false;
        }

        return true;
    }

    /**
     * Records a failed login attempt for the given phoneNumber.
     *
     * <p>Called by {@link AuthController} when {@code AuthenticationManager}
     * throws {@code BadCredentialsException} or {@code DisabledException}.
     *
     * @param phoneNumber the phoneNumber that failed authentication
     */
    public void recordFailure(String phoneNumber) {
        String key = phoneNumber.toLowerCase();
        AttemptRecord record = attempts.computeIfAbsent(key, k -> new AttemptRecord());
        record.count++;
        record.lastFailure = Instant.now();

        if (record.count >= MAX_ATTEMPTS) {
            log.warn("Account locked after {} failed attempts: phoneNumber={}, locked for {} minutes",
                    record.count, phoneNumber, LOCKOUT_MINUTES);
        } else {
            log.info("Failed login attempt {} of {} for phoneNumber={}",
                    record.count, MAX_ATTEMPTS, phoneNumber);
        }
    }

    /**
     * Clears all failed attempt history for the given phoneNumber.
     *
     * <p>Called by {@link AuthController} after a successful login.
     * If Ahmed fat-fingered his password 4 times then gets it right
     * on the 5th, his counter resets to zero — he is not punished for
     * eventually succeeding.
     *
     * @param phoneNumber the phoneNumber that successfully authenticated
     */
    public void recordSuccess(String phoneNumber) {
        String key = phoneNumber.toLowerCase();
        if (attempts.remove(key) != null) {
            log.debug("Failed attempt counter cleared for phoneNumber={}", phoneNumber);
        }
    }

    /**
     * Returns the number of minutes remaining in the lockout period
     * for the given phoneNumber. Returns {@code 0} if the phoneNumber is not locked.
     *
     * <p>Used by {@link AuthController} to include the remaining wait
     * time in the error response, so the user (or the mobile app) knows
     * when to retry instead of hammering the endpoint blindly.
     *
     * @param phoneNumber the phoneNumber to check
     * @return remaining lockout minutes, or 0 if not locked
     */
    public long getRemainingLockoutMinutes(String phoneNumber) {
        AttemptRecord record = attempts.get(phoneNumber.toLowerCase());

        if (record == null || record.count < MAX_ATTEMPTS) {
            return 0;
        }

        Instant unlockTime = record.lastFailure.plusSeconds(LOCKOUT_MINUTES * 60L);
        long remainingSeconds = java.time.Duration.between(Instant.now(), unlockTime).getSeconds();
        return Math.max(0, (remainingSeconds + 59) / 60); // round up to nearest minute
    }

    // ─── Internal record ──────────────────────────────────────────────────────

    /**
     * Mutable container for per-phoneNumber attempt tracking.
     * Not a Java record because both fields need to be updated in place.
     */
    private static class AttemptRecord {
        int     count       = 0;
        Instant lastFailure = Instant.now();
    }
}