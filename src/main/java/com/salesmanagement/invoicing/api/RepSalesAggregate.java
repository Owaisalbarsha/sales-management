package com.salesmanagement.invoicing.api;

import java.math.BigDecimal;

/**
 * Per-representative sales rollup published for FR-115 (rep sales performance).
 *
 * <p><strong>Computed in one query (D1).</strong> This is the read-side of the N+1 fix: instead of
 * reporting fetching every invoice and summing in Java, invoicing runs a single
 * {@code GROUP BY representative_id} and returns one row per rep. A month of 500 invoices across
 * 10 reps comes back as 10 rows, not 500.</p>
 *
 * <p><strong>Status filter (locked decision).</strong> Sales figures count only realised sales —
 * {@code status IN (SENT, APPROVED)}. REJECTED is excluded: a rejected invoice is a sale the
 * manager disowned, so counting it would inflate the rep's numbers. (The un-reversed stock from a
 * rejected invoice is an inventory concern, surfaced by the FR-122 movement report, not here.)</p>
 *
 * @param representativeId cross-module id of the rep
 * @param invoiceCount     number of realised invoices in the window
 * @param totalSales       sum of invoice totals ({@code NUMERIC(12,2)}, HALF_UP)
 */
public record RepSalesAggregate(
        Long       representativeId,
        long       invoiceCount,
        BigDecimal totalSales
) {}
