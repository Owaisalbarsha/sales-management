package com.salesmanagement.visit.internal.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Request body for {@code POST /api/visits/check-in}.
 *
 * <p>The {@code representativeId} is taken from the JWT, never from the body — a rep can only
 * check in as themselves. Bean-validation here only catches shape (required fields);
 * coordinate range, clock-skew, route ownership, route membership and duplicate/open-visit
 * rules are enforced in {@code VisitService}.</p>
 *
 * @param routeId      the route the stop belongs to; required
 * @param customerId   the customer being visited; required
 * @param latitude     GPS latitude at arrival; required, range-checked in the service
 * @param longitude    GPS longitude at arrival; required, range-checked in the service
 * @param checkInTime  client-supplied UTC instant of arrival (offline correctness); required
 */
public record CheckInRequest(

        @NotNull(message = "routeId is required")
        Long routeId,

        @NotNull(message = "customerId is required")
        Long customerId,

        @NotNull(message = "latitude is required")
        BigDecimal latitude,

        @NotNull(message = "longitude is required")
        BigDecimal longitude,

        @NotNull(message = "checkInTime is required")
        Instant checkInTime
) {}
