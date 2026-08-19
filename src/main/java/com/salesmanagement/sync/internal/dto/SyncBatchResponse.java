package com.salesmanagement.sync.internal.dto;

import java.util.List;

/**
 * The batch outcome: a per-item result array plus a roll-up the device can show as a
 * sync summary (FR-93). Returned inside the standard {@code ApiResponse} envelope with
 * HTTP 200 — individual FAILED items live in {@link #results}, they do not fail the call.
 *
 * @param results  one entry per submitted item, keyed by clientUuid
 * @param synced   count processed for the first time
 * @param duplicate count recognised as already-synced (E3)
 * @param failed   count terminally rejected (E4)
 */
public record SyncBatchResponse(
        List<SyncItemResult> results,
        int synced,
        int duplicate,
        int failed
) {
    /** Rolls the per-item results into the summary counts. */
    public static SyncBatchResponse of(List<SyncItemResult> results) {
        int synced = 0, duplicate = 0, failed = 0;
        for (SyncItemResult r : results) {
            switch (r.outcome()) {
                case SYNCED    -> synced++;
                case DUPLICATE -> duplicate++;
                case FAILED    -> failed++;
            }
        }
        return new SyncBatchResponse(results, synced, duplicate, failed);
    }
}
