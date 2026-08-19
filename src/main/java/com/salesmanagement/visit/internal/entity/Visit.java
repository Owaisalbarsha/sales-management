package com.salesmanagement.visit.internal.entity;

import com.salesmanagement.shared.domain.BaseEntity;
import com.salesmanagement.visit.internal.enums.VisitStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A field visit to a customer, owned by the {@code visit} module. Mirrors the ERD
 * {@code VISIT} block: {@code customerId}, {@code representativeId}, {@code routeId},
 * check-in/out time and location, and {@link VisitStatus}.
 *
 * <p><strong>Cross-module references are plain ids, never {@code @ManyToOne}</strong> (project
 * rule): {@code customerId} → {@code customer.customers(id)}, {@code representativeId} →
 * {@code identity.users(id)}, {@code routeId} → {@code routing.routes(id)}. The real FK
 * constraints live in this module's {@code V8} migration.</p>
 *
 * <p><strong>Why the check-in/out columns are nullable:</strong></p>
 * <ul>
 *   <li>{@code IN_PROGRESS} — {@code checkInTime}/{@code checkInLocation} set, check-out null.</li>
 *   <li>{@code COMPLETED}  — all four set.</li>
 *   <li>{@code MISSED}     — all four null (the stop was never visited).</li>
 * </ul>
 * The service enforces which fields are populated per status; the columns themselves permit null.
 *
 * <p>{@code checkInTime}/{@code checkOutTime} are {@link Instant} (UTC) and are
 * <em>client-supplied</em> for offline correctness (the device captures the real moment of the
 * action; the server stores it as given after a clock-skew check, per SRS FR-87 / NFR-31).
 * Location is stored as a {@code "lat,lng"} string exactly as the ERD specifies.</p>
 */
@Entity
@Table(name = "visits")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Visit extends BaseEntity {

    /** Cross-module FK to {@code customers(id)}. */
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** Cross-module FK to {@code users(id)} — always a {@code SALES_REP}. */
    @Column(name = "representative_id", nullable = false)
    private Long representativeId;

    /** Cross-module FK to {@code routes(id)} — the route this stop belongs to. */
    @Column(name = "route_id", nullable = false)
    private Long routeId;

    /** Client-supplied UTC instant of check-in. Null for a {@code MISSED} visit. */
    @Column(name = "check_in_time")
    private Instant checkInTime;

    /** GPS at check-in, stored as {@code "lat,lng"}. Null for a {@code MISSED} visit. */
    @Column(name = "check_in_location", length = 64)
    private String checkInLocation;

    /** Client-supplied UTC instant of check-out. Null until the rep checks out. */
    @Column(name = "check_out_time")
    private Instant checkOutTime;

    /** GPS at check-out, stored as {@code "lat,lng"}. Null until the rep checks out. */
    @Column(name = "check_out_location", length = 64)
    private String checkOutLocation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VisitStatus status;

    @Column(name = "client_uuid", length = 36)
    private String clientUuid;

    private Visit(Long customerId, Long representativeId, Long routeId,
                  Instant checkInTime, String checkInLocation, VisitStatus status) {
        this.customerId = customerId;
        this.representativeId = representativeId;
        this.routeId = routeId;
        this.checkInTime = checkInTime;
        this.checkInLocation = checkInLocation;
        this.status = status;
    }

    /**
     * A fresh {@code IN_PROGRESS} visit created at check-in.
     *
     * @param customerId       the stop's customer
     * @param representativeId the checking-in rep (from the JWT, never the body)
     * @param routeId          the route the stop belongs to
     * @param checkInTime      client-supplied UTC instant of arrival
     * @param checkInLocation  {@code "lat,lng"} at arrival
     */
    public static Visit checkIn(Long customerId, Long representativeId, Long routeId,
                                Instant checkInTime, String checkInLocation) {
        return new Visit(customerId, representativeId, routeId,
                checkInTime, checkInLocation, VisitStatus.IN_PROGRESS);
    }

    /**
     * A {@code MISSED} visit for a stop that was never reached. No times, no locations.
     * Materialised by the end-of-day action or the nightly sweep.
     *
     * @param customerId       the stop's customer
     * @param representativeId the rep who owned the route
     * @param routeId          the route the stop belongs to
     */
    public static Visit missed(Long customerId, Long representativeId, Long routeId) {
        return new Visit(customerId, representativeId, routeId, null, null, VisitStatus.MISSED);
    }

    /**
     * Applies check-out: sets time/location and flips {@code IN_PROGRESS -> COMPLETED}.
     * Callers must have already verified the visit is {@code IN_PROGRESS} (BR-10).
     */
    public void completeCheckOut(Instant checkOutTime, String checkOutLocation) {
        this.checkOutTime = checkOutTime;
        this.checkOutLocation = checkOutLocation;
        this.status = VisitStatus.COMPLETED;
    }

    /** Whether this visit has reached a terminal state ({@code COMPLETED} or {@code MISSED}). */
    public boolean isTerminal() {
        return status == VisitStatus.COMPLETED || status == VisitStatus.MISSED;
    }
}
