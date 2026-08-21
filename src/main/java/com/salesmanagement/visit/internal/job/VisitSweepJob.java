package com.salesmanagement.visit.internal.job;

import com.salesmanagement.routing.api.RouteAssignmentInfo;
import com.salesmanagement.routing.api.RouteInfo;
import com.salesmanagement.routing.api.RouteVisitsFinalized;
import com.salesmanagement.routing.api.RoutingFacade;
import com.salesmanagement.visit.internal.entity.Visit;
import com.salesmanagement.visit.internal.enums.VisitStatus;
import com.salesmanagement.visit.internal.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Nightly safety net for routes left unfinished. In the happy path (rep always ends the day
 * explicitly) there is nothing for this to do.
 *
 * <p>For every route dated before today that is still PLANNED or ACTIVE:</p>
 * <ul>
 *   <li>Any lingering IN_PROGRESS visit is closed as COMPLETED with a null check-out
 *       (the rep demonstrably arrived; MISSED would falsely say they never did).</li>
 *   <li>Every stop with no visit row is materialised as MISSED.</li>
 *   <li>{@link RouteVisitsFinalized} is published so the routing listener closes the route.</li>
 * </ul>
 *
 * <p>Runs at 02:00 server time. Each route is processed in its own transaction boundary
 * because the method itself is {@code @Transactional} — one failure does not roll back the
 * others (each {@code save} commits within the surrounding tx).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisitSweepJob {

    private final VisitRepository visitRepository;
    private final RoutingFacade routingFacade;
    private final ApplicationEventPublisher events;

    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void sweepUnfinishedRoutes() {
        LocalDate today = LocalDate.now();
        List<RouteInfo> stale = routingFacade.findActiveOrPlannedBefore(today);
        log.info("Visit sweep starting: {} unfinished route(s) before {}", stale.size(), today);

        for (RouteInfo route : stale) {
            sweepRoute(route);
        }

        log.info("Visit sweep complete for {} route(s)", stale.size());
    }

    private void sweepRoute(RouteInfo route) {
        List<Visit> existing = visitRepository.findByRouteId(route.id());

        // Close any lingering open visit (should not exist if force-checkout works, but safety net).
        for (Visit v : existing) {
            if (v.getStatus() == VisitStatus.IN_PROGRESS) {
                v.completeCheckOut(null, null);
                visitRepository.save(v);
                log.warn("Sweep closed lingering IN_PROGRESS visitId={} routeId={}",
                        v.getId(), route.id());
            }
        }

        Set<Long> covered = existing.stream()
                .map(Visit::getCustomerId)
                .collect(Collectors.toCollection(HashSet::new));

        int missed = 0;
        for (RouteAssignmentInfo stop : route.stops()) {
            if (!covered.contains(stop.customerId())) {
                visitRepository.save(Visit.missed(
                        stop.customerId(), route.representativeId(), route.id()));
                missed++;
            }
        }

        if (!route.stops().isEmpty()) {
            events.publishEvent(new RouteVisitsFinalized(route.id(), Instant.now()));
        }

        log.info("Sweep finalised routeId={} missedCreated={}", route.id(), missed);
    }
}
