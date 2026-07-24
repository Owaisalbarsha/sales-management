package com.salesmanagement.tracking.internal.service;

import com.salesmanagement.identity.api.UserFacade;
import com.salesmanagement.shared.exception.BusinessException;
import com.salesmanagement.tracking.api.GpsPointInput;
import com.salesmanagement.tracking.api.IngestResult;
import com.salesmanagement.tracking.internal.dto.GpsPointResponse;
import com.salesmanagement.tracking.internal.dto.RecordGpsRequest;
import com.salesmanagement.tracking.internal.dto.RepLatestLocationResponse;
import com.salesmanagement.tracking.internal.repository.GpsLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * Module tests for {@link TrackingService}.
 *
 * <p>{@code @ApplicationModuleTest} boots {@code tracking} and nothing else, so a stray import from
 * another module fails the test rather than quietly working. {@code UserFacade} is the module's one
 * outbound edge and is mocked; if a second {@code @MockBean} facade ever appears here, that is a
 * signal the module has grown a dependency worth arguing about.</p>
 *
 * <p><strong>These tests need a real PostgreSQL.</strong> The live-map query uses
 * {@code DISTINCT ON}, which no embedded database implements, and {@code NUMERIC}/{@code TIMESTAMPTZ}
 * round-tripping is exactly what the truncation tests are checking. See
 * {@code application-test.yml}: point it at a throwaway local database, or swap in Testcontainers if
 * Docker is available. Running these against H2 would pass the tests that do not matter and skip the
 * ones that do.</p>
 *
 * <p>The class is deliberately <em>not</em> {@code @Transactional}. Rolling back after each test
 * would hide the very behaviour under test: {@code ingest} commits, and the overlapping-batch case
 * only means something when the first batch is genuinely durable when the second arrives. Cleanup is
 * therefore explicit.</p>
 */
@ApplicationModuleTest
@ActiveProfiles("test")
class TrackingServiceTest {

    private static final Long REP_A = 1L;
    private static final Long REP_B = 2L;

    /** A fixed, whole-millisecond base instant, so no test depends on the wall clock. */
    private static final Instant T0 =
            Instant.parse("2026-07-22T08:00:00Z").truncatedTo(ChronoUnit.MILLIS);

    @Autowired TrackingService trackingService;
    @Autowired GpsLogRepository gpsLogRepository;

    @MockitoBean UserFacade userFacade;

