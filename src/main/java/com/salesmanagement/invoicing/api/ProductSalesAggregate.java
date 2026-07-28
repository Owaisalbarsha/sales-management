package com.salesmanagement.invoicing.api;

import java.math.BigDecimal;

/**
 * Per-product sales rollup published for FR-123 (fast / slow-moving product classification).
 *
 * <p><strong>Computed in one query (D1).</strong> A single {@code GROUP BY product_id} over the
 * invoice line items in the window returns one row per product sold. Reporting ranks these to pick
 * top-N (fast) and bottom-N (slow); N is a {@code systemconfig} key (D4), read via {@code ConfigFacade}.</p>
 *
 * <p><strong>Both units and revenue (design choice).</strong> The query returns {@code unitsSold}
 * <em>and</em> {@code revenue} even though D4 ranks by units. This costs nothing extra in the same
 * {@code GROUP BY} and lets the ranking switch to revenue later without a new query or a schema
 * change — the extensibility the module was scoped for.</p>
 *
 * <p><strong>Status filter (locked decision).</strong> {@code status IN (SENT, APPROVED)}; REJECTED
 * excluded, matching the other sales aggregates. Products that only ever appeared on DRAFT or
 * REJECTED invoices do not appear here.</p>
 *
 * @param productId cross-module id of the product ({@code inventory.products.id})
 * @param unitsSold total quantity sold across realised invoices in the window
 * @param revenue   sum of line subtotals ({@code NUMERIC(12,2)}, HALF_UP)
 */
public record ProductSalesAggregate(
        Long       productId,
        long       unitsSold,
        BigDecimal revenue
) {}
