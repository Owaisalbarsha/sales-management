package com.salesmanagement.routing.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * Request body for creating a route.
 *
 * <p>Bean validation catches shape errors (no rep, no territory, blank name). Stateful
 * checks are in the service: the rep is a {@code SALES_REP}, the territory exists, no
 * existing route for that rep on that day, every initial customer exists, is ACTIVE, and
 * belongs to the route's territory.</p>
 *
 * @param representativeId the sales rep who owns this route; required
 * @param territoryId      the territory this route covers; required
 * @param name             a human label, e.g. "North loop — Tue"; required
 * @param routeDate        the day this route is for; optional, defaults to today
 * @param customerIds      optional initial stops, sequenced in the given order
 */
public record CreateRouteRequest(

        @NotNull(message = "representativeId is required")
        Long representativeId,

        @NotNull(message = "territoryId is required")
        Long territoryId,

        @NotBlank(message = "name is required")
        String name,

        LocalDate routeDate,

        List<@NotNull Long> customerIds
) {}
