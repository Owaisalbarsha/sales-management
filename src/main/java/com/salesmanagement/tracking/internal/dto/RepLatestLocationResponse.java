package com.salesmanagement.tracking.internal.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A rep's most recent known position — the marker on the live map.
 *
 * <p><strong>The same record is returned by both {@code GET /api/tracking/latest} and every event
 * pushed down {@code GET /api/tracking/live}.</strong> That is deliberate: the dashboard fetches
 * {@code /latest} once on page load to draw the initial marker set, then applies SSE events to the
 * same structure. One shape, one handler, no branching on where the data came from.</p>
 *
 * <p>{@code active} is computed server-side against the freshness window (D26) rather than left to
 * the client, so "how stale is too stale" is defined once. A rep whose last fix is older than the
 * window is returned with {@code active = false} rather than omitted: a marker that vanishes tells
 * the manager nothing, whereas a greyed marker at the last known position tells them where the rep
 * was when contact was lost. Whether to grey, fade or hide is the dashboard's call.</p>
 *
 * <p>{@code representativeName} is resolved through {@code UserFacade} and may be {@code null} if
 * the user record has since been removed. It is enrichment, never a reason to fail a read.</p>
 *
 * @param representativeId   the rep
 * @param representativeName display name, or {@code null} if it could not be resolved
 * @param latitude           WGS84 latitude of the most recent fix
 * @param longitude          WGS84 longitude of the most recent fix
 * @param recordedAt         when the device captured that fix
 * @param active             whether {@code recordedAt} falls inside the freshness window
 */
public record RepLatestLocationResponse(
        Long       representativeId,
        String     representativeName,
        BigDecimal latitude,
        BigDecimal longitude,
        Instant    recordedAt,
        boolean    active
) {}
