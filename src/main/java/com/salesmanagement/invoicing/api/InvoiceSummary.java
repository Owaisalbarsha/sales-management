package com.salesmanagement.invoicing.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Flat, per-invoice read model published by the {@code invoicing} module for the
 * {@code reporting} module's tabular reports (FR-116 sales list, FR-117 filtered sales).
 *
 * <p><strong>Why this exists (D1 / D2).</strong> Reporting may not import the {@code Invoice}
 * entity or reach into invoicing's tables. This record is the boundary type: invoicing runs the
 * query and hands back a flat row that already carries the cross-module ids ({@code customerId},
 * {@code representativeId}) as plain {@code Long}s. Reporting resolves those ids to names via the
 * other facades in a single batch, never per row.</p>
 *
 * <p><strong>Status.</strong> The {@code status} is exposed as a {@code String} (the enum name),
 * not the internal {@code InvoiceStatus} enum, so no internal type crosses the module boundary.
 * The summary read is unfiltered by status by design — a full sales list may legitimately show
 * DRAFT rows to a manager — so callers that want only realised sales filter on this field or use
 * the aggregate methods, which bake the {@code {SENT, APPROVED}} filter in.</p>
 *
 * @param invoiceId        the invoice id
 * @param customerId       cross-module id of the buying customer
 * @param representativeId cross-module id of the selling rep
 * @param invoiceDate      business date of the sale (already a {@code LocalDate}, D9)
 * @param totalAmount      server-computed invoice total ({@code NUMERIC(12,2)}, HALF_UP)
 * @param status           the invoice status as its enum name (e.g. {@code "SENT"})
 */
public record InvoiceSummary(
        Long       invoiceId,
        Long       customerId,
        Long       representativeId,
        LocalDate  invoiceDate,
        BigDecimal totalAmount,
        String     status
) {}
