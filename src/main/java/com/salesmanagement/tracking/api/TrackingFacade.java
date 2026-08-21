package com.salesmanagement.tracking.api;

import com.salesmanagement.tracking.internal.service.TrackingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Public API surface of the {@code tracking} module — the only type other modules may import from
 * tracking. Delegates to {@code TrackingService}; business logic never lives here.
 *
 * <p><strong>Unlike the other facades in this system, this one writes.</strong> {@code UserFacade},
 * {@code RoutingFacade} and {@code VisitFacade} are read-only, and that is the right default. The
 * exception is deliberate and has a specific cause: when {@code sync} processes a queue item of
 * type {@code GPS_LOG} it must set that item's {@code SyncStatus} to {@code SYNCED} or
 * {@code FAILED} and increment {@code RetryCount} accordingly. That requires a synchronous
 * pass/fail answer from whoever handled the payload.</p>
 *
 * <p>The project architecture document specifies that path as an event
 * ({@code SyncItemReceivedEvent}, consumed by a listener here). That is wrong, and it is worth
 * understanding why rather than just following it: an {@code @ApplicationModuleListener} is
 * asynchronous and runs in its own transaction, so the publisher has already committed and
 * returned before the listener succeeds or fails. {@code sync} would have no way to learn the
 * outcome and would have to mark every item {@code SYNCED} on dispatch, making its retry machinery
 * decorative. A direct facade call keeps the outcome where the decision is made.</p>
 *
 * <p>Events remain the right tool where the publisher genuinely does not care about the result,
 * which is why {@code RouteExecutionStarted} and {@code RouteVisitsFinalized} are events. This is
 * not one of those cases.</p>
 */
@Service
@RequiredArgsConstructor
public class TrackingFacade {

    private final TrackingService trackingService;

    /**
     * Persists a batch of GPS fixes for one rep, deduplicating against what is already stored.
     *
     * <p>Idempotent on {@code (representativeId, recordedAt)}: resending a batch that has already
     * landed returns {@code accepted = 0} with every point counted as a duplicate, and that is
     * success. Callers must treat it as such (see {@link IngestResult}); a caller that reads
     * {@code accepted = 0} as failure will retry forever.</p>
     *
     * <p>Genuine problems throw {@code BusinessException} and persist nothing: unknown or inactive
     * rep, empty or oversized batch, missing or out-of-range coordinates, a capture instant too far
     * in the future, or a concurrent insert of the same instant. The whole batch is one
     * transaction, so there is no partial state to reconcile.</p>
     *
     * @param representativeId the rep the points belong to, established by the caller from its own
     *                         trusted source (the JWT principal, or the sync queue item's owner)
     * @param points           the batch, at most 500 points
     * @return counts of newly persisted and skipped-as-duplicate points
     */
    public IngestResult ingest(Long representativeId, List<GpsPointInput> points) {
        return trackingService.ingest(representativeId, points);
    }

    /**
     * The rep's most recent known position, or empty if they have never reported one.
     *
     * <p>Absence is a normal state, not an error, so this returns {@link Optional} rather than
     * throwing — the same contract as {@code RoutingFacade.getRouteForToday}.</p>
     *
     * <p>Deliberately returns the raw {@code recordedAt} with no freshness flag. What counts as
     * "still active" is a display policy (15 minutes on the live map), and a reporting query
     * measuring route adherence may reasonably apply a different one.</p>
     */
    public Optional<GpsPointInfo> getLatestLocation(Long representativeId) {
        return trackingService.getLatestLocation(representativeId);
    }
}
