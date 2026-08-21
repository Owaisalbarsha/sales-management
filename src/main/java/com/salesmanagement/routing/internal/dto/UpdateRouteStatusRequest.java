package com.salesmanagement.routing.internal.dto;

import com.salesmanagement.routing.internal.enums.RouteStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for advancing a route's status. Only the linear transitions
 * {@code PLANNED → ACTIVE} and {@code ACTIVE → COMPLETED} are accepted; anything else is a
 * 409. (When the visit module lands, check-in/check-out events can drive these transitions
 * automatically — this endpoint is the manual seam until then.)
 *
 * @param status the target status; required
 */
public record UpdateRouteStatusRequest(

        @NotNull(message = "status is required")
        RouteStatus status
) {}
