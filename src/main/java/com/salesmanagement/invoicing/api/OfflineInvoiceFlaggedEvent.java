package com.salesmanagement.invoicing.api;

import java.time.Instant;

/**
 * Published when a completed offline sale was recorded despite a conflict that the online path
 * would have rejected outright (FR-94 detection). The sale is not blocked — it physically
 * happened and the customer signed for it (Fork B/C) — but a manager should see the conflict.
 *
 * <p>Two reasons in v1:</p>
 * <ul>
 *   <li>{@code CUSTOMER_INACTIVE} — the customer was deactivated while the rep was offline. Online
 *       submit throws 422 here; offline it records and flags.</li>
 *   <li>{@code PRICE_DRIFT} — a line's frozen offline price differs from the current server price.
 *       The frozen price wins (it matches the signed ePOD, D16); the drift is surfaced, not
 *       corrected.</li>
 * </ul>
 *
 * <p>Consumed by {@code notification}/{@code reporting}. Flagging via an event keeps the
 * {@code invoices} schema unchanged — no review-flag columns were added to a closed module.</p>
 *
 * @param invoiceId        the synced invoice
 * @param representativeId the selling rep
 * @param reason           machine-readable conflict reason ({@code CUSTOMER_INACTIVE} | {@code PRICE_DRIFT})
 * @param detail           human-readable detail for the review queue
 * @param occurredAt       server instant the invoice was synced
 */
public record OfflineInvoiceFlaggedEvent(
        Long invoiceId,
        Long representativeId,
        String reason,
        String detail,
        Instant occurredAt
) {}
