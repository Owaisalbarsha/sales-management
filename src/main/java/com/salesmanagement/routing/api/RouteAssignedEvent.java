package com.salesmanagement.routing.api;

import java.time.Instant;

/**
 * Domain event: a route has been assigned to a representative (FR-107). Consumed
 * by the {@code notification} module to tell the rep a route is now theirs.
 *
 * <p><strong>Why this type lives in {@code routing.api}.</strong> It is a fact
 * <em>about a route</em>, produced by {@code routing} when it creates/assigns
 * one. {@code notification} depends on {@code routing} (it may enrich via
 * {@code RoutingFacade}), never the other way round, so declaring the event here
 * keeps the module edge one-directional (notification → routing) and avoids the
 * dependency cycle {@code ApplicationModules.verify()} would reject. Same
 * rationale as {@link RouteExecutionStarted} and {@link RouteVisitsFinalized}.</p>
 *
 * <p><strong>Published by</strong> {@code routing}'s assignment flow (when a route
 * is created for, or reassigned to, a rep). <strong>Consumed by</strong> an
 * {@code @ApplicationModuleListener} in {@code notification}. The listener is the
 * only consumer today; the event carries enough to raise the notification without
 * a follow-up facade call in the common case.</p>
 *
 * <p><strong>Scope note (FR-107, "route changes or newly assigned customers").</strong>
 * This event covers the "route assigned" half. A separate customer-list-changed
 * signal (a rep's stops added/removed after assignment) is intentionally not
 * emitted in v1; it is an additive event on the same pattern when the routing
 * edit flow needs it. Documented rather than silently dropped.</p>
 *
 * @param routeId          the route that was assigned
 * @param representativeId the rep the route now belongs to
 * @param routeName        the route's display name (so the notification message
 *                         needs no follow-up lookup)
 * @param at               when the assignment occurred (server UTC instant)
 */
public record RouteAssignedEvent(
        Long    routeId,
        Long    representativeId,
        String  routeName,
        Instant at
) {}
