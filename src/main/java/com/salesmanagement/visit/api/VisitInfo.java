package com.salesmanagement.visit.api;

import java.time.Instant;

/**
 * Public read model of a visit, exposed to other modules via {@link VisitFacade}.
 *
 * <p>Deliberately minimal: enough for {@code invoicing} to bind an invoice to a visit and check
 * who/what/what-state, without leaking the internal {@code Visit} entity or its enum. {@code status}
 * is a plain {@code String} (the {@code VisitStatus} name), consistent with {@code RouteInfo}.</p>
 *
 * @param visitId          the visit id
 * @param routeId          the route the stop belongs to
 * @param customerId       the customer visited
 * @param representativeId the rep who performed (or was assigned) the visit
 * @param status           {@code IN_PROGRESS} / {@code COMPLETED} / {@code MISSED}
 * @param checkInTime      UTC instant of check-in, or {@code null} for a {@code MISSED} visit
 * @param checkOutTime     UTC instant of check-out, or {@code null} if not yet checked out
 */
public record VisitInfo(
        Long    visitId,
        Long    routeId,
        Long    customerId,
        Long    representativeId,
        String  status,
        Instant checkInTime,
        Instant checkOutTime
) {}
