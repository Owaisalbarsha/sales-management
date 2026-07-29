package com.salesmanagement.reporting.internal.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Typed JSON response models for the sales and customer reports. These are what the {@code format=json}
 * path returns — structured, not stringified — so the Vue dashboard binds fields directly. The export
 * path ({@code xlsx}/{@code pdf}) converts the same data to a {@link com.salesmanagement.reporting.internal.support.ReportTable}.
 *
 * <p>Each row carries the resolved display name alongside the id, so the client neither re-resolves nor
 * shows a bare number. An id whose name could not be resolved (orphaned reference) gets a {@code "—"}
 * placeholder rather than dropping the row — a real sales figure must never vanish because a name lookup
 * missed.</p>
 */
public final class SalesReportDtos {

    private SalesReportDtos() {}

    /** Placeholder shown when an id cannot be resolved to a name, so a row is never silently dropped. */
    public static final String UNRESOLVED_NAME = "—";

    /**
     * FR-115: one row per rep — how many realised invoices and how much they sold in the window.
     *
     * @param representativeId the rep's id
     * @param representativeName resolved name, or {@code "—"} if unresolved
     * @param invoiceCount number of realised (SENT/APPROVED) invoices
     * @param totalSales sum of those invoice totals
     */
    public record RepSalesRow(
            Long       representativeId,
            String     representativeName,
            long       invoiceCount,
            BigDecimal totalSales
    ) {}

    /**
     * FR-116/117: one row per invoice — the tabular sales list, filterable by rep and customer.
     *
     * @param invoiceId the invoice id
     * @param invoiceDate business date
     * @param customerId customer id
     * @param customerName resolved customer name, or {@code "—"}
     * @param representativeId rep id
     * @param representativeName resolved rep name, or {@code "—"}
     * @param totalAmount invoice total
     * @param status invoice status name
     */
    public record InvoiceRow(
            Long       invoiceId,
            LocalDate  invoiceDate,
            Long       customerId,
            String     customerName,
            Long       representativeId,
            String     representativeName,
            BigDecimal totalAmount,
            String     status
    ) {}

    /**
     * FR-118/119: one row per customer — purchase count and spend in the window.
     *
     * @param customerId customer id
     * @param customerName resolved name, or {@code "—"}
     * @param invoiceCount number of realised invoices
     * @param totalSpent sum of those invoice totals
     */
    public record CustomerPurchaseRow(
            Long       customerId,
            String     customerName,
            long       invoiceCount,
            BigDecimal totalSpent
    ) {}

    /**
     * A report envelope carrying the resolved window alongside the rows, so a JSON consumer knows
     * exactly which half-open {@code [from, to)} the figures cover (the server may have defaulted it).
     */
    public record ReportEnvelope<T>(
            LocalDate from,
            LocalDate to,
            List<T>   rows
    ) {}
}
