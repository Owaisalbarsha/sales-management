package com.salesmanagement.sync.internal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A batch of offline records pushed on reconnect (UC-23; POST /api/sync/batch).
 *
 * <p>The device pages large queues into batches to avoid disconnects (UC-23 special
 * requirement); {@link #MAX_BATCH_SIZE} caps one request so a rep who was offline for
 * a week cannot post ten thousand items in a single transaction-heavy call. If the
 * device has more, it sends more batches — the server is idempotent, so ordering
 * across batches is safe (visits and their invoices should travel in the same batch,
 * but a split is tolerated because an unresolved visit reference degrades to a null
 * link, never a failure).</p>
 */
public record SyncBatchRequest(
        @NotEmpty @Size(max = SyncBatchRequest.MAX_BATCH_SIZE) @Valid List<SyncItemRequest> items
) {
    /**
     * Ceiling on items per request. 500 mirrors the tracking ingest cap and keeps a
     * single batch's per-item transactions bounded. Purely a transport limit — total
     * queue size is unbounded across batches.
     */
    public static final int MAX_BATCH_SIZE = 500;
}
