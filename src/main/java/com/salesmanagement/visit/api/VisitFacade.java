package com.salesmanagement.visit.api;

import com.salesmanagement.visit.internal.entity.Visit;
import com.salesmanagement.visit.internal.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * The {@code visit} module's public API. Other modules depend on this, never on the internal
 * entity, repository, or service.
 *
 * <p>Kept intentionally small. The originally-planned {@code openStops} method was dropped:
 * it existed so {@code routing} could evaluate route completion, but that decision now lives in
 * {@code visit} (published as {@code RouteVisitsFinalized}) to avoid a visit &lt;-&gt; routing
 * dependency cycle. The surface here is what {@code invoicing} needs to bind an invoice to a
 * visit.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VisitFacade {

    private final VisitRepository visitRepository;

    /** Whether a visit with this id exists. */
    public boolean existsById(Long visitId) {
        return visitRepository.existsById(visitId);
    }

    /**
     * The visit's public read model.
     *
     * @throws IllegalArgumentException if no visit has this id (callers that expect existence
     *         should check {@link #existsById} first, or handle this by translating to their own
     *         domain error)
     */
    public VisitInfo getVisitInfo(Long visitId) {
        Visit v = visitRepository.findById(visitId)
                .orElseThrow(() -> new IllegalArgumentException("Visit not found: " + visitId));
        return new VisitInfo(
                v.getId(),
                v.getRouteId(),
                v.getCustomerId(),
                v.getRepresentativeId(),
                v.getStatus().name(),
                v.getCheckInTime(),
                v.getCheckOutTime());
    }

    /**
     * All visits belonging to the given routes, in one query — the batch read behind the route
     * performance reports (FR-125 planned-vs-actual, FR-126 completion rate, FR-127 missed visits).
     *
     * <p><strong>Why by route id, not by date (D1 + a correctness trap).</strong> Reporting first
     * asks {@code routing} for the routes in a date window, then calls this with their ids. Filtering
     * visits by their own timestamp would be wrong: a MISSED visit was never checked in, so its
     * {@code checkInTime} is {@code null} and it would silently drop out of the result — which is
     * exactly the visit FR-126 needs to count. Anchoring on the route keeps missed visits in, and
     * one {@code IN} query replaces a per-route loop.</p>
     *
     * <p>An empty or {@code null}-containing id set returns an empty list rather than failing, so a
     * window with no routes is a clean empty report, not an error.</p>
     *
     * @param routeIds the routes whose visits to load (typically every route in a date window)
     * @return every visit on those routes, in no guaranteed order; empty if {@code routeIds} is empty
     */
    public List<VisitInfo> findVisitsByRouteIds(Collection<Long> routeIds) {
        if (routeIds == null || routeIds.isEmpty()) {
            return List.of();
        }
        return visitRepository.findByRouteIdIn(routeIds).stream()
                .map(v -> new VisitInfo(
                        v.getId(),
                        v.getRouteId(),
                        v.getCustomerId(),
                        v.getRepresentativeId(),
                        v.getStatus().name(),
                        v.getCheckInTime(),
                        v.getCheckOutTime()))
                .toList();
    }
}
