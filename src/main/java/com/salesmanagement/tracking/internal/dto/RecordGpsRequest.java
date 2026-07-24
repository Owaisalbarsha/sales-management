package com.salesmanagement.tracking.internal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Body of {@code POST /api/tracking/gps} — a batch of GPS fixes from one rep's device.
 *
 * <p>Always a batch, even for a single point (D15). One shape covers the online case (one point
 * every 60 seconds) and the reconnect case (the backlog accumulated while offline), so there is
 * one code path and one dedup implementation rather than two.</p>
 *
 * <p><strong>No {@code representativeId} field, by design (D16).</strong> The rep is taken from the
 * JWT principal. A body field would be something to forge.</p>
 *
 * <p>Range checks live here rather than in the service because a violation should come back as a
 * 400 with a field-level message that names the offending point, which is what
 * {@code GlobalExceptionHandler} produces from {@code @Valid}. The CHECK constraints in V11 are
 * the last line of defence, not the first.</p>
 *
 * <p>Clock-skew rejection (D22) is deliberately <em>not</em> here: "not in the future" needs
 * {@code now()}, and a bean-validation annotation that reads the clock is harder to test than a
 * plain guard in the service.</p>
 */
public record RecordGpsRequest(

        @NotEmpty(message = "at least one GPS point is required")
        @Size(max = RecordGpsRequest.MAX_BATCH_SIZE,
              message = "a batch may not exceed " + RecordGpsRequest.MAX_BATCH_SIZE + " points")
        @Valid
        List<Point> points
) {

    /**
     * Upper bound on one batch (D17). An eight-hour offline day at the default 60-second capture
     * interval produces 480 points, so 500 clears the realistic worst case with headroom. A client
     * holding more than this must split; the cap bounds request size, transaction duration and the
     * {@code IN} list of the dedup pre-filter.
     */
    public static final int MAX_BATCH_SIZE = 500;

    /**
     * One fix within the batch.
     *
     * @param latitude   WGS84 latitude
     * @param longitude  WGS84 longitude
     * @param recordedAt device capture instant; ISO 8601 <em>with an explicit offset</em>
     *                   (for example {@code 2026-07-22T11:04:00+03:00}), never a bare {@code Z}
     *                   standing in for local wall-clock time
     */
    public record Point(

            @NotNull(message = "latitude is required")
            @DecimalMin(value = "-90.0",  message = "latitude must be >= -90")
            @DecimalMax(value = "90.0",   message = "latitude must be <= 90")
            BigDecimal latitude,

            @NotNull(message = "longitude is required")
            @DecimalMin(value = "-180.0", message = "longitude must be >= -180")
            @DecimalMax(value = "180.0",  message = "longitude must be <= 180")
            BigDecimal longitude,

            @NotNull(message = "recordedAt is required")
            Instant recordedAt
    ) {}
}
