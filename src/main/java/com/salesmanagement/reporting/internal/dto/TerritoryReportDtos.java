package com.salesmanagement.reporting.internal.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Typed JSON response models for the territory reports. Structured JSON for the dashboard,
 * converted to a ReportTable on the export path.
 */
public final class TerritoryReportDtos {

    private TerritoryReportDtos() {}

    /** Sales rolled up to a territory: invoice count + summed revenue, ranked. Zero-sales territories still appear. */
    public record TerritorySalesRow(
            Long       territoryId,
            String     territoryName,
            long       invoiceCount,
            BigDecimal totalSales
    ) {}

    /** Active-customer count per territory (current snapshot). Zero-customer territories appear at zero. */
    public record TerritoryCustomersRow(
            Long   territoryId,
            String territoryName,
            long   customerCount
    ) {}

    public record TerritoryEnvelope<T>(
            List<T> rows
    ) {}
}
