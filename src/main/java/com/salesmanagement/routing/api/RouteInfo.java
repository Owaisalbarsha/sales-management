package com.salesmanagement.routing.api;

import java.time.LocalDate;
import java.util.List;

/**
 * Immutable public projection of a {@code Route}, safe to pass across module boundaries.
 * The only route type other modules may hold.
 *
 * <p>Consumed by {@code visit} (validate a check-in target is a stop on today's route) and
 * by {@code sync} (push the rep's route down on initial sync). {@code status} is surfaced as
 * a {@code String}, not the internal {@code RouteStatus} enum — exposing the enum would force
 * other modules to import a {@code routing.internal} type, which the module boundary forbids.</p>
 *
 * @param id               route id
 * @param representativeId owning sales rep
 * @param territoryId      the route's territory
 * @param name             route label
 * @param date             the day this route is for
 * @param status           "PLANNED" | "ACTIVE" | "COMPLETED"
 * @param optimized        whether the stop order was auto-computed
 * @param stops            stops in visit order
 */
public record RouteInfo(
        Long                      id,
        Long                      representativeId,
        Long                      territoryId,
        String                    name,
        LocalDate                 date,
        String                    status,
        boolean                   optimized,
        List<RouteAssignmentInfo> stops
) {}
