package com.salesmanagement.tracking.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A single GPS fix offered to {@link TrackingFacade#ingest}.
 *
 * <p>Deliberately carries no {@code representativeId}: the rep is supplied as a separate argument
 * to {@code ingest}, taken from the authenticated principal on the REST path (D16) and from the
 * queue item's owner on the {@code sync} path. Putting it inside the point would invite a caller
 * to attribute fixes to someone else.</p>
 *
 * <p>Values arrive raw. {@code recordedAt} is truncated to milliseconds and the coordinates are
 * scaled to 6 places inside the entity constructor, so callers need not normalise anything.
 * Range and clock-skew validation happen in {@code TrackingService}, not here — a record is a
 * data carrier, not a guard.</p>
 *
 * @param latitude   WGS84 latitude, expected within [-90, 90]
 * @param longitude  WGS84 longitude, expected within [-180, 180]
 * @param recordedAt UTC instant the device captured the fix; clients send ISO 8601 with an
 *                   explicit offset, never a bare {@code Z} standing in for local wall-clock time
 */
public record GpsPointInput(
        BigDecimal latitude,
        BigDecimal longitude,
        Instant    recordedAt
) {}
