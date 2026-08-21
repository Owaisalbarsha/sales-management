package com.salesmanagement.routing.internal.dto;

import java.time.LocalDate;

/**
 * Request body for editing a route header. Both fields optional — only the non-null ones
 * are applied. The rep and territory are intentionally <em>not</em> editable after creation:
 * changing either would orphan stops in the wrong territory. Create a new route instead.
 *
 * @param name      new label; ignored if null
 * @param routeDate new date; ignored if null (re-checks the one-route-per-rep-per-day rule)
 */
public record UpdateRouteRequest(
        String name,
        LocalDate routeDate
) {}
