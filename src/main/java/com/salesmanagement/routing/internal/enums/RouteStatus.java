package com.salesmanagement.routing.internal.enums;

/**
 * Lifecycle of a {@code Route}.
 *
 * <p>Deliberately coarse: the granular per-stop progress lives on {@code VISIT}
 * ({@code IN_PROGRESS | COMPLETED | MISSED}), not here. A fat route state machine
 * would duplicate that. The route only needs to express "planned but not started",
 * "the rep is executing it today", and "the day is done".</p>
 *
 * <p>The ERD declares {@code ROUTE.Status} as a string but never enumerates its
 * values (every other status entity does). These three were agreed for this module.</p>
 *
 * <p>Linear transitions only: {@code PLANNED → ACTIVE → COMPLETED}. No skipping,
 * no going backwards. Enforced in {@code RouteService.updateStatus}.</p>
 */
public enum RouteStatus {

    /** Created by a sales manager; editable; not yet the rep's live route. Default on create. */
    PLANNED,

    /** The rep's live route for the day (stock loaded, visits being executed). */
    ACTIVE,

    /** The day is over; the route is a historical record (terminal). */
    COMPLETED
}
