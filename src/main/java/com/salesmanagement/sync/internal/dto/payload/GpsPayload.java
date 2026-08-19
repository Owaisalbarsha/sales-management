package com.salesmanagement.sync.internal.dto.payload;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The JSON shape of a GPS_LOG sync item's payload — one location fix. A GPS_LOG item is
 * a single point; sync collects all GPS points in a batch (they all belong to the one
 * authenticated rep) and hands them to {@code TrackingFacade.ingest} as a list, which
 * dedups on {@code (rep, recordedAt)} and caps at 500 (chunked by sync if exceeded).
 *
 * @param latitude   WGS84 latitude
 * @param longitude  WGS84 longitude
 * @param recordedAt device UTC instant the fix was captured
 */
public record GpsPayload(
        BigDecimal latitude,
        BigDecimal longitude,
        Instant    recordedAt
) {}
