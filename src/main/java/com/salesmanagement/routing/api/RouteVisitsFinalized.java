package com.salesmanagement.routing.api;

import java.time.Instant;

/**
 * Domain event: every stop on a route has reached a terminal visit state
 * ({@code COMPLETED} or {@code MISSED}). Consumed by {@code routing} to flip the route to
 * {@code COMPLETED}.
 *
 * <p>The "all stops terminal" decision is computed by {@code visit} (it can read the route's
 * stop list via {@code RoutingFacade.getRouteInfo} and its own visit rows). {@code routing}
 * cannot compute it without reading {@code visit}'s data, which would introduce a
 * routing -> visit edge and, combined with the existing visit -> routing edge, a dependency
 * cycle. So {@code visit} decides and announces; {@code routing} trusts the event and updates
 * its own aggregate. See {@link RouteExecutionStarted} for the same rationale on event location.</p>
 *
 * <p>Emitted on the last check-out that closes a route, on end-of-day (which fills remaining
 * stops as {@code MISSED}), and by the nightly sweep. The routing listener is idempotent and
 * accepts {@code PLANNED} or {@code ACTIVE} as the prior state (a route that received zero
 * check-ins and was swept is still {@code PLANNED} when finalised).</p>
 *
 * @param routeId the route whose stops are all terminal
 * @param at      when finalisation occurred (server UTC instant)
 */
public record RouteVisitsFinalized(
        Long    routeId,
        Instant at
) {}
