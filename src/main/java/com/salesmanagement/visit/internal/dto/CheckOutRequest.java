package com.salesmanagement.visit.internal.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Request body for {@code POST /api/visits/check-out}.
 *
 * <p>The stop is identified by {@code (routeId, customerId)} — the same key the open visit was
 * created under. BR-10 (no check-out without a matching check-in) is enforced in the service:
 * the visit must exist and be {@code IN_PROGRESS}.</p>
 *
 * @param routeId      the route the stop belongs to; required
 * @param customerId   the customer being checked out of; required
 * @param latitude     GPS latitude at departure; required, range-checked in the service
 * @param longitude    GPS longitude at departure; required, range-checked in the service
 * @param checkOutTime client-supplied UTC instant of departure; required
 */
public record CheckOutRequest(

        @NotNull(message = "routeId is required")
        Long routeId,

        @NotNull(message = "customerId is required")
        Long customerId,

        @NotNull(message = "latitude is required")
        BigDecimal latitude,

        @NotNull(message = "longitude is required")
        BigDecimal longitude,

        @NotNull(message = "checkOutTime is required")
        Instant checkOutTime
) {}
