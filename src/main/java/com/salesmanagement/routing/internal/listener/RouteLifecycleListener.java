package com.salesmanagement.routing.internal.listener;

import com.salesmanagement.routing.api.RouteExecutionStarted;
import com.salesmanagement.routing.api.RouteVisitsFinalized;
import com.salesmanagement.routing.internal.entity.Route;
import com.salesmanagement.routing.internal.enums.RouteStatus;
import com.salesmanagement.routing.internal.repository.RouteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Reacts to route-lifecycle events published by the {@code visit} module.
 *
 * <p>Both events are declared in {@code routing.api} (not in {@code visit.api}) so the
 * module dependency stays one-directional: visit -> routing. Importing a {@code visit.*}
 * type here would create a cycle that {@code ApplicationModules.verify()} rejects.</p>
 *
 * <p>Both handlers are idempotent — a route already in the target state (or past it) is
 * silently left unchanged. This matters because Spring Modulith's event publication log
 * retries unacknowledged events on restart; a replay must not corrupt state.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
class RouteLifecycleListener {

    private final RouteRepository routeRepository;

    /**
     * First check-in on a PLANNED route: flip PLANNED -> ACTIVE.
     * A route already ACTIVE or COMPLETED is left unchanged (idempotent).
     */
    @ApplicationModuleListener
    void on(RouteExecutionStarted event) {
        routeRepository.findWithAssignmentsById(event.routeId()).ifPresent(route -> {
            if (route.getStatus() == RouteStatus.PLANNED) {
                route.setStatus(RouteStatus.ACTIVE);
                routeRepository.save(route);
                log.info("Route {} PLANNED -> ACTIVE (first check-in by rep {})",
                        event.routeId(), event.representativeId());
            }
        });
    }

    /**
     * All stops terminal: flip PLANNED|ACTIVE -> COMPLETED.
     * Accepts PLANNED too — a route swept with zero check-ins is still PLANNED when finalised.
     * A route already COMPLETED is left unchanged (idempotent).
     */
    @ApplicationModuleListener
    void on(RouteVisitsFinalized event) {
        routeRepository.findWithAssignmentsById(event.routeId()).ifPresent(route -> {
            if (route.getStatus() == RouteStatus.PLANNED
                    || route.getStatus() == RouteStatus.ACTIVE) {
                route.setStatus(RouteStatus.COMPLETED);
                routeRepository.save(route);
                log.info("Route {} -> COMPLETED (all stops terminal)", event.routeId());
            }
        });
    }
}
