package com.salesmanagement.visit.api;

import com.salesmanagement.visit.internal.entity.Visit;
import com.salesmanagement.visit.internal.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
}
