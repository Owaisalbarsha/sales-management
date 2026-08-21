package com.salesmanagement.tracking.internal.entity;

import com.salesmanagement.shared.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * A single GPS fix captured by a sales rep's device.
 *
 * <p>Owned by the {@code tracking} module. Other modules never touch this entity; they see only
 * the module's REST responses and the read models exposed by {@code TrackingFacade}.</p>
 *
 * <p><strong>Immutable by design.</strong> Unlike {@code Invoice}, this entity has no setters and
 * no state machine. A GPS fix is a historical fact: once written it is never corrected, only
 * superseded by a later fix. That is also why {@code updated_at} (inherited from
 * {@link BaseEntity}) is dead weight here, kept only for consistency across entities.</p>
 *
 * <p><strong>References (project rule):</strong> {@code representativeId} is a cross-module link
 * to {@code identity.users(id)}. Plain {@code Long}, never {@code @ManyToOne}. The DB FK is
 * declared in the V11 migration.</p>
 *
 * <p><strong>Normalisation (D7, D21).</strong> The constructor is the single choke point where
 * incoming values are made canonical:</p>
 * <ul>
 *   <li>{@code recordedAt} is truncated to <em>milliseconds</em>. PostgreSQL stores
 *       {@code timestamptz} at microsecond precision while {@code Instant} carries nanoseconds,
 *       so an untruncated value would not compare equal to the value read back. Since
 *       {@code recordedAt} is half of the dedup key {@code UNIQUE(representative_id,
 *       recorded_at)}, that mismatch would make the service's dedup pre-filter silently miss
 *       duplicates.</li>
 *   <li>{@code latitude} / {@code longitude} are scaled to 6 decimal places {@code HALF_UP},
 *       matching {@code NUMERIC(9,6)}. Doing it here rather than letting the database round means
 *       the in-memory object always equals the persisted row.</li>
 * </ul>
 *
 * <p>Range validation is <em>not</em> done here. It belongs on the request DTO
 * ({@code @DecimalMin} / {@code @DecimalMax}), where a violation becomes a 400 with a usable
 * message rather than an exception deep in the persistence layer. The CHECK constraints in V11
 * are the last line of defence, not the first.</p>
 */
@Entity
@Table(name = "gps_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GpsLog extends BaseEntity {

    /** Decimal places stored for both coordinates, matching {@code NUMERIC(9,6)}. */
    public static final int COORDINATE_SCALE = 6;

    /** Cross-module FK to {@code identity.users(id)} — the capturing SALES_REP. */
    @Column(name = "representative_id", nullable = false)
    private Long representativeId;

    @Column(name = "latitude", nullable = false, precision = 9, scale = COORDINATE_SCALE)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false, precision = 9, scale = COORDINATE_SCALE)
    private BigDecimal longitude;

    /** Device capture instant, truncated to milliseconds. Half of the dedup key. */
    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    /**
     * Creates a normalised GPS fix. All four arguments are required.
     *
     * @param representativeId the capturing rep (from the authenticated principal, D16)
     * @param latitude         WGS84 latitude, already range-validated by the caller
     * @param longitude        WGS84 longitude, already range-validated by the caller
     * @param recordedAt       device capture instant; truncated to milliseconds here
     */
    public GpsLog(Long representativeId, BigDecimal latitude, BigDecimal longitude, Instant recordedAt) {
        this.representativeId = representativeId;
        this.latitude         = normalizeCoordinate(latitude);
        this.longitude        = normalizeCoordinate(longitude);
        this.recordedAt       = normalizeInstant(recordedAt);
    }

    /**
     * Canonical form of a capture instant: truncated to milliseconds.
     *
     * <p>Exposed publicly because the service must apply the identical transformation to the
     * timestamps it sends into the dedup pre-filter query. If the two ever diverge, dedup breaks
     * without any test failing on the happy path — which is precisely the bug
     * {@code ingest_skipsDuplicate_whenTimestampDiffersOnlyInSubMilliseconds} exists to catch.</p>
     *
     * @param instant a capture instant, or {@code null}
     * @return the truncated instant, or {@code null} if the input was {@code null}
     */
    public static Instant normalizeInstant(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MILLIS);
    }

    /** Canonical form of a coordinate: 6 decimal places, {@code HALF_UP}. */
    private static BigDecimal normalizeCoordinate(BigDecimal value) {
        return value == null ? null : value.setScale(COORDINATE_SCALE, RoundingMode.HALF_UP);
    }
}
