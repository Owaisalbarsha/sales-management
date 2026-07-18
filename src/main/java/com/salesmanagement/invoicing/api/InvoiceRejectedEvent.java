package com.salesmanagement.invoicing.api;

import java.time.Instant;

/**
 * Domain event: a sales manager (or admin) rejected an invoice (SENT → REJECTED), with a
 * mandatory non-blank reason (BR-3).
 *
 * <p>Rejection is informational in the MVP — it flags the invoice as disputed/erroneous but
 * does not reverse the sale (stock stays deducted, D1). Published so the future
 * {@code notification} module can tell the rep their invoice was rejected <em>and why</em>
 * (FR-104), which is what lets them raise a corrected invoice.</p>
 *
 * @param invoiceId        the rejected invoice
 * @param representativeId the rep who owns the invoice (the notification target)
 * @param reason           the mandatory rejection reason (never blank)
 * @param at               when rejection occurred (server UTC instant)
 */
public record InvoiceRejectedEvent(
        Long    invoiceId,
        Long    representativeId,
        String  reason,
        Instant at
) {}
