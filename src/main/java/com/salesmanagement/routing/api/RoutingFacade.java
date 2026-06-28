package com.salesmanagement.routing.api;

import com.salesmanagement.routing.internal.entity.Route;
import com.salesmanagement.routing.internal.entity.RouteCustomerAssignment;
import com.salesmanagement.routing.internal.repository.RouteRepository;
import com.salesmanagement.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Public API surface of the {@code routing} module — the only type other modules may import
 * from routing. Mirrors {@code CustomerFacade} / {@code TerritoryFacade}: read-only, and it
 * reaches into its own {@code internal} package (allowed within a module) but never leaks the
 * {@link Route} entity or the {@code RouteStatus} enum outward — callers receive a
 * {@link RouteInfo} or a primitive.</p>
 *
 * <p>Built ahead of its consumers: {@code visit} (next in the build order) needs to validate a
 * route id and that a customer is a stop on it; {@code sync} needs today's route to push down.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RoutingFacade {

    private final RouteRepository routeRepository;

    /**
     * The rep's route for today, if any. Absence is normal (a rep may have no route today),
     * so this returns {@link Optional} rather than throwing — sync should treat empty as
     * "nothing to push", not an error.
     *
     * @param representativeId the sales rep
     * @return today's route info, or empty
     */
    public Optional<RouteInfo> getRouteForToday(Long representativeId) {
        return routeRepository
                .findWithAssignmentsByRepresentativeIdAndRouteDate(representativeId, LocalDate.now())
                .map(RoutingFacade::toInfo);
    }

    /**
     * The route with the given id.
     *
     * @param routeId the route id
     * @return the route info
     * @throws BusinessException 404 if no route has this id
     */
    public RouteInfo getRouteInfo(Long routeId) {
        Route route = routeRepository.findWithAssignmentsById(routeId)
                .orElseThrow(() -> BusinessException.notFound(
                        "Route not found: " + routeId, "ROUTE_NOT_FOUND"));
        return toInfo(route);
    }

    /**
     * Whether a customer is a stop on a route. Used by {@code visit} to reject a check-in for
     * a customer who is not on the rep's route (SRS alternative flow "customer not on today's
     * route"). Returns {@code false} for an unknown route rather than throwing.
     *
     * @param routeId    the route id
     * @param customerId the customer id
     * @return {@code true} if the customer is a stop on the route
     */
    public boolean isCustomerOnRoute(Long routeId, Long customerId) {
        return routeRepository.findWithAssignmentsById(routeId)
                .map(r -> r.getAssignments().stream()
                        .anyMatch(a -> a.getCustomerId().equals(customerId)))
                .orElse(false);
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
}
