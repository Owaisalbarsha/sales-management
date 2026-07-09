package com.salesmanagement.routing.internal.service;

import com.salesmanagement.customer.api.CustomerFacade;
import com.salesmanagement.customer.api.CustomerInfo;
import com.salesmanagement.identity.api.UserFacade;
import com.salesmanagement.routing.internal.dto.AssignCustomersRequest;
import com.salesmanagement.routing.internal.dto.CreateRouteRequest;
import com.salesmanagement.routing.internal.dto.ReorderRouteRequest;
import com.salesmanagement.routing.internal.dto.RouteResponse;
import com.salesmanagement.routing.internal.dto.UpdateRouteRequest;
import com.salesmanagement.routing.internal.entity.Route;
import com.salesmanagement.routing.internal.entity.RouteCustomerAssignment;
import com.salesmanagement.routing.internal.enums.RouteStatus;
import com.salesmanagement.routing.internal.repository.RouteRepository;
import com.salesmanagement.routing.internal.service.RouteOptimizationService.GeoPoint;
import com.salesmanagement.shared.api.PageResponse;
import com.salesmanagement.shared.exception.BusinessException;
import com.salesmanagement.shared.security.UserRole;
import com.salesmanagement.territory.api.TerritoryFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Business logic for routes — create, assign/remove/reorder stops, optimise, advance status.
 *
 * <p><strong>Cross-module dependencies (all via facades, never internal types):</strong>
 * {@code UserFacade} (verify the owner is a {@code SALES_REP}), {@code TerritoryFacade}
 * (verify the territory exists), {@code CustomerFacade} (verify each stop's customer exists,
 * is ACTIVE, and belongs to the route's territory; and to read coordinates for optimisation).</p>
 *
 * <p><strong>Optimisation flag discipline:</strong> any manual change to the stop set or order
 * (assign, remove, reorder) sets {@code isOptimized = false}; only {@code optimise} sets it
 * {@code true}. The flag therefore always answers "is the current order machine-computed?".</p>
 *
 * <p><strong>Dirty-checking is unreliable in this setup</strong> — every mutation ends with an
 * explicit {@code routeRepository.save(...)}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RouteService {

    private final RouteRepository routeRepository;
    private final RouteOptimizationService optimizationService;
    private final UserFacade userFacade;
    private final TerritoryFacade territoryFacade;
    private final CustomerFacade customerFacade;

    // ── Commands ─────────────────────────────────────────────────────────────

    /**
     * Sales manager creates a route for a rep, optionally with an initial set of stops.
     *
     * @throws BusinessException 404 if the rep, territory, or any customer is unknown;
     *                           409 if the rep already has a route that day;
     *                           400 on duplicate customers in the request;
     *                           422 if the user is not a SALES_REP or a customer is inactive
     *                           or outside the route's territory
     */
    @Transactional
    public RouteResponse create(CreateRouteRequest request) {
        requireSalesRep(request.representativeId());
        requireTerritory(request.territoryId());

        LocalDate date = request.routeDate() != null ? request.routeDate() : LocalDate.now();
        boolean hasOpenRoute = routeRepository
                .existsByRepresentativeIdAndRouteDateAndStatusIn(
                        request.representativeId(),
                        date,
                        java.util.List.of(RouteStatus.PLANNED, RouteStatus.ACTIVE));

        if (hasOpenRoute) {
            throw BusinessException.conflict(
                    "Representative " + request.representativeId()
                            + " already has a PLANNED or ACTIVE route on " + date,
                    "ROUTE_ALREADY_EXISTS_FOR_DAY");
        }

        Route route = new Route(request.representativeId(), request.territoryId(), request.name(), date);

        if (request.customerIds() != null && !request.customerIds().isEmpty()) {
            Set<Long> seen = new HashSet<>();
            int seq = 1;
            for (Long customerId : request.customerIds()) {
                if (!seen.add(customerId)) {
                    throw BusinessException.badRequest(
                            "Duplicate customer in request: " + customerId, "DUPLICATE_CUSTOMER_ON_ROUTE");
                }
                requireAssignableCustomer(customerId, request.territoryId());
                route.addAssignment(new RouteCustomerAssignment(customerId, seq++));
            }
        }

        Route saved = routeRepository.save(route);
        log.info("Created route id={} representativeId={} territoryId={} date={} stops={}",
                saved.getId(), saved.getRepresentativeId(), saved.getTerritoryId(),
                saved.getRouteDate(), saved.getAssignments().size());
        return toResponse(saved);
    }

    /**
     * Edit the route header (name and/or date). Rep and territory are not editable.
     *
     * @throws BusinessException 404 if no such route; 409 if the route is COMPLETED, or the
     *                           new date collides with another route for the same rep
     */
    @Transactional
    public RouteResponse update(Long routeId, UpdateRouteRequest request) {
        Route route = findWithAssignmentsOrThrow(routeId);
        requireEditable(route);

        if (request.name() != null) {
            route.setName(request.name());
        }
        if (request.routeDate() != null && !request.routeDate().equals(route.getRouteDate())) {
            if (routeRepository.existsByRepresentativeIdAndRouteDate(
                    route.getRepresentativeId(), request.routeDate())) {
                throw BusinessException.conflict(
                        "Representative " + route.getRepresentativeId()
                                + " already has a route on " + request.routeDate(),
                        "ROUTE_ALREADY_EXISTS_FOR_DAY");
            }
            route.setRouteDate(request.routeDate());
        }
        return toResponse(routeRepository.save(route));
    }

    /**
     * Append customers to a route. Resets the optimisation flag.
     *
     * @throws BusinessException 404 if no such route or an unknown customer; 409 if the route
     *                           is COMPLETED or a customer is already on it; 400 on duplicates;
     *                           422 if a customer is inactive or outside the route's territory
     */
    @Transactional
    public RouteResponse assignCustomers(Long routeId, AssignCustomersRequest request) {
        Route route = findWithAssignmentsOrThrow(routeId);
        requireEditable(route);

        Set<Long> existing = new HashSet<>();
        for (RouteCustomerAssignment a : route.getAssignments()) {
            existing.add(a.getCustomerId());
        }

        Set<Long> seen = new HashSet<>();
        int nextSeq = route.getAssignments().stream()
                .mapToInt(RouteCustomerAssignment::getSequenceNumber).max().orElse(0) + 1;

        for (Long customerId : request.customerIds()) {
            if (!seen.add(customerId)) {
                throw BusinessException.badRequest(
                        "Duplicate customer in request: " + customerId, "DUPLICATE_CUSTOMER_ON_ROUTE");
            }
            if (existing.contains(customerId)) {
                throw BusinessException.conflict(
                        "Customer " + customerId + " is already on route " + routeId,
                        "CUSTOMER_ALREADY_ON_ROUTE");
            }
            requireAssignableCustomer(customerId, route.getTerritoryId());
            route.addAssignment(new RouteCustomerAssignment(customerId, nextSeq++));
        }

        route.setOptimized(false);
        return toResponse(routeRepository.save(route));
    }

    /**
     * Remove one customer from a route and re-compact the remaining sequence to 1..n.
     * Resets the optimisation flag.
     *
     * @throws BusinessException 404 if no such route or the customer is not on it;
     *                           409 if the route is COMPLETED
     */
    @Transactional
    public RouteResponse removeCustomer(Long routeId, Long customerId) {
        Route route = findWithAssignmentsOrThrow(routeId);
        requireEditable(route);

        RouteCustomerAssignment target = route.getAssignments().stream()
                .filter(a -> a.getCustomerId().equals(customerId))
                .findFirst()
                .orElseThrow(() -> BusinessException.notFound(
                        "Customer " + customerId + " is not on route " + routeId, "ASSIGNMENT_NOT_FOUND"));

        route.getAssignments().remove(target); // orphanRemoval deletes the row
        recompactSequence(route);
        route.setOptimized(false);
        return toResponse(routeRepository.save(route));
    }

    /**
     * Manually set the visit order. The request must list exactly the current stops.
     * Resets the optimisation flag (a hand order is not an auto-computed one).
     *
     * @throws BusinessException 404 if no such route; 409 if COMPLETED; 400 if the list has
     *                           duplicates or does not match the current stop set exactly
     */
    @Transactional
    public RouteResponse reorder(Long routeId, ReorderRouteRequest request) {
        Route route = findWithAssignmentsOrThrow(routeId);
        requireEditable(route);

        List<Long> requested = request.orderedCustomerIds();
        Set<Long> requestedSet = new HashSet<>(requested);
        if (requested.size() != requestedSet.size()) {
            throw BusinessException.badRequest("Duplicate customer in ordering", "DUPLICATE_CUSTOMER_ON_ROUTE");
        }

        Set<Long> current = new HashSet<>();
        for (RouteCustomerAssignment a : route.getAssignments()) {
            current.add(a.getCustomerId());
        }
        if (!requestedSet.equals(current)) {
            throw BusinessException.badRequest(
                    "Ordering must list exactly the customers currently on the route",
                    "ROUTE_ORDERING_MISMATCH");
        }

        Map<Long, Integer> seqByCustomer = new HashMap<>();
        for (int i = 0; i < requested.size(); i++) {
            seqByCustomer.put(requested.get(i), i + 1);
        }
        for (RouteCustomerAssignment a : route.getAssignments()) {
            a.setSequenceNumber(seqByCustomer.get(a.getCustomerId()));
        }
        route.setOptimized(false);
        return toResponse(routeRepository.save(route));
    }

    /**
     * Auto-optimise the visit order (FR-51/52) and set {@code isOptimized = true}.
     *
     * @throws BusinessException 404 if no such route; 409 if COMPLETED;
     *                           422 if the route is empty or any customer lacks coordinates
     */
    @Transactional
    public RouteResponse optimize(Long routeId) {
        Route route = findWithAssignmentsOrThrow(routeId);
        requireEditable(route);

        if (route.getAssignments().isEmpty()) {
            throw BusinessException.unprocessable(
                    "Route " + routeId + " has no customers to optimise", "ROUTE_EMPTY");
        }

        // Deterministic input order: by current sequence, so optimisation starts from stop #1.
        List<RouteCustomerAssignment> ordered = route.getAssignments().stream()
                .sorted(Comparator.comparingInt(RouteCustomerAssignment::getSequenceNumber))
                .toList();

        List<GeoPoint> points = new ArrayList<>(ordered.size());
        for (RouteCustomerAssignment a : ordered) {
            CustomerInfo c = customerFacade.getCustomerInfo(a.getCustomerId());
            if (c.latitude() == null || c.longitude() == null) {
                throw BusinessException.unprocessable(
                        "Customer " + a.getCustomerId() + " has no coordinates; cannot optimise route " + routeId,
                        "CUSTOMER_MISSING_COORDINATES");
            }
            points.add(new GeoPoint(a.getCustomerId(), c.latitude().doubleValue(), c.longitude().doubleValue()));
        }

        List<Long> optimisedOrder = optimizationService.optimizeOrder(points);
        Map<Long, Integer> seqByCustomer = new HashMap<>();
        for (int i = 0; i < optimisedOrder.size(); i++) {
            seqByCustomer.put(optimisedOrder.get(i), i + 1);
        }
        for (RouteCustomerAssignment a : route.getAssignments()) {
            a.setSequenceNumber(seqByCustomer.get(a.getCustomerId()));
        }
        route.setOptimized(true);

        Route saved = routeRepository.save(route);
        log.info("Optimised route id={} ({} stops)", routeId, saved.getAssignments().size());
        return toResponse(saved);
    }

    /**
     * Advance the route status. Only {@code PLANNED → ACTIVE} and {@code ACTIVE → COMPLETED}.
     *
     * @throws BusinessException 404 if no such route; 409 on an illegal transition
     */
    @Transactional
    public RouteResponse updateStatus(Long routeId, RouteStatus target) {
        Route route = findWithAssignmentsOrThrow(routeId);
        RouteStatus current = route.getStatus();

        boolean legal = (current == RouteStatus.PLANNED && target == RouteStatus.ACTIVE)
                || (current == RouteStatus.ACTIVE && target == RouteStatus.COMPLETED);
        if (!legal) {
            throw BusinessException.conflict(
                    "Illegal route status transition " + current + " -> " + target,
                    "ILLEGAL_ROUTE_STATUS_TRANSITION");
        }
        route.setStatus(target);
        return toResponse(routeRepository.save(route));
    }

    /**
     * Delete a route (cascades its stops). Only a {@code PLANNED} route may be deleted —
     * an {@code ACTIVE} route is live and a {@code COMPLETED} one is a historical record.
     *
     * @throws BusinessException 404 if no such route; 409 if it is not PLANNED
     */
    @Transactional
    public void delete(Long routeId) {
        Route route = findWithAssignmentsOrThrow(routeId);
        if (route.getStatus() != RouteStatus.PLANNED) {
            throw BusinessException.conflict(
                    "Only PLANNED routes can be deleted (status is " + route.getStatus() + ")",
                    "ROUTE_NOT_DELETABLE");
        }
        routeRepository.delete(route);
        log.info("Deleted route id={}", routeId);
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    /** @throws BusinessException 404 if no such route */
    public RouteResponse getById(Long routeId) {
        return toResponse(findWithAssignmentsOrThrow(routeId));
    }

    public PageResponse<RouteResponse> list(Long representativeId,
                                            RouteStatus status,
                                            LocalDate routeDate,
                                            Pageable pageable) {
        return PageResponse.of(
                routeRepository.search(representativeId, status, routeDate, pageable)
                        .map(this::toResponse));
    }

    /** A rep's own route for a given day. @throws BusinessException 404 if none that day */
    public RouteResponse getRouteForRepOnDate(Long representativeId, LocalDate date) {
        var routes = routeRepository
                .findAllWithAssignmentsByRepresentativeIdAndRouteDate(representativeId, date);
        if (routes.isEmpty()) {
            throw BusinessException.notFound(
                    "No route for representative " + representativeId + " on " + date,
                    "ROUTE_NOT_FOUND");
        }
        Route route = routes.get(0);  // ACTIVE if any, else PLANNED, else most recent COMPLETED

        if (route.getStatus() == RouteStatus.PLANNED) {
            route.setStatus(RouteStatus.ACTIVE);
            routeRepository.save(route);
            log.info("Route id={} auto-activated on first rep fetch", route.getId());
        }
        return toResponse(route);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Route findWithAssignmentsOrThrow(Long routeId) {
        return routeRepository.findWithAssignmentsById(routeId)
                .orElseThrow(() -> BusinessException.notFound(
                        "Route not found: " + routeId, "ROUTE_NOT_FOUND"));
    }

    /** Rewrites sequence numbers to 1..n preserving the current relative order. */
    private void recompactSequence(Route route) {
        List<RouteCustomerAssignment> ordered = route.getAssignments().stream()
                .sorted(Comparator.comparingInt(RouteCustomerAssignment::getSequenceNumber))
                .toList();
        int seq = 1;
        for (RouteCustomerAssignment a : ordered) {
            a.setSequenceNumber(seq++);
        }
    }

    private void requireSalesRep(Long userId) {
        // getRoleById throws 404 if the user is unknown.
        if (userFacade.getRoleById(userId) != UserRole.SALES_REP) {
            throw BusinessException.unprocessable(
                    "User " + userId + " is not a SALES_REP", "NOT_A_SALES_REP");
        }
    }

    private void requireTerritory(Long territoryId) {
        if (!territoryFacade.exists(territoryId)) {
            throw BusinessException.notFound(
                    "Territory not found: " + territoryId, "TERRITORY_NOT_FOUND");
        }
    }

    /** A customer is assignable iff it exists, is ACTIVE, and lives in the route's territory. */
    private void requireAssignableCustomer(Long customerId, Long routeTerritoryId) {
        CustomerInfo c = customerFacade.getCustomerInfo(customerId); // throws 404 if unknown
        if (!c.active()) {
            throw BusinessException.unprocessable(
                    "Customer " + customerId + " is not ACTIVE", "CUSTOMER_NOT_ACTIVE");
        }
        if (!routeTerritoryId.equals(c.territoryId())) {
            throw BusinessException.unprocessable(
                    "Customer " + customerId + " (territory " + c.territoryId()
                            + ") is not in the route's territory " + routeTerritoryId,
                    "CUSTOMER_NOT_IN_ROUTE_TERRITORY");
        }
    }

    private void requireEditable(Route route) {
        if (route.getStatus() == RouteStatus.COMPLETED) {
            throw BusinessException.conflict(
                    "Route " + route.getId() + " is COMPLETED and cannot be modified",
                    "ROUTE_NOT_EDITABLE");
        }
    }

    private RouteResponse toResponse(Route route) {
        Map<Long, CustomerInfo> customers = new HashMap<>();
        for (RouteCustomerAssignment a : route.getAssignments()) {
            customers.put(a.getCustomerId(), safeCustomer(a.getCustomerId()));
        }
        return RouteResponse.from(
                route,
                customers,
                safeUserName(route.getRepresentativeId()),
                safeTerritoryName(route.getTerritoryId()));
    }

    private CustomerInfo safeCustomer(Long customerId) {
        try {
            return customerFacade.getCustomerInfo(customerId);
        } catch (Exception ex) {
            return null;
        }
    }

    private String safeUserName(Long userId) {
        try {
            return userFacade.getNameById(userId);
        } catch (Exception ex) {
            return null;
        }
    }

    private String safeTerritoryName(Long territoryId) {
        try {
            return territoryFacade.getTerritoryInfo(territoryId).name();
        } catch (Exception ex) {
            return null;
        }
    }
}
