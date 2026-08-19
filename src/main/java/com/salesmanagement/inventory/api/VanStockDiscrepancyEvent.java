package com.salesmanagement.inventory.api;

import java.time.Instant;

/**
 * Published when an authoritative offline van deduction (Fork B/E) could not take the full
 * sold quantity off the rep's van, because the server's recorded van stock was lower than the
 * completed offline sale implied.
 *
 * <p>The sale still records — the goods physically left the van in the field, and the mobile
 * app is the primary BR-4 guard (FR-89), so a non-zero {@code shortfall} is the exception, not
 * the norm. It signals a divergence between the device's cached van stock and the server's
 * (multi-device, a bug, or a mid-day change that should not happen). The warehouse manager
 * reconciles it against the physical count at end-of-day return.</p>
 *
 * <p>Consumed by {@code notification} (alert the warehouse manager) and {@code reporting}
 * (a reconciliation report). Emitted per shortfall line, so reconciliation is product-precise.</p>
 *
 * @param representativeId the rep whose van fell short
 * @param productId        the product that could not be fully deducted
 * @param requested        units the offline sale needed to deduct
 * @param deducted         units actually taken off the van (clamped at what was available)
 * @param shortfall        {@code requested - deducted} (always &gt; 0 when this event fires)
 * @param occurredAt       server instant the discrepancy was detected
 */
public record VanStockDiscrepancyEvent(
        Long representativeId,
        Long productId,
        int requested,
        int deducted,
        int shortfall,
        Instant occurredAt
) {}
