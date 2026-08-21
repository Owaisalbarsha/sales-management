package com.salesmanagement.invoicing.internal.dto;

import java.util.List;

/**
 * Flat, display-ready view model for the customer invoice PDF. Everything the Thymeleaf
 * template needs, already formatted — the template does no computation and no money math.
 *
 * <p>All monetary strings are pre-formatted server-side from the invoice's <em>stored</em>
 * values (never recomputed), and all timestamps are pre-rendered from {@code Instant} with an
 * explicit offset. The template only places already-final strings.</p>
 */
public record InvoicePdfView(
        String            invoiceNumber,
        String            status,
        String            invoiceDate,
        String            customerName,
        String            representativeName,
        List<Line>        lines,
        String            totalAmount,
        String            currencySymbol,
        List<EpodProof>   epodProofs,
        String            generatedAt
) {
    /** One rendered line row. All amounts are pre-formatted strings. */
    public record Line(
            String productName,
            String sku,
            String quantity,
            String price,
            String discount,
            String subtotal
    ) {}

    /** One ePOD proof-of-delivery entry, rendered for the proof section. */
    public record EpodProof(
            String type,
            String capturedAt,
            String coordinates,   // "lat, lon" or a dash when absent
            String hashShort      // first 12 hex chars, enough to reference the full hash
    ) {}
}
