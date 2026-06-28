package com.salesmanagement.routing.internal.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Route optimisation (FR-51 / FR-52): reorders a route's stops to shorten travel.
 *
 * <p>Algorithm: greedy nearest-neighbour — start at the first stop, repeatedly hop to the
 * closest not-yet-visited stop by great-circle (Haversine) distance. This is the standard
 * simple TSP heuristic. It is <em>not</em> optimal (optimal TSP is NP-hard), but it is fast,
 * deterministic, and good enough for a daily field route of a few dozen customers. Swapping
 * in 2-opt or an external routing API later is a drop-in replacement behind this method.</p>
 *
 * <p>Pure function over coordinates: no DB, no entities. Callers resolve coordinates first
 * (rejecting any stop that has none) and apply the returned order back onto the assignments.</p>
 */
@Service
public class RouteOptimizationService {

    /** A customer's geo-position, used only as optimisation input. */
    public record GeoPoint(Long customerId, double latitude, double longitude) {}

    /**
     * Returns the customer ids in optimised visit order. Starts from the first point in the
     * input list, so callers control the start by ordering the input (e.g. by current sequence).
     *
     * @param points stops with coordinates; for 0–2 points the input order is returned unchanged
     * @return customer ids in visit order
     */
    public List<Long> optimizeOrder(List<GeoPoint> points) {
        if (points.size() <= 2) {
            return points.stream().map(GeoPoint::customerId).toList();
        }

        List<GeoPoint> remaining = new ArrayList<>(points);
        List<Long> order = new ArrayList<>(points.size());

        GeoPoint current = remaining.remove(0);
        order.add(current.customerId());

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

    /** Great-circle distance in kilometres between two points. */
    private static double haversineKm(GeoPoint a, GeoPoint b) {
        final double earthRadiusKm = 6371.0088;
        double dLat = Math.toRadians(b.latitude() - a.latitude());
        double dLon = Math.toRadians(b.longitude() - a.longitude());
        double lat1 = Math.toRadians(a.latitude());
        double lat2 = Math.toRadians(b.latitude());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.sin(dLon / 2) * Math.sin(dLon / 2) * Math.cos(lat1) * Math.cos(lat2);
        return 2 * earthRadiusKm * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }
}
