package com.salesmanagement.tracking.internal.dto;

import com.salesmanagement.tracking.api.IngestResult;

/**
 * Body of a successful {@code POST /api/tracking/gps}.
 *
 * <p><strong>Read this before writing the mobile client.</strong> {@code duplicatesSkipped} is a
 * success outcome, not a failure. A rep coming back online resends the tail of their local queue,
 * so overlap with what the server already holds is the expected case (FR-60 says prevent
 * duplicates, not reject them). A response of {@code accepted: 0, duplicatesSkipped: 480} means
 * every one of those points is safely stored and all 480 must be cleared from the device queue.
 * Treating it as an error produces a queue that never drains and a rep whose device retries
 * forever.</p>
 *
 * @param accepted          points newly persisted by this call
 * @param duplicatesSkipped points already held for this rep at the same instant
 */
public record IngestResponse(
        int accepted,
        int duplicatesSkipped
) {
    public static IngestResponse from(IngestResult result) {
        return new IngestResponse(result.accepted(), result.duplicatesSkipped());
    }
}
