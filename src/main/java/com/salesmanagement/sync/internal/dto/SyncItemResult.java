package com.salesmanagement.sync.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The per-item outcome the device reads to reconcile its local queue (UC-23
 * post-conditions; FR-93). Keyed by {@code clientUuid} so the device can match it back
 * regardless of the order the server processed things.
 *
 * <p>The overall HTTP response is 200 even when some items FAILED — a batch is a bag of
 * independent records, not one transaction (E4: reject the bad item, keep the rest).
 * The device acts per item: SYNCED/DUPLICATE clear it from the queue; FAILED it either
 * drops or lets the user retry (E4); the {@code errorCode} tells it which.</p>
 *
 * @param clientUuid      the item's idempotency key, echoed back for matching
 * @param outcome         SYNCED | DUPLICATE | FAILED
 * @param serverRecordId  id of the created record (invoice/visit); null for GPS, DUPLICATE-of-GPS, or FAILED
 * @param errorCode       machine-readable failure code; null unless FAILED
 * @param errorDetail     human-readable failure detail; null unless FAILED
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SyncItemResult(
        String clientUuid,
        Outcome outcome,
        Long serverRecordId,
        String errorCode,
        String errorDetail
) {

    /** Per-item terminal outcomes returned to the device. */
    public enum Outcome {
        /** Processed for the first time. */
        SYNCED,
        /** Already present from a prior sync — recognised by clientUuid and skipped as success (E3). */
        DUPLICATE,
        /** Terminally rejected (E4). Carries an errorCode. */
        FAILED
    }

    public static SyncItemResult synced(String clientUuid, Long serverRecordId) {
        return new SyncItemResult(clientUuid, Outcome.SYNCED, serverRecordId, null, null);
    }

    public static SyncItemResult duplicate(String clientUuid, Long serverRecordId) {
        return new SyncItemResult(clientUuid, Outcome.DUPLICATE, serverRecordId, null, null);
    }

    public static SyncItemResult failed(String clientUuid, String errorCode, String errorDetail) {
        return new SyncItemResult(clientUuid, Outcome.FAILED, null, errorCode, errorDetail);
    }
}
