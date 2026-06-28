package com.salesmanagement.routing.internal.dto;

import com.salesmanagement.customer.api.CustomerInfo;
import com.salesmanagement.routing.internal.entity.Route;
import com.salesmanagement.routing.internal.entity.RouteCustomerAssignment;
import com.salesmanagement.routing.internal.enums.RouteStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Response projection of a {@link Route}, returned by every route endpoint.
 *
 * <p>Stops are returned already sorted by {@code sequenceNumber} and enriched with the
 * customer's {@code name}, {@code address}, and coordinates (resolved from
 * {@code CustomerFacade}) so the client renders the route without a second call per stop.
 * The header is enriched with the rep's name and the territory's name. Any enrichment that
 * fails (deleted customer/user) yields a null on that field rather than failing the read.</p>
 */
public record RouteResponse(
        Long          id,
        Long          representativeId,
        String        representativeName,
        Long          territoryId,
        String        territoryName,
        String        name,
        LocalDate     routeDate,
        RouteStatus   status,
        boolean       isOptimized,
        List<Stop>    stops,
        Instant       createdAt,
        Instant       updatedAt
) {
    /**
     * One stop, enriched with customer display info.
     *
     * @param assignmentId    surrogate id of the stop (the {@code routeCustomerAssignmentId}
     *                        the visit module references)
     * @param customerId      cross-module customer id
     * @param customerName    enriched customer name
     * @param customerAddress enriched customer address
     * @param latitude        enriched customer latitude (may be null)
     * @param longitude       enriched customer longitude (may be null)
     * @param sequenceNumber  visit order on the route
     */
    public record Stop(
            Long       assignmentId,
            Long       customerId,
            String     customerName,
            String     customerAddress,
            BigDecimal latitude,
            BigDecimal longitude,
            int        sequenceNumber
    ) {}

    /**
     * Maps a {@link Route} entity to its response projection.
     *
     * @param route              the route to map; must not be null
     * @param customerInfos      customer-id → {@link CustomerInfo} for every stop's customer
     * @param representativeName resolved rep name (may be null if unknown)
     * @param territoryName      resolved territory name (may be null if unknown)
     */
    public static RouteResponse from(Route route,
                                     Map<Long, CustomerInfo> customerInfos,
                                     String representativeName,
                                     String territoryName) {
        List<Stop> stops = route.getAssignments().stream()
                .sorted(Comparator.comparingInt(RouteCustomerAssignment::getSequenceNumber))
                .map(a -> toStop(a, customerInfos))
                .toList();

        return new RouteResponse(
                route.getId(),
                route.getRepresentativeId(),
                representativeName,
                route.getTerritoryId(),
                territoryName,
                route.getName(),
                route.getRouteDate(),
                route.getStatus(),
                route.isOptimized(),
                stops,
                route.getCreatedAt(),
                route.getUpdatedAt()
        );
    }

    private static Stop toStop(RouteCustomerAssignment a, Map<Long, CustomerInfo> customerInfos) {
        CustomerInfo c = customerInfos.get(a.getCustomerId());
        return new Stop(
                a.getId(),
                a.getCustomerId(),
                c != null ? c.name()      : null,
                c != null ? c.address()   : null,
                c != null ? c.latitude()  : null,
                c != null ? c.longitude() : null,
                a.getSequenceNumber()
        );
    }
}
