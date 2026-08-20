package com.salesmanagement.routing.internal.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Route optimisation (FR-51 / FR-52): reorders a route's stops to shorten travel.
 *
 * <p>Algorithm: greedy nearest-neighbour on Haversine (great-circle) distance, anchored
 * to a fixed <strong>company depot</strong> read from {@code company.latitude} /
 * {@code company.longitude} in configuration. The algorithm starts at the depot, picks
 * the closest unvisited customer, then from that customer picks the next closest, and so
 * on. The depot itself is not returned in the output — only the customer visit order.</p>
 *
 * <p>This depot-anchored model reflects reality: reps start the day at the company
 * warehouse/office (load stock, get paperwork) rather than at whichever customer happens
 * to be "stop #1". Prior versions used stop #1 as the anchor, which produced bad
 * front-end routing when stop #1 was geographically far from the depot.</p>
 *
 * <p>Nearest-neighbour is a heuristic, not optimal (optimal TSP is NP-hard). It can
 * produce tours a few percent longer than the true optimum on adversarial inputs. Good
 * enough for a daily field route of a few dozen customers; 2-opt or an external routing
 * API is a drop-in replacement behind {@link #optimizeOrder(List)}.</p>
 */
@Slf4j
@Service
public class RouteOptimizationService {

    /** A geo-position, either a customer or the depot, used as optimisation input. */
    public record GeoPoint(Long customerId, double latitude, double longitude) {}

    private final double depotLatitude;
    private final double depotLongitude;

    public RouteOptimizationService(
            @Value("${company.latitude}")  double depotLatitude,
            @Value("${company.longitude}") double depotLongitude) {
        this.depotLatitude  = depotLatitude;
        this.depotLongitude = depotLongitude;
        log.info("Route optimiser anchored at depot ({}, {})", depotLatitude, depotLongitude);
    }

    /**
     * Returns the customer ids in optimised visit order, starting from the company depot.
     *
     * <p>For 0 or 1 stops the input order is returned unchanged (nothing to optimise).
     * The depot is used only as the starting anchor for the nearest-neighbour walk; it
     * does not appear in the returned list.</p>
     *
     * @param points customer stops with coordinates
     * @return customer ids in visit order
     */
    public List<Long> optimizeOrder(List<GeoPoint> points) {
        if (points.size() <= 1) {
            return points.stream().map(GeoPoint::customerId).toList();
        }

        // Virtual anchor: the depot. customerId=null so it is never returned in the output.
        GeoPoint depot = new GeoPoint(null, depotLatitude, depotLongitude);

        List<GeoPoint> remaining = new ArrayList<>(points);
        List<Long> order = new ArrayList<>(points.size());

        GeoPoint current = depot;               // start from the depot, not from a customer
        while (!remaining.isEmpty()) {
            GeoPoint nearest = null;
            double best = Double.MAX_VALUE;
            for (GeoPoint candidate : remaining) {
                double d = haversineKm(current, candidate);
                if (d < best) {
                    best = d;
                    nearest = candidate;
                }
            }
            remaining.remove(nearest);
            order.add(nearest.customerId());
            current = nearest;
        }
        return order;
    }

    /** Great-circle distance in kilometres between two points on the Earth's surface. */
    private static double haversineKm(GeoPoint a, GeoPoint b) {
        final double earthRadiusKm = 6371.0088;
        double dLat = Math.toRadians(b.latitude()  - a.latitude());
        double dLon = Math.toRadians(b.longitude() - a.longitude());
        double lat1 = Math.toRadians(a.latitude());
        double lat2 = Math.toRadians(b.latitude());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.sin(dLon / 2) * Math.sin(dLon / 2) * Math.cos(lat1) * Math.cos(lat2);
        return 2 * earthRadiusKm * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }
}