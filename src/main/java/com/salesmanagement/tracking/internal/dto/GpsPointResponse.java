package com.salesmanagement.tracking.internal.dto;

import com.salesmanagement.tracking.internal.entity.GpsLog;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One point on a rep's trail, returned by
 * {@code GET /api/tracking/reps/{repId}/trail?date=YYYY-MM-DD}.
 *
 * <p>Carries no {@code representativeId}: the whole response belongs to the rep named in the path,
 * and repeating the id on every one of ~480 points is wire noise. The dashboard renders the list
 * as a polyline in the order returned (oldest first).</p>
 *
 * <p>No {@code id} either. Nothing addresses an individual fix, and exposing a key invites a
 * future endpoint that edits or deletes one, which would break the append-only guarantee this
 * table depends on.</p>
 */
public record GpsPointResponse(
        BigDecimal latitude,
        BigDecimal longitude,
        Instant    recordedAt
) {
    public static GpsPointResponse from(GpsLog log) {
        return new GpsPointResponse(log.getLatitude(), log.getLongitude(), log.getRecordedAt());
    }
}
