package com.salesmanagement.reporting.internal.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Typed JSON response models for the inventory reports (FR-120/121/122/123 + aging + fill-rate).
 * Same convention as the sales DTOs: structured JSON for the dashboard, converted to a
 * {@code ReportTable} on the export path.
 */
public final class InventoryReportDtos {

    private InventoryReportDtos() {}

    /**
     * FR-120 / FR-121: a product's warehouse position. {@code belowMin} is precomputed upstream by the
     * inventory query.
     */
    public record StockLevelRow(
            Long    productId,
            String  productName,
            String  sku,
            int     onHand,
            int     minStockLevel,
            boolean belowMin
    ) {}

    /**
     * FR-122: net stock movement for one product over the window. Loaded out to vans, returned in from
     * vans, and sold — the three directions, plus the net (returned − loaded − sold, i.e. the effect on
     * warehouse on-hand). All three source figures come from different modules and are joined per
     * product in the service.
     */
    public record MovementRow(
            Long   productId,
            String productName,
            long   loadedToVans,
            long   returnedFromVans,
            long   sold,
            long   net
    ) {}

    /**
     * FR-123: a product's sales velocity and its fast/slow classification within the window.
     * {@code classification} is one of {@code FAST}, {@code SLOW}, or {@code MID}.
     */
    public record ProductMovementRow(
            Long       productId,
            String     productName,
            long       unitsSold,
            BigDecimal revenue,
            String     classification
    ) {}

    /**
     * Aging: a stocked product with no outbound movement in the last N days (window-based proxy).
     * Carries the on-hand it is sitting on and the threshold used, so the report explains itself.
     */
    public record AgingRow(
            Long   productId,
            String productName,
            String sku,
            int    onHand,
            int    agingDays
    ) {}

    /**
     * Fill-rate: how well the warehouse met demand for one product. {@code fillRatePercent} is
     * {@code fulfilled / requested * 100}, rounded to 1dp; {@code null} requested guards div-by-zero
     * (a product never requested has no fill rate and is omitted upstream).
     */
    public record FillRateRow(
            Long       productId,
            String     productName,
            long       requested,
            long       fulfilled,
            BigDecimal fillRatePercent
    ) {}

    /** Envelope carrying the resolved window (or the threshold, for aging) with the rows. */
    public record InventoryEnvelope<T>(
            List<T> rows
    ) {}
}
