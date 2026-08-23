package com.salesmanagement.invoicing.api;

/**
 * How many invoices sit in one status over a window — the dashboard's invoice-status donut.
 *
 * <p><strong>Deliberately NOT status-filtered.</strong> Every other aggregate in this package counts
 * only realised sales; this one is the exception, and it is the whole point of it. The donut answers
 * "where is the paperwork stuck?", so DRAFT and REJECTED are the interesting slices. Counting them
 * here does not contradict the realised-sales rule — the rule governs <em>revenue</em>, and this
 * record carries no money at all, only a count.</p>
 *
 * <p><strong>Status as a stable string.</strong> {@code status} is the {@code InvoiceStatus} enum
 * <em>name</em>, not the enum: the internal type never crosses the module boundary (the same choice
 * {@link InvoiceSummary#status()} makes). The facade returns one row per declared status, zero-filled,
 * in the lifecycle order invoicing itself defines — a donut whose slices appear and vanish between
 * refreshes is a broken chart, and only invoicing knows the full status set.</p>
 *
 * @param status the invoice status as its enum name (e.g. {@code "SENT"})
 * @param count  number of invoices in that status within the window ({@code >= 0})
 */
public record InvoiceStatusCount(
        String status,
        long   count
) {}
