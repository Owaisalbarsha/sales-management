package com.salesmanagement.tracking.api;

/**
 * Outcome of one batch handed to {@link TrackingFacade#ingest}.
 *
 * <p>The two counts always sum to the number of points the caller submitted <em>after</em>
 * in-batch deduplication (D18): a batch containing the same instant twice contributes one point,
 * not two.</p>
 *
 * <p><strong>{@code duplicatesSkipped} is not an error.</strong> FR-60 says prevent duplicate GPS
 * data on sync, not reject it. A rep coming back online legitimately resends the tail of their
 * queue, so overlap is the expected case, not a fault. Callers must treat a response with
 * {@code accepted = 0} and {@code duplicatesSkipped = 480} as complete success and clear those
 * items from their local queue. Treating it as a failure produces a permanent resend loop, which
 * is the single most likely integration bug on the mobile side.</p>
 *
 * <p>Genuine failures never reach this record: validation problems (empty batch, oversized batch,
 * out-of-range coordinates, future timestamps) throw {@code BusinessException} and fail the whole
 * batch, because a malformed batch is a client defect rather than a data condition.</p>
 *
 * @param accepted          points newly persisted by this call
 * @param duplicatesSkipped points already present for this rep at the same instant
 */
public record IngestResult(
        int accepted,
        int duplicatesSkipped
) {
    /** Total points considered after in-batch deduplication. */
    public int submitted() {
        return accepted + duplicatesSkipped;
    }
}
