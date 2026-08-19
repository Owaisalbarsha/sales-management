package com.salesmanagement.sync.internal.enums;

/**
 * The server-side lifecycle of a queue item (ERD {@code SYNC_QUEUE_ITEM.SyncStatus}).
 *
 * <p>Because processing is synchronous within the batch request (Fork A), an item
 * does not rest in {@link #PENDING} on the server between requests: it is written
 * PENDING and settled to {@link #SYNCED} or {@link #FAILED} in the same
 * transaction. PENDING exists as the pre-settlement state and as the value the
 * device shows while an item is in flight (FR-93).</p>
 */
public enum SyncStatus {

    /** Received, not yet settled. Transient on the server; a live indicator on the device (FR-93). */
    PENDING,

    /**
     * Processed successfully. For INVOICE/VISIT the created server id is recorded
     * in {@code server_record_id}. A re-sent SYNCED item is skipped as success (E3).
     */
    SYNCED,

    /**
     * Terminally rejected (E4): unparseable payload, or a business rule the retry
     * cannot cure (this is not a transient/backoff state — that is the device's
     * job, Fork G). Carries an {@code error_code} the device reads and the
     * dead-letter surfaces.
     */
    FAILED
}
