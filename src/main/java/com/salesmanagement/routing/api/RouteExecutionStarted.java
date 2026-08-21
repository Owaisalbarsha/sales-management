package com.salesmanagement.routing.api;

import java.time.Instant;

/**
 * Domain event: a rep has begun executing a route (the first check-in landed on a route that
 * was still {@code PLANNED}). Consumed by {@code routing} to flip {@code PLANNED -> ACTIVE}.
 *
 * <p><strong>Why this type lives in {@code routing.api}, not in {@code visit}:</strong> it is a
 * fact <em>about a route</em>, and {@code visit} already depends on {@code routing} (it calls
 * {@code RoutingFacade} at check-in). Publishing an event whose type belongs to {@code routing}
 * keeps the module edge one-directional (visit -> routing). Defining it in {@code visit} and
 * having {@code routing} listen would add a routing -> visit edge and create a dependency cycle
 * that {@code ApplicationModules.verify()} rejects.</p>
 *
 * <p>Published by {@code visit}'s check-in flow; consumed by an {@code @ApplicationModuleListener}
 * in {@code routing.internal}. The listener is idempotent: a route already {@code ACTIVE} or
 * {@code COMPLETED} is left unchanged.</p>
 *
 * @param routeId          the route that has started
 * @param representativeId the rep who checked in
 * @param at               when the triggering check-in occurred (client-supplied UTC instant)
 */
public record RouteExecutionStarted(
        Long    routeId,
        Long    representativeId,
        Instant at
) {}
