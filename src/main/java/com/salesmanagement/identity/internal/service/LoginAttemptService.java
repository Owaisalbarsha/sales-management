package com.salesmanagement.identity.internal.service;

import com.salesmanagement.identity.internal.controller.AuthController;
import com.salesmanagement.identity.internal.entity.User;
import com.salesmanagement.identity.internal.entity.UserStatus;
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
 * attempts for the same email, further login requests for that email are
 * rejected immediately for {@value #LOCKOUT_MINUTES} minutes — without
 * even checking the password. This makes brute-force attacks impractical
 * because the attacker must wait after every 5 guesses.
 *
 * <p><b>Why per-email, not per-IP:</b> IP-based throttling is easily
 * bypassed with rotating proxies. Email-based throttling protects the
 * specific account being targeted regardless of the attacker's IP.
 * The downside — a legitimate user locked out by someone else trying
 * their email — is acceptable in a closed internal system where the
 * ADMIN can intervene.
 *
 * <p><b>Lifecycle:</b>
 * <ul>
 *   <li>Every failed login increments the counter for that email.</li>
 *   <li>A successful login resets the counter to zero immediately.</li>
 *   <li>After {@value #MAX_ATTEMPTS} failures, the email is locked for
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
 * {@link User} entity or {@link UserStatus}. Account lockout here is
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
     * Tracks failed attempts per email.
     * Key: lowercase email.
     * Value: mutable record holding attempt count and last failure time.
     */
    private final Map<String, AttemptRecord> attempts = new ConcurrentHashMap<>();

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the given email is currently locked out
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
     * @param email the login email to check (case-insensitive)
     * @return {@code true} if the account is temporarily locked out
     */
    public boolean isLocked(String email) {
        AttemptRecord record = attempts.get(email.toLowerCase());

        if (record == null) {
            return false;
        }

        if (record.count < MAX_ATTEMPTS) {
            return false;
        }

        // Lockout window has expired — reset and allow
        if (record.lastFailure.plusSeconds(LOCKOUT_MINUTES * 60L).isBefore(Instant.now())) {
            attempts.remove(email.toLowerCase());
            log.info("Lockout expired for email={}, attempts reset", email);
            return false;
        }

        return true;
    }

    /**
     * Records a failed login attempt for the given email.
     *
     * <p>Called by {@link AuthController} when {@code AuthenticationManager}
     * throws {@code BadCredentialsException} or {@code DisabledException}.
     *
     * @param email the email that failed authentication
     */
    public void recordFailure(String email) {
        String key = email.toLowerCase();
        AttemptRecord record = attempts.computeIfAbsent(key, k -> new AttemptRecord());
        record.count++;
        record.lastFailure = Instant.now();

        if (record.count >= MAX_ATTEMPTS) {
            log.warn("Account locked after {} failed attempts: email={}, locked for {} minutes",
                    record.count, email, LOCKOUT_MINUTES);
        } else {
            log.info("Failed login attempt {} of {} for email={}",
                    record.count, MAX_ATTEMPTS, email);
        }
    }

    /**
     * Clears all failed attempt history for the given email.
     *
     * <p>Called by {@link AuthController} after a successful login.
     * If Ahmed fat-fingered his password 4 times then gets it right
     * on the 5th, his counter resets to zero — he is not punished for
     * eventually succeeding.
     *
     * @param email the email that successfully authenticated
     */
    public void recordSuccess(String email) {
        String key = email.toLowerCase();
        if (attempts.remove(key) != null) {
            log.debug("Failed attempt counter cleared for email={}", email);
        }
    }

    /**
     * Returns the number of minutes remaining in the lockout period
     * for the given email. Returns {@code 0} if the email is not locked.
     *
     * <p>Used by {@link AuthController} to include the remaining wait
     * time in the error response, so the user (or the mobile app) knows
     * when to retry instead of hammering the endpoint blindly.
     *
     * @param email the email to check
     * @return remaining lockout minutes, or 0 if not locked
     */
    public long getRemainingLockoutMinutes(String email) {
        AttemptRecord record = attempts.get(email.toLowerCase());

        if (record == null || record.count < MAX_ATTEMPTS) {
            return 0;
        }

        Instant unlockTime = record.lastFailure.plusSeconds(LOCKOUT_MINUTES * 60L);
        long remainingSeconds = java.time.Duration.between(Instant.now(), unlockTime).getSeconds();
        return Math.max(0, (remainingSeconds + 59) / 60); // round up to nearest minute
    }

    // ─── Internal record ──────────────────────────────────────────────────────

    /**
     * Mutable container for per-email attempt tracking.
     * Not a Java record because both fields need to be updated in place.
     */
    private static class AttemptRecord {
        int     count       = 0;
        Instant lastFailure = Instant.now();
    }
}