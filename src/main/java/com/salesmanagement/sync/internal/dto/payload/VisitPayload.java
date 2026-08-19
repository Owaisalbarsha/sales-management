package com.salesmanagement.sync.internal.dto.payload;

import java.time.Instant;

/**
 * The JSON shape of a VISIT sync item's payload. One record covers both operations:
 *
 * <ul>
 *   <li><strong>CREATE</strong> (check-in, and check-out if the whole visit completed
 *       offline): {@code customerId}, {@code routeId}, {@code checkInTime}/location set;
 *       {@code checkOutTime}/location optionally set.</li>
 *   <li><strong>UPDATE</strong> (a check-out arriving after its check-in already synced,
 *       Fork H): {@code visitClientUuid} points at the check-in item; only
 *       {@code checkOutTime}/location are meaningful.</li>
 * </ul>
 *
 * <p>Sync maps this to the visit module's {@code OfflineVisitInput} (for CREATE) or calls
 * {@code recordOfflineCheckOut} (for UPDATE). {@code visitClientUuid} is a sync-layer
 * concern (the remap key); the visit facade never sees it.</p>
 *
 * @param customerId       the customer visited (CREATE)
 * @param routeId          the route stop (CREATE)
 * @param checkInTime      device instant of check-in (CREATE)
 * @param checkInLocation  GPS "lat,lng" at check-in (CREATE)
 * @param checkOutTime     device instant of check-out (CREATE if completed offline, or UPDATE)
 * @param checkOutLocation GPS "lat,lng" at check-out
 * @param visitClientUuid  clientUuid of the check-in item this UPDATE completes; null on CREATE
 */
public record VisitPayload(
        Long    customerId,
        Long    routeId,
        Instant checkInTime,
        String  checkInLocation,
        Instant checkOutTime,
        String  checkOutLocation,
        String  visitClientUuid
) {}
