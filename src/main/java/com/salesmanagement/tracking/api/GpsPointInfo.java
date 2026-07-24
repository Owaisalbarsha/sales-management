package com.salesmanagement.tracking.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Public read model of a stored GPS fix, returned by
 * {@link TrackingFacade#getLatestLocation(Long)}.
 *
 * <p>Deliberately minimal, in the spirit of {@code VisitInfo} and {@code RouteInfo}: enough for a
 * caller to place a rep on a map and judge how fresh the fix is, without leaking the
 * {@code GpsLog} entity. It carries no {@code id} because no other module has any reason to
 * address an individual fix, and no {@code createdAt} because offline lag is a tracking-internal
 * diagnostic.</p>
 *
 * <p>Note this record intentionally has no {@code active} flag. Freshness is a policy
 * ("15 minutes", D26) owned by whoever is displaying the data; the raw {@code recordedAt} lets a
 * caller apply its own threshold. The REST-facing DTO does carry the flag, because the dashboard
 * should not be reimplementing that rule.</p>
 *
 * @param representativeId the rep who produced the fix
 * @param latitude         WGS84 latitude, scaled to 6 decimal places
 * @param longitude        WGS84 longitude, scaled to 6 decimal places
 * @param recordedAt       UTC instant the device captured the fix, truncated to milliseconds
 */
public record GpsPointInfo(
        Long       representativeId,
        BigDecimal latitude,
        BigDecimal longitude,
        Instant    recordedAt
) {}
