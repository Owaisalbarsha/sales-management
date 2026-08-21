package com.salesmanagement.visit.internal.enums;

/**
 * Lifecycle of a customer visit. Matches the ERD {@code VISIT.Status} exactly.
 *
 * <p>There is deliberately no {@code PENDING}/{@code SCHEDULED} value: a visit row is
 * created only when the rep checks in, so an un-reached stop has no row to attach a
 * "scheduled" state to. The plan for a stop lives on
 * {@code route_customer_assignments} (owned by {@code routing}); this enum records what
 * actually happened.</p>
 *
 * <ul>
 *   <li>{@code IN_PROGRESS} — the rep has checked in and not yet checked out.</li>
 *   <li>{@code COMPLETED}  — the rep checked out (terminal).</li>
 *   <li>{@code MISSED}     — the stop was never visited; materialised at end-of-day or by
 *       the nightly sweep for stops with no visit (terminal).</li>
 * </ul>
 */
public enum VisitStatus {
    IN_PROGRESS,
    COMPLETED,
    MISSED
}
