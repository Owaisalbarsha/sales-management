package com.salesmanagement.invoicing.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Per-business-day realised-sales rollup published for the dashboard's sales-trend chart.
 *
 * <p><strong>One query for a whole window (D1).</strong> The trend needs a point per day, which is
 * exactly the shape a {@code GROUP BY invoice_date} produces. Reporting asks once for the window and
 * receives one row per day that actually had realised sales — never one query per day, and never the
 * raw invoices summed in Java. Days with no sales are simply absent; the caller zero-fills them for
 * the chart (fabricating rows in the database would be a lie, leaving gaps in the chart would be a
 * different one).</p>
 *
 * <p><strong>Status filter (locked decision).</strong> {@code status IN (SENT, APPROVED)}, the same
 * realised-sales definition every other sales aggregate here uses — a trend line and a rep ranking
 * over the same window must add up to the same total.</p>
 *
 * @param date         the business date of the bucket ({@code Invoice.invoiceDate})
 * @param invoiceCount number of realised invoices on that date
 * @param totalSales   sum of their totals ({@code NUMERIC(12,2)}, HALF_UP)
 */
public record DailySalesAggregate(
        LocalDate  date,
        long       invoiceCount,
        BigDecimal totalSales
) {}
