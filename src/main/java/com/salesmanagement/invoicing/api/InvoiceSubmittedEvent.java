package com.salesmanagement.invoicing.api;

import java.time.Instant;

/**
 * Domain event: a rep has submitted an invoice (DRAFT → SENT). At this point van stock has
 * already been deducted atomically and the invoice is immutable.
 *
 * <p>Published by {@code invoicing} on the submit transition. No consumer exists yet; the
 * {@code notification} module will later listen to alert the sales manager that an invoice is
 * awaiting review. Spring Modulith's event publication log persists the event safely until a
 * listener is registered.</p>
 *
 * @param invoiceId        the submitted invoice
 * @param representativeId the rep who created and submitted it
 * @param at               when submission occurred (server UTC instant)
 */
public record InvoiceSubmittedEvent(
        Long    invoiceId,
        Long    representativeId,
        Instant at
) {}
