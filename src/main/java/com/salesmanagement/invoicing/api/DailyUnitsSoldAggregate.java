package com.salesmanagement.invoicing.api;

import java.time.LocalDate;

/**
 * Per-business-day units-sold rollup published for the dashboard's inventory movement chart.
 *
 * <p>The "sold" component of warehouse movement over time. {@code vanops} supplies the loaded-out and
 * returned-in components with the same daily shape ({@code DailyMovementAggregate}); reporting merges
 * the three series by date. One {@code GROUP BY invoice_date} over the line items covers the whole
 * window (D1) — a movement chart must never cost one query per day.</p>
 *
 * <p><strong>Status filter (locked decision).</strong> {@code status IN (SENT, APPROVED)} — the same
 * realised-sales definition as {@link ProductSalesAggregate}, so the per-product and per-day views of
 * "sold" agree.</p>
 *
 * @param date      the business date of the bucket ({@code Invoice.invoiceDate})
 * @param unitsSold total quantity across realised invoice lines on that date
 */
public record DailyUnitsSoldAggregate(
        LocalDate date,
        long      unitsSold
) {}