    @BeforeEach
    void setUp() {
        gpsLogRepository.deleteAll();
        given(userFacade.isActive(anyLong())).willReturn(true);
        given(userFacade.getNameById(anyLong())).willReturn("Test Rep");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Ingest — happy path and deduplication
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("1. a batch of new points is persisted in full")
    void ingest_persistsAllPoints_whenBatchIsNew() {
        IngestResult result = trackingService.ingest(REP_A, points(T0, T0.plusSeconds(60), T0.plusSeconds(120)));

        assertThat(result.accepted()).isEqualTo(3);
        assertThat(result.duplicatesSkipped()).isZero();
        assertThat(gpsLogRepository.count()).isEqualTo(3);
    }

    /**
     * The test this module exists to pass.
     *
     * <p>A rep returning to coverage resends the tail of their queue, so an overlapping batch is the
     * normal case rather than an edge case. The naive implementation, handing the whole batch to
     * {@code saveAll}, passes the first call and then trips
     * {@code uq_gps_logs_rep_recorded_at} on the second, rolling back every point including the new
     * one. The device retries, collides identically, and its queue never drains.</p>
     *
     * <p>Note what is asserted: not merely "no exception", but the exact split. An implementation
     * that swallowed the collision and silently dropped the new point would also avoid throwing.</p>
     */
    @Test
    @DisplayName("2. an overlapping resend stores only the new points and reports the rest as duplicates")
    void ingest_skipsExisting_whenBatchOverlapsPreviousBatch() {
        trackingService.ingest(REP_A, points(T0, T0.plusSeconds(60), T0.plusSeconds(120)));

        IngestResult second = trackingService.ingest(
                REP_A, points(T0.plusSeconds(60), T0.plusSeconds(120), T0.plusSeconds(180)));

        assertThat(second.accepted()).isEqualTo(1);
        assertThat(second.duplicatesSkipped()).isEqualTo(2);
        assertThat(gpsLogRepository.count()).isEqualTo(4);
    }

    @Test
    @DisplayName("3. a batch containing the same instant twice contributes one point")
    void ingest_dedupesWithinBatch_whenSameTimestampSentTwice() {
        IngestResult result = trackingService.ingest(REP_A, points(T0, T0, T0.plusSeconds(60)));

        assertThat(result.accepted()).isEqualTo(2);
        assertThat(result.duplicatesSkipped()).isZero();
        assertThat(gpsLogRepository.count()).isEqualTo(2);
    }

    /**
     * The silent-failure case.
     *
     * <p>{@code Instant} carries nanoseconds; PostgreSQL stores microseconds. Without the
     * millisecond truncation in {@code GpsLog}, the pre-filter would compare a nanosecond-precision
     * candidate against a rounded stored value, find no match, and attempt an insert that the unique
     * constraint then rejects. Nothing on the happy path would ever reveal it.</p>
     */
    @Test
    @DisplayName("4. instants differing only below the millisecond are the same point")
    void ingest_skipsDuplicate_whenTimestampDiffersOnlyInSubMilliseconds() {
        Instant precise = T0.plusNanos(123_456_789L);
        Instant nearby  = T0.plusNanos(123_999_999L);

        trackingService.ingest(REP_A, points(precise));
        IngestResult second = trackingService.ingest(REP_A, points(nearby));

        assertThat(second.accepted()).isZero();
        assertThat(second.duplicatesSkipped()).isEqualTo(1);
        assertThat(gpsLogRepository.count()).isEqualTo(1);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Ingest — rejection
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("5. a capture instant beyond the clock-skew tolerance is rejected")
    void ingest_rejects_whenTimestampIsInTheFuture() {
        Instant tooFarAhead = Instant.now().plus(10, ChronoUnit.MINUTES);

        assertThatThrownBy(() -> trackingService.ingest(REP_A, points(tooFarAhead)))
                .isInstanceOf(BusinessException.class);

        assertThat(gpsLogRepository.count()).isZero();
    }

    @Test
    @DisplayName("6. an empty batch is rejected")
    void ingest_rejects_whenBatchIsEmpty() {
        assertThatThrownBy(() -> trackingService.ingest(REP_A, List.<GpsPointInput>of()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("7. a batch above the size cap is rejected")
    void ingest_rejects_whenBatchExceedsMaxSize() {
        List<GpsPointInput> oversized = IntStream.rangeClosed(0, RecordGpsRequest.MAX_BATCH_SIZE)
                .mapToObj(i -> point(T0.plusSeconds(i)))
                .toList();

        assertThatThrownBy(() -> trackingService.ingest(REP_A, oversized))
                .isInstanceOf(BusinessException.class);

        assertThat(gpsLogRepository.count()).isZero();
    }

    /**
     * Duplicates what {@code RecordGpsRequest} already validates, on purpose. The facade entrance
     * that {@code sync} will use has no bean validation in front of it, so a guard that lives only
     * in the DTO is a guard the second caller never gets.
     */
    @Test
    @DisplayName("8. out-of-range coordinates are rejected and nothing is stored")
    void ingest_rejects_whenCoordinatesOutOfRange() {
        GpsPointInput badLatitude = new GpsPointInput(
                new BigDecimal("91.000000"), new BigDecimal("36.276527"), T0);

        assertThatThrownBy(() -> trackingService.ingest(REP_A, List.of(badLatitude)))
                .isInstanceOf(BusinessException.class);

        assertThat(gpsLogRepository.count()).isZero();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Reads
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Two reps, three points each, and only the newest of each may come back. Also pins the
     * freshness rule: a rep whose last fix predates the active window is still returned, flagged
     * inactive, because a marker that disappears tells a manager nothing about where contact was
     * lost.
     */
    @Test
    @DisplayName("9. latest returns exactly one row per rep, carrying the freshness flag")
    void latest_returnsOneRowPerRep_withActiveFlag() {
        Instant recent = Instant.now().minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
        Instant stale  = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);

        trackingService.ingest(REP_A, points(recent.minusSeconds(120), recent.minusSeconds(60), recent));
        trackingService.ingest(REP_B, points(stale.minusSeconds(60), stale));

        List<RepLatestLocationResponse> latest = trackingService.getLatestLocations();

        assertThat(latest).hasSize(2);

        RepLatestLocationResponse a = latest.stream()
                .filter(r -> r.representativeId().equals(REP_A)).findFirst().orElseThrow();
        RepLatestLocationResponse b = latest.stream()
                .filter(r -> r.representativeId().equals(REP_B)).findFirst().orElseThrow();

        assertThat(a.recordedAt()).isEqualTo(recent);
        assertThat(a.active()).isTrue();

        assertThat(b.recordedAt()).isEqualTo(stale);
        assertThat(b.active()).isFalse();
    }

    /**
     * Also guards the day boundary. The point at 23:59:59 belongs to the requested day; the one a
     * second later does not. An inclusive upper bound would pull in the first fix of the next
     * morning.
     */
    @Test
    @DisplayName("10. trail returns that day's points only, oldest first")
    void trail_returnsOrderedPoints_forGivenDate() {
        LocalDate day = LocalDate.of(2026, 7, 22);
        Instant dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant();

        trackingService.ingest(REP_A, points(
                dayStart.plus(9, ChronoUnit.HOURS),
                dayStart.plus(8, ChronoUnit.HOURS),
                dayStart.plus(23, ChronoUnit.HOURS).plusSeconds(3599),
                dayStart.plus(24, ChronoUnit.HOURS)));

        List<GpsPointResponse> trail = trackingService.getTrail(REP_A, day);

        assertThat(trail).hasSize(3);
        assertThat(trail).extracting(GpsPointResponse::recordedAt).isSorted();
        assertThat(trail.get(0).recordedAt()).isEqualTo(dayStart.plus(8, ChronoUnit.HOURS));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Fixtures
    // ═══════════════════════════════════════════════════════════════════════

    /** Damascus, roughly. Coordinates are irrelevant to every assertion here; only instants matter. */
    private static GpsPointInput point(Instant recordedAt) {
        return new GpsPointInput(new BigDecimal("33.513805"), new BigDecimal("36.276527"), recordedAt);
    }

    private static List<GpsPointInput> points(Instant... recordedAt) {
        return java.util.Arrays.stream(recordedAt).map(TrackingServiceTest::point).toList();
    }
}
