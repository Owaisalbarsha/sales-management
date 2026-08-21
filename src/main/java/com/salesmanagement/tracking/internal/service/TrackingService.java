package com.salesmanagement.tracking.internal.service;

import com.salesmanagement.identity.api.UserFacade;
import com.salesmanagement.shared.exception.BusinessException;
import com.salesmanagement.tracking.api.GpsPointInfo;
import com.salesmanagement.tracking.api.GpsPointInput;
import com.salesmanagement.tracking.api.IngestResult;
import com.salesmanagement.systemconfig.api.ConfigFacade;
import com.salesmanagement.systemconfig.api.ConfigKey;
import com.salesmanagement.tracking.internal.TrackingPolicy;
import com.salesmanagement.tracking.internal.dto.GpsPointResponse;
import com.salesmanagement.tracking.internal.dto.RecordGpsRequest;
import com.salesmanagement.tracking.internal.dto.RepLatestLocationResponse;
import com.salesmanagement.tracking.internal.entity.GpsLog;
import com.salesmanagement.tracking.internal.event.GpsPointsIngested;
import com.salesmanagement.tracking.internal.repository.GpsLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Business logic for GPS tracking.
 *
 * <p><strong>Ingest.</strong> One batch, one transaction. Validates the rep and every point,
 * normalises capture instants, deduplicates within the batch and against what is already stored,
 * inserts only what is new, and publishes the freshest accepted fix for the live map. Duplicates
 * are a normal outcome, never an error (FR-60).</p>
 *
 * <p><strong>Reads.</strong> {@code getLatestLocations} backs the live map's initial paint
 * (FR-61); {@code getTrail} backs the day-trail polyline. Both are bounded by construction.</p>
 *
 * <p><strong>Two entrances, one implementation.</strong> {@code ingest} is reached today from
 * {@code TrackingController} and tomorrow from {@code sync} through {@code TrackingFacade}. The
 * REST entrance has bean validation in front of it; the facade entrance does not. Every guard in
 * this class therefore repeats checks the DTO already makes, deliberately: a validation that lives
 * only in a DTO is a validation the second caller does not get.</p>
 *
 * <p><strong>Cross-module dependencies:</strong> {@code UserFacade} only. Tracking reaches into no
 * other module's tables and publishes no cross-module events.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TrackingService {

    private final GpsLogRepository gpsLogRepository;
    private final UserFacade userFacade;
    private final ApplicationEventPublisher events;
    private final ConfigFacade configFacade;

    // ═══════════════════════════════════════════════════════════════════════
    //  INGEST
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Persists a batch of GPS fixes for one rep.
     *
     * <p>The order of operations matters and is not arbitrary:</p>
     * <ol>
     *   <li><strong>Validate the rep and the batch envelope.</strong> Cheap checks first, so a
     *       malformed request never reaches the database.</li>
     *   <li><strong>Normalise and deduplicate within the batch</strong> into a
     *       {@code LinkedHashMap} keyed by millisecond-truncated instant. A device that fires twice
     *       inside the same millisecond contributes one point, last value winning, and insertion
     *       order is preserved so the resulting inserts read chronologically in the log.</li>
     *   <li><strong>Ask which of those instants already exist.</strong> One query. This is the
     *       guard that keeps a resent backlog from failing: see below.</li>
     *   <li><strong>Insert only the new ones, and flush.</strong></li>
     *   <li><strong>Publish the freshest accepted fix</strong> for the after-commit SSE
     *       broadcast.</li>
     * </ol>
     *
     * <p><strong>Why step 3 exists.</strong> A rep coming back online resends the tail of their
     * local queue, so overlap with stored data is the normal case, not an anomaly. Handing the
     * whole batch to {@code saveAll} would trip {@code uq_gps_logs_rep_recorded_at} on the first
     * already-known point and roll back all 480. The device would retry, collide identically, and
     * never drain its queue. Pre-filtering turns the expected case into an ordinary outcome.</p>
     *
     * <p><strong>Why the catch is still there.</strong> The pre-filter cannot see a concurrent
     * transaction inserting the same instant between the query and the flush. That race is close to
     * impossible here (one device per rep, enforced by single-session-per-user) but it is not
     * structurally excluded, so it gets a 409 telling the client to retry rather than a 500. Note
     * {@code saveAllAndFlush}, not {@code saveAll}: without the flush the violation would surface at
     * commit, outside this try block, and reach the user as an unhandled 500.</p>
     *
     * @param representativeId the rep the points belong to; from the JWT principal on the REST
     *                         path, from the queue item's owner on the sync path, never from a
     *                         request body
     * @param points           the batch, already range-checked on the REST path
     * @return counts of newly persisted and skipped-as-duplicate points
     * @throws BusinessException 404 unknown rep; 422 inactive rep; 400 empty batch, oversized
     *                           batch, missing or out-of-range values, or a capture instant too far
     *                           in the future; 409 concurrent insert of the same instant
     */
    @Transactional
    public IngestResult ingest(Long representativeId, List<GpsPointInput> points) {
        requireActiveRepresentative(representativeId);
        requireValidBatchSize(points);

        Instant skewCeiling = Instant.now().plus(TrackingPolicy.MAX_CLOCK_SKEW);

        Map<Instant, GpsPointInput> byInstant = new LinkedHashMap<>();
        for (GpsPointInput point : points) {
            validatePoint(point, skewCeiling);
            byInstant.put(GpsLog.normalizeInstant(point.recordedAt()), point);
        }

        Set<Instant> alreadyStored = new HashSet<>(
                gpsLogRepository.findExistingRecordedAt(representativeId, byInstant.keySet()));

        List<GpsLog> toInsert = byInstant.entrySet().stream()
                .filter(entry -> !alreadyStored.contains(entry.getKey()))
                .map(entry -> new GpsLog(
                        representativeId,
                        entry.getValue().latitude(),
                        entry.getValue().longitude(),
                        entry.getKey()))
                .toList();

        int submitted  = byInstant.size();
        int duplicates = submitted - toInsert.size();

        if (toInsert.isEmpty()) {
            log.debug("Ingest for rep {}: all {} points already stored", representativeId, submitted);
            return new IngestResult(0, duplicates);
        }

        try {
            gpsLogRepository.saveAllAndFlush(toInsert);
        } catch (DataIntegrityViolationException e) {
            // Only reachable if a concurrent transaction inserted one of these instants after the
            // pre-filter ran. The transaction is already poisoned, so it cannot be salvaged here;
            // a 409 tells the client to resend, and the resend will pre-filter cleanly.
            log.warn("Concurrent GPS insert collision for rep {}: {}", representativeId, e.getMessage());
            throw BusinessException.conflict(
                    "Concurrent GPS ingest for this representative; retry the batch",
                    "GPS_INGEST_CONFLICT");
        }

        publishFreshest(representativeId, toInsert);

        log.info("Ingested GPS batch for rep {}: accepted={} duplicatesSkipped={}",
                representativeId, toInsert.size(), duplicates);
        return new IngestResult(toInsert.size(), duplicates);
    }

    /** Convenience entrance for the REST controller, which speaks the DTO rather than the api record. */
    @Transactional
    public IngestResult ingest(Long representativeId, RecordGpsRequest request) {
        List<GpsPointInput> inputs = request.points().stream()
                .map(p -> new GpsPointInput(p.latitude(), p.longitude(), p.recordedAt()))
                .toList();
        return ingest(representativeId, inputs);
    }

    /**
     * Announces only the newest accepted fix, not every point in the batch.
     *
     * <p>The live map answers "where is this rep now". Replaying a 480-point backlog through SSE
     * would walk a marker across the city over several seconds and finish exactly where a single
     * event would have put it at once, while flooding every connected dashboard. History is the
     * trail endpoint's job.</p>
     */
    private void publishFreshest(Long representativeId, List<GpsLog> inserted) {
        inserted.stream()
                .max(Comparator.comparing(GpsLog::getRecordedAt))
                .ifPresent(newest -> events.publishEvent(new GpsPointsIngested(
                        representativeId,
                        newest.getLatitude(),
                        newest.getLongitude(),
                        newest.getRecordedAt())));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  READS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * The most recent known position of every rep who has ever reported one (FR-61).
     *
     * <p>Returns stale reps too, flagged {@code active = false} rather than omitted. Filtering them
     * out server-side would erase the answer to the question a manager actually asks when a rep
     * goes quiet, which is not "where is he" but "where was he when we lost him".</p>
     *
     * <p>Names are resolved one lookup per row. With 50 reps that is 50 cheap reads on a page the
     * dashboard requests once at load; if the fleet ever grows enough for that to matter, the fix is
     * a batch lookup on {@code UserFacade}, not a cache here.</p>
     */
    public List<RepLatestLocationResponse> getLatestLocations() {
        int windowMinutes = configFacade.getInt(
                ConfigKey.TRACKING_ACTIVE_WINDOW_MINUTES,
                (int) TrackingPolicy.ACTIVE_WINDOW.toMinutes());   // 15 = fallback default
        Instant freshnessFloor = Instant.now().minus(Duration.ofMinutes(windowMinutes));

        return gpsLogRepository.findLatestPerRepresentative().stream()
                .map(row -> new RepLatestLocationResponse(
                        row.getRepresentativeId(),
                        safeUserName(row.getRepresentativeId()),
                        row.getLatitude(),
                        row.getLongitude(),
                        row.getRecordedAt(),
                        row.getRecordedAt().isAfter(freshnessFloor)))
                .toList();
    }

    /**
     * One rep's fixes for one calendar day, oldest first — the trail polyline.
     *
     * <p>The date is mandatory and covers exactly one day. This is the only query in the module
     * whose result size is driven by user input, and an open-ended range parameter is an invitation
     * to request five years of points and stall the server. One day at the default capture interval
     * is roughly 480 rows, which is why the response is unpaged: a polyline needs every vertex in
     * order, and paging a map trail means nothing to the consumer.</p>
     *
     * <p><strong>Known simplification:</strong> the day boundary is UTC. For a fleet at UTC+03:00 a
     * request for "22 July" therefore covers 03:00 on the 22nd through 03:00 on the 23rd local time.
     * Ordinary working hours sit comfortably inside that window, so this is invisible in practice,
     * but late-evening work lands on the following day's trail. If that ever matters, the fix is an
     * optional {@code offset} query parameter defaulting to UTC, not a change to storage: instants
     * stay UTC throughout the system.</p>
     *
     * @param representativeId the rep whose trail is requested
     * @param date             the calendar day, required
     * @throws BusinessException 400 missing date; 404 unknown rep
     */
    public List<GpsPointResponse> getTrail(Long representativeId, LocalDate date) {
        if (date == null) {
            throw BusinessException.badRequest("date is required", "GPS_TRAIL_DATE_REQUIRED");
        }
        requireRepresentativeExists(representativeId);

        Instant from = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to   = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        return gpsLogRepository.findTrail(representativeId, from, to).stream()
                .map(GpsPointResponse::from)
                .toList();
    }

    /**
     * One rep's most recent fix, for other modules ({@code reporting}) via {@code TrackingFacade}.
     *
     * <p>Returns {@link Optional} rather than throwing: a rep who has never reported a position is
     * an ordinary state, not an error, and callers should not have to catch an exception to
     * discover it.</p>
     */
    public Optional<GpsPointInfo> getLatestLocation(Long representativeId) {
        return gpsLogRepository.findFirstByRepresentativeIdOrderByRecordedAtDesc(representativeId)
                .map(log -> new GpsPointInfo(
                        log.getRepresentativeId(),
                        log.getLatitude(),
                        log.getLongitude(),
                        log.getRecordedAt()));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Guards
    // ═══════════════════════════════════════════════════════════════════════

    private void requireValidBatchSize(List<GpsPointInput> points) {
        if (points == null || points.isEmpty()) {
            throw BusinessException.badRequest(
                    "At least one GPS point is required", "GPS_BATCH_EMPTY");
        }
        if (points.size() > RecordGpsRequest.MAX_BATCH_SIZE) {
            throw BusinessException.badRequest(
                    "A batch may not exceed " + RecordGpsRequest.MAX_BATCH_SIZE
                            + " points; received " + points.size(),
                    "GPS_BATCH_TOO_LARGE");
        }
    }

    /**
     * Per-point validation. Repeats what {@code RecordGpsRequest} already enforces because the
     * facade entrance used by {@code sync} has no bean validation in front of it.
     */
    private void validatePoint(GpsPointInput point, Instant skewCeiling) {
        if (point == null
                || point.latitude() == null
                || point.longitude() == null
                || point.recordedAt() == null) {
            throw BusinessException.badRequest(
                    "latitude, longitude and recordedAt are all required on every point",
                    "GPS_POINT_INCOMPLETE");
        }
        if (outOfRange(point.latitude(), 90)) {
            throw BusinessException.badRequest(
                    "latitude out of range: " + point.latitude(), "GPS_LATITUDE_OUT_OF_RANGE");
        }
        if (outOfRange(point.longitude(), 180)) {
            throw BusinessException.badRequest(
                    "longitude out of range: " + point.longitude(), "GPS_LONGITUDE_OUT_OF_RANGE");
        }
        if (point.recordedAt().isAfter(skewCeiling)) {
            // A future instant would sit permanently at the top of the live map and could never be
            // superseded by a genuine later fix. Reject rather than store.
            throw BusinessException.badRequest(
                    "recordedAt is too far in the future: " + point.recordedAt(),
                    "GPS_TIMESTAMP_IN_FUTURE");
        }
    }

    private static boolean outOfRange(BigDecimal value, int bound) {
        return value.abs().compareTo(BigDecimal.valueOf(bound)) > 0;
    }

    private void requireActiveRepresentative(Long representativeId) {
        if (!userFacade.isActive(requireRepresentativeExists(representativeId))) {
            throw BusinessException.unprocessable(
                    "Representative is not active: " + representativeId, "REPRESENTATIVE_NOT_ACTIVE");
        }
    }

    /** Translates identity's exception into this module's 404, and returns the id for chaining. */
    private Long requireRepresentativeExists(Long representativeId) {
        try {
            userFacade.getNameById(representativeId);
        } catch (RuntimeException e) {
            throw BusinessException.notFound(
                    "Representative not found: " + representativeId, "REPRESENTATIVE_NOT_FOUND");
        }
        return representativeId;
    }

    /** Name enrichment must never fail a read; an unresolvable user yields {@code null}. */
    private String safeUserName(Long userId) {
        try {
            return userFacade.getNameById(userId);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
