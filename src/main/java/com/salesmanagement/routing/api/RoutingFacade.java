package com.salesmanagement.routing.api;

import com.salesmanagement.routing.internal.entity.Route;
import com.salesmanagement.routing.internal.entity.RouteCustomerAssignment;
import com.salesmanagement.routing.internal.enums.RouteStatus;
import com.salesmanagement.routing.internal.repository.RouteRepository;
import com.salesmanagement.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Public API surface of the {@code routing} module — the only type other modules may import
 * from routing. Read-only: no writes go through this facade.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RoutingFacade {

    private final RouteRepository routeRepository;

    /**
     * The rep's route for today, if any. Absence is normal (a rep may have no route today),
     * so this returns {@link Optional} rather than throwing.
     */
    public Optional<RouteInfo> getRouteForToday(Long representativeId) {
        return routeRepository
                .findWithAssignmentsByRepresentativeIdAndRouteDate(representativeId, LocalDate.now())
                .map(RoutingFacade::toInfo);
    }

    /**
     * The route with the given id.
     *
     * @throws BusinessException 404 if no route has this id
     */
    public RouteInfo getRouteInfo(Long routeId) {
        Route route = routeRepository.findWithAssignmentsById(routeId)
                .orElseThrow(() -> BusinessException.notFound(
                        "Route not found: " + routeId, "ROUTE_NOT_FOUND"));
        return toInfo(route);
    }

    /**
     * Whether a customer is a stop on a route. Returns {@code false} for an unknown route
     * rather than throwing.
     */
    public boolean isCustomerOnRoute(Long routeId, Long customerId) {
        return routeRepository.findWithAssignmentsById(routeId)
                .map(r -> r.getAssignments().stream()
                        .anyMatch(a -> a.getCustomerId().equals(customerId)))
                .orElse(false);
    }

    /**
     * Routes whose date is strictly before {@code date} and whose status is still
     * {@code PLANNED} or {@code ACTIVE}. Used by the visit module's nightly sweep to close
     * routes the reps forgot to end, including routes that received zero check-ins (which
     * have no visit rows, so only routing can surface them).
     *
     * @param date exclusive upper bound — typically {@code LocalDate.now()}
     */
    public List<RouteInfo> findActiveOrPlannedBefore(LocalDate date) {
        return routeRepository
                .findWithAssignmentsByRouteDateBeforeAndStatusIn(
                        date, List.of(RouteStatus.PLANNED, RouteStatus.ACTIVE))
                .stream()
                .map(RoutingFacade::toInfo)
                .toList();
    }

    private static RouteInfo toInfo(Route route) {
        List<RouteAssignmentInfo> stops = route.getAssignments().stream()
                .sorted(Comparator.comparingInt(RouteCustomerAssignment::getSequenceNumber))
                .map(a -> new RouteAssignmentInfo(a.getId(), a.getCustomerId(), a.getSequenceNumber()))
                .toList();
        return new RouteInfo(
                route.getId(),
                route.getRepresentativeId(),
                route.getTerritoryId(),
                route.getName(),
                route.getRouteDate(),
                route.getStatus().name(),
                route.isOptimized(),
                stops);
    }

    /**
     * Every route whose business date falls in the half-open window {@code [from, to)}, optionally
     * scoped to one rep — the planned side of the route performance reports (FR-125/126/127).
     *
     * <p>Reporting calls this first, then feeds the returned route ids to
     * {@code VisitFacade.findVisitsByRouteIds} to get the actual visit outcomes. Assignments (the
     * planned stops) are fetch-joined so the caller can count planned-vs-completed without a second
     * trip. Half-open range and {@code LocalDate} match the invoicing aggregates and the
     * {@code Route.routeDate} column (D9).</p>
     *
     * <p>No status filter: a route performance report wants PLANNED, ACTIVE and COMPLETED routes
     * alike (a route still ACTIVE at report time is itself a finding). Callers that want only closed
     * routes filter on {@link RouteInfo#status()}.</p>
     *
     * @param from  inclusive start of the business-date window
     * @param to    exclusive end of the business-date window
     * @param repId optional rep filter; {@code null} for all reps
     * @return routes in the window with their planned stops, newest first
     */
    @Transactional(readOnly = true)
    public List<RouteInfo> findRoutesInRange(LocalDate from, LocalDate to, Long repId) {
        return routeRepository.findWithAssignmentsInRange(from, to, repId).stream()
                .map(RoutingFacade::toInfo)
                .toList();
    }

    /**
     * Batch-resolves route names for a set of ids. Returns a map of id -> name.
     * Ids not found in the DB are simply absent from the map (no exception).
     * Prefer this over N calls to {@link #getRouteInfo} when resolving names for a list response.
     */
    public Map<Long, String> getRouteNames(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return routeRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Route::getId, Route::getName));
    }
}
