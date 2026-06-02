package com.salesmanagement.identity.internal;

/**
 * Lifecycle status of a {@link User} account.
 *
 * <ul>
 *   <li>{@code ACTIVE}    – account is fully operational; the user can log in.</li>
 *   <li>{@code INACTIVE}  – account has been administratively disabled (e.g. rep left
 *                           the company). Login is refused; historical data is preserved.</li>
 *   <li>{@code SUSPENDED} – temporary restriction (e.g. policy violation). Login is
 *                           refused until an ADMIN restores the account to ACTIVE.</li>
 * </ul>
 *
 * Transitions allowed (enforced at the application layer, not DB):
 * <pre>
 *   ACTIVE ──→ INACTIVE
 *   ACTIVE ──→ SUSPENDED
 *   SUSPENDED ──→ ACTIVE
 *   INACTIVE  ──→ ACTIVE   (re-hire scenario)
 * </pre>
 *
 * Persisted as a VARCHAR via {@code @Enumerated(EnumType.STRING)}.
 * Never use ordinal — ordinal breaks silently when values are reordered.
 */
public enum UserStatus {

    ACTIVE,
    INACTIVE,
    SUSPENDED;

    /**
     * Returns {@code true} if this status permits the user to authenticate.
     * Centralising the check here means no controller or service ever
     * hard-codes {@code status == ACTIVE} inline.
     */
    public boolean allowsLogin() {
        return this == ACTIVE;
    }
}