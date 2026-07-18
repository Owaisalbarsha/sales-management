package com.salesmanagement.invoicing.api;

import java.time.Instant;

/**
 * Domain event: a sales manager (or admin) approved an invoice (SENT → APPROVED).
 *
 * <p>Approval is informational in the MVP — it validates the invoice as clean revenue and
 * carries no stock or state side-effects (stock already moved at submit). Published so the
 * future {@code notification} module can tell the rep their invoice was approved (FR-103).</p>
 *
 * @param invoiceId        the approved invoice
 * @param representativeId the rep who owns the invoice (the notification target)
 * @param at               when approval occurred (server UTC instant)
 */
public record InvoiceApprovedEvent(
        Long    invoiceId,
        Long    representativeId,
        Instant at
) {}
