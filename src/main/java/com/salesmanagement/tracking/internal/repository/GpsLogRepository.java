package com.salesmanagement.tracking.internal.repository;

import com.salesmanagement.tracking.internal.entity.GpsLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for {@link GpsLog}. Three queries, one per read the module actually has, plus the
 * inherited writes.
 *
 * <p>Every query is served by the index behind {@code UNIQUE(representative_id, recorded_at)}.
 * No additional index exists on this table (D12), and none should be added without a query that
 * needs it.</p>
 */
public interface GpsLogRepository extends JpaRepository<GpsLog, Long> {

    /**
     * The subset of {@code candidates} that already exists for this rep — the dedup pre-filter
     * behind D19.
     *
     * <p><strong>Why a pre-filter and not just {@code saveAll}.</strong> A rep coming back online
     * resends the tail of their queue. If even one point in a 480-point batch is already stored,
     * {@code saveAll} trips {@code uq_gps_logs_rep_recorded_at} and the entire batch rolls back.
     * The rep retries, collides again, and the queue never drains: a permanent resend loop. So the
     * service asks first, inserts only what is new, and still catches
     * {@code DataIntegrityViolationException} for the concurrent-retry race this query cannot see.</p>
     *
     * <p>Callers must pass instants already normalised through
     * {@link GpsLog#normalizeInstant(Instant)}; comparing untruncated nanosecond values against
     * microsecond-precision stored values never matches.</p>
     *
     * @param representativeId the rep whose points are being ingested
     * @param candidates       normalised capture instants from the incoming batch
     * @return the instants among {@code candidates} that are already persisted
     */
    @Query("""
           select g.recordedAt
             from GpsLog g
            where g.representativeId = :representativeId
              and g.recordedAt in :candidates
           """)
    List<Instant> findExistingRecordedAt(@Param("representativeId") Long representativeId,
                                         @Param("candidates") Collection<Instant> candidates);

    /**
     * One rep's fixes within a half-open instant range, oldest first — the trail behind D27.
     *
     * <p>The controller converts the mandatory {@code date} parameter into
     * {@code [date.atStartOfDay(UTC), +1 day)} before calling this. The range is deliberately
     * half-open, and deliberately compared against the raw column: wrapping it as
     * {@code cast(recorded_at as date) = :date} would apply a function to the indexed column and
     * force a sequential scan. It would also walk straight back into the JPQL date-parameter
     * type-inference problem this project has already hit once.</p>
     *
     * @param representativeId the rep whose trail is requested
     * @param from             inclusive lower bound
     * @param to               exclusive upper bound
     */
    @Query("""
           select g
             from GpsLog g
            where g.representativeId = :representativeId
              and g.recordedAt >= :from
              and g.recordedAt <  :to
            order by g.recordedAt asc
           """)
    List<GpsLog> findTrail(@Param("representativeId") Long representativeId,
                           @Param("from") Instant from,
                           @Param("to") Instant to);

    /**
     * The most recent fix for every rep that has ever reported one — the live map behind D25.
     *
     * <p>PostgreSQL's {@code DISTINCT ON} keeps the first row of each
     * {@code representative_id} group under the given {@code ORDER BY}, so ordering by
     * {@code recorded_at DESC} yields the latest point per rep in a single index scan over
     * {@code uq_gps_logs_rep_recorded_at}. The portable JPQL equivalent needs a correlated
     * {@code max()} subquery and reads worse for no benefit: this system is PostgreSQL and has
     * never claimed otherwise.</p>
     *
     * <p>Aliases are double-quoted so PostgreSQL preserves their camel case; unquoted identifiers
     * are folded to lower case and the interface projection would then fail to bind. This query is
     * a raw string that Hibernate cannot validate at startup, which is exactly why
     * {@code distinctOnQuery_returnsLatestPointPerRepresentative} is a {@code @DataJpaTest} rather
     * than a nice-to-have.</p>
     *
     * <p>Note this returns <em>every</em> rep, including long-idle ones. Deciding which of them
     * counts as active is the service's job (D26), not the database's — the endpoint returns the
     * flag and lets the dashboard choose whether to grey out or hide.</p>
     */
    @Query(value = """
                   SELECT DISTINCT ON (representative_id)
                          representative_id AS "representativeId",
                          latitude          AS "latitude",
                          longitude         AS "longitude",
                          recorded_at       AS "recordedAt"
                     FROM gps_logs
                    ORDER BY representative_id, recorded_at DESC
                   """,
           nativeQuery = true)
    List<LatestLocationRow> findLatestPerRepresentative();

    /**
     * One rep's most recent fix, or empty if they have never reported one.
     *
     * <p>A derived query rather than a slice of {@link #findLatestPerRepresentative()}: with
     * {@code representative_id} as the leading column of the unique index and {@code recorded_at}
     * descending as the second, this is a single index seek. Filtering the whole-fleet result in
     * memory would read one row per rep to discard all but one.</p>
     *
     * <p>Serves {@code TrackingFacade.getLatestLocation}, which {@code reporting} will use for
     * route-adherence work. Nothing calls it today.</p>
     */
    Optional<GpsLog> findFirstByRepresentativeIdOrderByRecordedAtDesc(Long representativeId);

    /**
     * Row shape returned by {@link #findLatestPerRepresentative()}.
     *
     * <p>An interface projection rather than a record: Spring Data builds the proxy from the
     * result-set column names, which keeps the query and its consumer in one file. If the
     * {@code Instant} binding ever fails on a driver upgrade, the fallback is to declare
     * {@code java.sql.Timestamp getRecordedAt()} here and convert in the service — the
     * {@code @DataJpaTest} will tell you before production does.</p>
     */
    interface LatestLocationRow {
        Long getRepresentativeId();
        BigDecimal getLatitude();
        BigDecimal getLongitude();
        Instant getRecordedAt();
    }
}
