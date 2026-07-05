package com.salesmanagement.visit.internal.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code POST /api/visits/end-day}.
 *
 * <p>The rep declares they are done with a route. The service materialises a {@code MISSED}
 * visit for every stop with no visit row, then signals route completion. Rejected (409) if any
 * visit on the route is still {@code IN_PROGRESS} — the rep must check out first.</p>
 *
 * @param routeId the route being closed for the day; required
 */
public record EndDayRequest(

        @NotNull(message = "routeId is required")
        Long routeId
) {}
