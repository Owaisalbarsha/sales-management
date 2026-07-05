package com.salesmanagement.visit.internal.service;

import com.salesmanagement.customer.api.CustomerFacade;
import com.salesmanagement.identity.api.UserFacade;
import com.salesmanagement.routing.api.RouteAssignmentInfo;
import com.salesmanagement.routing.api.RouteExecutionStarted;
import com.salesmanagement.routing.api.RouteInfo;
import com.salesmanagement.routing.api.RouteVisitsFinalized;
import com.salesmanagement.routing.api.RoutingFacade;
import com.salesmanagement.shared.api.PageResponse;
import com.salesmanagement.shared.exception.BusinessException;
import com.salesmanagement.visit.internal.dto.CheckInRequest;
import com.salesmanagement.visit.internal.dto.CheckOutRequest;
import com.salesmanagement.visit.internal.dto.EndDayRequest;
import com.salesmanagement.visit.internal.dto.VisitResponse;
import com.salesmanagement.visit.internal.entity.Visit;
import com.salesmanagement.visit.internal.enums.VisitStatus;
import com.salesmanagement.visit.internal.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Business logic for field visits: check-in, check-out, end-of-day, and reads.
 *
 * <p><strong>Cross-module dependencies (all read-only facade calls, no reach into other
 * modules' tables):</strong> {@code RoutingFacade} (validate the route belongs to the rep and
 * the customer is a stop on it; read the stop list for completion), {@code CustomerFacade}
 * (validate/enrich the customer), {@code UserFacade} (enrich the rep name).</p>
 *
 * <p><strong>Route state is driven by events, not by mutating routing.</strong> On the first
 * check-in of a {@code PLANNED} route this publishes {@link RouteExecutionStarted}; when every
 * stop on a route is terminal it publishes {@link RouteVisitsFinalized}. {@code routing} listens
 * and updates its own aggregate. The "all stops terminal" decision is computed here because
 * {@code routing} cannot read visit data without creating a module dependency cycle.</p>
 *
 * <p><strong>Offline correctness:</strong> {@code checkInTime}/{@code checkOutTime} are supplied
 * by the device (the real moment of the action, which may have happened hours before the request
 * reaches the server on reconnect). The server stores them as given, validating only that they
 * are not in the future beyond {@link #CLOCK_SKEW}. Per SRS, GPS is range-checked only — there is
 * no distance or geofence gate.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VisitService {

    /** Tolerance for client clocks running ahead of the server. */
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    private static final BigDecimal LAT_MIN = new BigDecimal("-90");
    private static final BigDecimal LAT_MAX = new BigDecimal("90");
    private static final BigDecimal LNG_MIN = new BigDecimal("-180");
    private static final BigDecimal LNG_MAX = new BigDecimal("180");

    private final VisitRepository visitRepository;
    private final RoutingFacade routingFacade;
    private final CustomerFacade customerFacade;
    private final UserFacade userFacade;
    private final ApplicationEventPublisher events;

    // ── Commands ─────────────────────────────────────────────────────────────

    /**
     * Records the rep's arrival at a stop and opens an {@code IN_PROGRESS} visit.
     *
     * @param representativeId the checking-in rep (from the JWT)
     * @throws BusinessException 403 if the route is not the rep's;
     *                           404 if the route or customer does not exist;
     *                           409 if a visit already exists for this stop, or another visit on
     *                           the route is still open (force check-out);
     *                           422 if the customer is not a stop on the route, coordinates are
     *                           out of range, or the timestamp is in the future
     */
    @Transactional
    public VisitResponse checkIn(Long representativeId, CheckInRequest req) {
        RouteInfo route = resolveOwnedRoute(representativeId, req.routeId());
        requireCustomerExists(req.customerId());
        requireCustomerOnRoute(req.routeId(), req.customerId());   // SRS alt-flow A2
        requireNotFuture(req.checkInTime(), "checkInTime", "CHECK_IN_TIME_IN_FUTURE");
        String location = toLocation(req.latitude(), req.longitude());

        // Duplicate: one visit per stop (SRS alt-flow A3 + UNIQUE(route_id, customer_id)).
        visitRepository.findByRouteIdAndCustomerId(req.routeId(), req.customerId())
                .ifPresent(existing -> {
                    if (existing.getStatus() == VisitStatus.IN_PROGRESS) {
                        throw BusinessException.conflict(
                                "Visit already in progress for this stop", "VISIT_ALREADY_IN_PROGRESS");
                    }
                    throw BusinessException.conflict(
                            "A visit for this stop has already been recorded", "VISIT_ALREADY_RECORDED");
                });

        // Force check-out: no new check-in while any visit on the route is still open.
        if (visitRepository.countByRouteIdAndStatus(req.routeId(), VisitStatus.IN_PROGRESS) > 0) {
            throw BusinessException.conflict(
                    "Check out of your current visit before starting another", "OPEN_VISIT_ON_ROUTE");
        }

        Visit visit = visitRepository.save(Visit.checkIn(
                req.customerId(), representativeId, req.routeId(), req.checkInTime(), location));

        // First check-in on a PLANNED route starts it: routing flips PLANNED -> ACTIVE.
        if ("PLANNED".equals(route.status())) {
            events.publishEvent(new RouteExecutionStarted(
                    req.routeId(), representativeId, req.checkInTime()));
            log.info("Published RouteExecutionStarted routeId={} representativeId={}",
                    req.routeId(), representativeId);
        }

        log.info("Check-in visitId={} routeId={} customerId={} representativeId={}",
                visit.getId(), req.routeId(), req.customerId(), representativeId);
        return toResponse(visit);
    }

    /**
     * Records departure and closes the visit ({@code IN_PROGRESS -> COMPLETED}). If this closes
     * the last open/pending stop, publishes {@link RouteVisitsFinalized}.
     *
     * @param representativeId the checking-out rep (from the JWT)
     * @throws BusinessException 403 if the route or visit is not the rep's;
     *                           404 if the route does not exist, or no visit exists for this stop
     *                           (BR-10: no check-out without a matching check-in);
     *                           409 if the visit is not {@code IN_PROGRESS};
     *                           422 if coordinates are out of range, or the timestamp is in the
     *                           future or before check-in
     */
    @Transactional
    public VisitResponse checkOut(Long representativeId, CheckOutRequest req) {
        resolveOwnedRoute(representativeId, req.routeId());

        Visit visit = visitRepository.findByRouteIdAndCustomerId(req.routeId(), req.customerId())
                .orElseThrow(() -> BusinessException.notFound(
                        "No visit to check out of for this stop", "NO_CHECK_IN"));   // BR-10

        if (!visit.getRepresentativeId().equals(representativeId)) {
            throw BusinessException.forbidden(
                    "This visit belongs to another representative", "VISIT_NOT_OWNED");
        }
        if (visit.getStatus() != VisitStatus.IN_PROGRESS) {
            throw BusinessException.conflict(
                    "Visit is not in progress (status " + visit.getStatus() + ")",
                    "VISIT_NOT_IN_PROGRESS");
        }

        requireNotFuture(req.checkOutTime(), "checkOutTime", "CHECK_OUT_TIME_IN_FUTURE");
        if (visit.getCheckInTime() != null && req.checkOutTime().isBefore(visit.getCheckInTime())) {
            throw BusinessException.unprocessable(
                    "checkOutTime is before checkInTime", "CHECK_OUT_BEFORE_CHECK_IN");
        }
        String location = toLocation(req.latitude(), req.longitude());

        visit.completeCheckOut(req.checkOutTime(), location);
        visitRepository.save(visit);
        log.info("Check-out visitId={} routeId={} customerId={}",
                visit.getId(), req.routeId(), req.customerId());

        finaliseRouteIfAllTerminal(req.routeId());
        return toResponse(visit);
    }

    /**
     * The rep declares the route done for the day. Every stop with no visit becomes
     * {@code MISSED}, then the route is finalised.
     *
     * @param representativeId the rep (from the JWT)
     * @throws BusinessException 403 if the route is not the rep's;
     *                           404 if the route does not exist;
     *                           409 if any visit on the route is still {@code IN_PROGRESS}
     */
    @Transactional
    public void endDay(Long representativeId, EndDayRequest req) {
        RouteInfo route = resolveOwnedRoute(representativeId, req.routeId());

        if (visitRepository.countByRouteIdAndStatus(req.routeId(), VisitStatus.IN_PROGRESS) > 0) {
            throw BusinessException.conflict(
                    "Check out of your open visit before ending the day", "OPEN_VISIT_ON_ROUTE");
        }

        Set<Long> visited = visitRepository.findByRouteId(req.routeId()).stream()
                .map(Visit::getCustomerId)
                .collect(Collectors.toSet());

        int missed = 0;
        for (RouteAssignmentInfo stop : route.stops()) {
            if (!visited.contains(stop.customerId())) {
                visitRepository.save(Visit.missed(stop.customerId(), representativeId, req.routeId()));
                missed++;
            }
        }
        log.info("End-day routeId={} representativeId={} missedCreated={}",
                req.routeId(), representativeId, missed);

        finaliseRouteIfAllTerminal(req.routeId());
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    /**
     * One visit. A {@code SALES_REP} may read only their own; {@code ADMIN}/{@code SALES_MANAGER}
     * may read any.
     *
     * @throws BusinessException 404 if no such visit; 403 if a non-privileged caller asks for a
     *                           visit that is not theirs
     */
    public VisitResponse getById(Long requesterId, boolean privileged, Long visitId) {
        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> BusinessException.notFound(
                        "Visit not found: " + visitId, "VISIT_NOT_FOUND"));
        if (!privileged && !visit.getRepresentativeId().equals(requesterId)) {
            throw BusinessException.forbidden("Not your visit", "VISIT_NOT_OWNED");
        }
        return toResponse(visit);
    }

    /**
     * Page of visits with optional filters. A {@code SALES_REP} is forced to their own visits
     * regardless of the {@code representativeId} filter; privileged roles see any.
     */
    public PageResponse<VisitResponse> list(Long requesterId,
                                            boolean privileged,
                                            Long representativeId,
                                            Long routeId,
                                            Long customerId,
                                            VisitStatus status,
                                            Pageable pageable) {
        Long effectiveRep = privileged ? representativeId : requesterId;
        Page<Visit> page = visitRepository.search(effectiveRep, routeId, customerId, status, pageable);

        Set<Long> customerIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        for (Visit v : page.getContent()) {
            customerIds.add(v.getCustomerId());
            userIds.add(v.getRepresentativeId());
        }
        Map<Long, String> customerNames = resolveCustomerNames(customerIds);
        Map<Long, String> userNames = resolveUserNames(userIds);

        return PageResponse.of(page.map(v -> VisitResponse.from(
                v, customerNames.get(v.getCustomerId()), userNames.get(v.getRepresentativeId()))));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Publishes {@link RouteVisitsFinalized} when every stop on the route is terminal. Computed
     * here (not in routing) to keep the module dependency one-directional.
     */
    private void finaliseRouteIfAllTerminal(Long routeId) {
        RouteInfo route = routingFacade.getRouteInfo(routeId);
        Set<Long> stopCustomerIds = route.stops().stream()
                .map(RouteAssignmentInfo::customerId)
                .collect(Collectors.toSet());
        if (stopCustomerIds.isEmpty()) {
            return; // a route with no stops is never auto-finalised
        }
        Set<Long> terminalCustomerIds = visitRepository.findByRouteId(routeId).stream()
                .filter(Visit::isTerminal)
                .map(Visit::getCustomerId)
                .collect(Collectors.toSet());

        if (terminalCustomerIds.containsAll(stopCustomerIds)) {
            events.publishEvent(new RouteVisitsFinalized(routeId, Instant.now()));
            log.info("Published RouteVisitsFinalized routeId={} ({} stops all terminal)",
                    routeId, stopCustomerIds.size());
        }
    }

    /** Loads the route and asserts it belongs to the rep. */
    private RouteInfo resolveOwnedRoute(Long representativeId, Long routeId) {
        RouteInfo route = routingFacade.getRouteInfo(routeId); // 404 if missing
        if (!route.representativeId().equals(representativeId)) {
            throw BusinessException.forbidden(
                    "Route " + routeId + " does not belong to you", "ROUTE_NOT_OWNED");
        }
        return route;
    }

    private void requireCustomerExists(Long customerId) {
        if (!customerFacade.exists(customerId)) {
            throw BusinessException.notFound(
                    "Customer not found: " + customerId, "CUSTOMER_NOT_FOUND");
        }
    }

    private void requireCustomerOnRoute(Long routeId, Long customerId) {
        if (!routingFacade.isCustomerOnRoute(routeId, customerId)) {
            throw BusinessException.unprocessable(
                    "Customer " + customerId + " is not a stop on route " + routeId,
                    "CUSTOMER_NOT_ON_ROUTE");
        }
    }

    private void requireNotFuture(Instant t, String field, String code) {
        if (t.isAfter(Instant.now().plus(CLOCK_SKEW))) {
            throw BusinessException.unprocessable(field + " is in the future", code);
        }
    }

    /** Range-only GPS validation (no distance/geofence gate, per SRS); returns {@code "lat,lng"}. */
    private String toLocation(BigDecimal latitude, BigDecimal longitude) {
        if (latitude.compareTo(LAT_MIN) < 0 || latitude.compareTo(LAT_MAX) > 0) {
            throw BusinessException.unprocessable(
                    "latitude out of range [-90,90]: " + latitude, "INVALID_LATITUDE");
        }
        if (longitude.compareTo(LNG_MIN) < 0 || longitude.compareTo(LNG_MAX) > 0) {
            throw BusinessException.unprocessable(
                    "longitude out of range [-180,180]: " + longitude, "INVALID_LONGITUDE");
        }
        return latitude.toPlainString() + "," + longitude.toPlainString();
    }

    private VisitResponse toResponse(Visit v) {
        return VisitResponse.from(v,
                safeCustomerName(v.getCustomerId()),
                safeUserName(v.getRepresentativeId()));
    }

    private Map<Long, String> resolveCustomerNames(Set<Long> ids) {
        Map<Long, String> out = new HashMap<>();
        for (Long id : ids) out.put(id, safeCustomerName(id));
        return out;
    }

    private Map<Long, String> resolveUserNames(Set<Long> ids) {
        Map<Long, String> out = new HashMap<>();
        for (Long id : ids) out.put(id, safeUserName(id));
        return out;
    }

    /** Customer name, or {@code null} if the lookup fails. */
    private String safeCustomerName(Long customerId) {
        try {
            return customerFacade.getCustomerInfo(customerId).name();
        } catch (Exception ex) {
            return null;
        }
    }

    /** User name, or {@code null} if the lookup fails. */
    private String safeUserName(Long userId) {
        try {
            return userFacade.getNameById(userId);
        } catch (Exception ex) {
            return null;
        }
    }
}
