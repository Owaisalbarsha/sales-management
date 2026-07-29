package com.salesmanagement.invoicing.api;

import java.math.BigDecimal;

/**
 * Per-customer purchasing rollup published for FR-118 / FR-119 (customer purchasing behaviour).
 *
 * <p><strong>Computed in one query (D1).</strong> A single {@code GROUP BY customer_id} returns one
 * row per customer over the window — the read-side of the N+1 fix, same as {@link RepSalesAggregate}.
 * Reporting resolves {@code customerId} to a name in one batch call to {@code CustomerFacade}, never
 * per row.</p>
 *
 * <p><strong>Status filter (locked decision).</strong> Counts only realised purchases —
 * {@code status IN (SENT, APPROVED)}, REJECTED excluded — so a customer's spend reflects sales the
 * business stands behind.</p>
 *
 * @param customerId   cross-module id of the customer
 * @param invoiceCount number of realised invoices for this customer in the window
 * @param totalSpent   sum of invoice totals ({@code NUMERIC(12,2)}, HALF_UP)
 */
public record CustomerPurchaseAggregate(
        Long       customerId,
        long       invoiceCount,
        BigDecimal totalSpent
) {}
