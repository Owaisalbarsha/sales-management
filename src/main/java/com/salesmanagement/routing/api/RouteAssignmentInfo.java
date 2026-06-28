package com.salesmanagement.routing.api;

/**
 * Immutable public projection of one route stop, safe to pass across module boundaries.
 *
 * <p>Carries the surrogate {@code assignmentId} (the {@code routeCustomerAssignmentId} the
 * {@code visit} module stores as a plain id column when a rep checks in), the cross-module
 * {@code customerId}, and the visit {@code sequenceNumber}. Internal entities and enums are
 * never exposed.</p>
 *
 * @param assignmentId   surrogate id of the stop
 * @param customerId     the customer to visit
 * @param sequenceNumber visit order on the route
 */
public record RouteAssignmentInfo(
        Long assignmentId,
        Long customerId,
        int  sequenceNumber
) {}
