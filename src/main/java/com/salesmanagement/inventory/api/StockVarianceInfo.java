package com.salesmanagement.inventory.api;

/**
 * Immutable public projection of one product's stock variance within a finalized count
 * (FR-124), safe to pass across module boundaries. Consumed by the {@code reporting} module
 * to render the Stock Variance Report without touching inventory's tables or entities.
 *
 * <p>This is a read/report model: it carries the display fields ({@code productName},
 * {@code sku}) alongside the figures, so {@code reporting} renders a product table from a
 * single facade call rather than looping {@link InventoryFacade#getProductInfo(Long)} per row.
 * The internal {@code StockCountLine} entity and {@code ProductStatus} are never exposed.</p>
 *
 * <p><strong>Semantics.</strong> {@code recordedQuantity} is the system's warehouse figure as
 * snapshotted at the moment the count was finalized (the variance as-of instant), not the live
 * figure now — so the variance remains a true reflection of the gap at count time even after
 * later stock movements. {@code variance = countedQuantity - recordedQuantity}: positive means
 * more on the shelf than the system knew, negative points at shrinkage / theft / miscount.</p>
 *
 * @param productId       the counted product
 * @param productName     product name, for display
 * @param sku             stock keeping unit, for display
 * @param recordedQuantity system-recorded warehouse quantity snapshotted at finalize (>= 0)
 * @param countedQuantity  physically counted quantity (>= 0)
 * @param variance         {@code countedQuantity - recordedQuantity} (may be negative)
 */
public record StockVarianceInfo(
        Long   productId,
        String productName,
        String sku,
        int    recordedQuantity,
        int    countedQuantity,
        int    variance
) {}
