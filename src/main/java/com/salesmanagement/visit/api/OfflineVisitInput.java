package com.salesmanagement.visit.api;

import java.time.Instant;

/**
 * Input to {@code VisitFacade.recordOfflineVisit} — one visit captured offline and
 * replayed by the {@code sync} module. A public api type so sync can build it without
 * seeing the internal {@code Visit} entity.
 *
 * <p><strong>Shape mirrors the offline reality.</strong> A rep checks in, then later
 * checks out; both instants are captured on the device. When the whole visit
 * completed offline, all four check-in/out fields are present and the visit lands
 * {@code COMPLETED} in one CREATE. When only the check-in synced (the rep regained
 * connectivity mid-visit), {@code checkOutTime}/{@code checkOutLocation} are null and
 * the visit lands {@code IN_PROGRESS}; the later check-out arrives as an UPDATE
 * (Fork H) keyed on {@link #clientUuid}.</p>
 *
 * <p><strong>Idempotency (Fork A / FR-99).</strong> {@link #clientUuid} is the
 * per-record key. {@code recordOfflineVisit} is idempotent on it: a resend returns
 * the existing visit id rather than creating a second row. This is the key that did
 * not exist before sync (GPS and invoice already had theirs); it is added to the
 * {@code visits} table by the visit-module migration.</p>
 *
 * <p><strong>Trust boundary.</strong> {@code representativeId} is NOT here — the
 * facade takes it as a separate argument from the JWT principal, never from a payload
 * the device could forge.</p>
 *
 * @param clientUuid       per-record idempotency key (v4 UUID from the device)
 * @param customerId       the customer visited (validated to exist by the facade)
 * @param routeId          the route this stop belongs to (validated to exist)
 * @param checkInTime      device UTC instant of check-in; null only for a MISSED stop
 * @param checkInLocation  GPS at check-in as "lat,lng"; null only for a MISSED stop
 * @param checkOutTime     device UTC instant of check-out; null if not yet checked out
 * @param checkOutLocation GPS at check-out as "lat,lng"; null if not yet checked out
 */
public record OfflineVisitInput(
        String  clientUuid,
        Long    customerId,
        Long    routeId,
        Instant checkInTime,
        String  checkInLocation,
        Instant checkOutTime,
        String  checkOutLocation
) {}
