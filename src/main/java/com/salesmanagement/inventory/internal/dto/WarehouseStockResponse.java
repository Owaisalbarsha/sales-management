package com.salesmanagement.inventory.internal.dto;

import com.salesmanagement.inventory.internal.WarehouseStockItem;

import java.time.Instant;

/**
 * Response projection of a {@link WarehouseStockItem}.
 *
 * <p>Flattens the row plus a few derived fields the warehouse dashboard needs: the
 * product's name/SKU (so the client need not make a second call), the product's
 * {@code minStockLevel}, and a computed {@code lowStock} flag — {@code true} when the
 * on-hand quantity is below the reorder threshold (FR-32/FR-121).</p>
 *
 * <p>{@code lastUpdated} is the entity's inherited {@code updated_at} — the ERD's
 * {@code LastUpdated} field, surfaced under its ERD name.</p>
 *
 * @param id            warehouse-stock row id
 * @param productId     the product this stock is for
 * @param productName   product name (denormalised for display)
 * @param sku           product SKU (denormalised for display)
 * @param quantity      on-hand quantity in the warehouse
 * @param minStockLevel the product's reorder threshold
 * @param lowStock      {@code true} if {@code quantity < minStockLevel}
 * @param lastUpdated   when this stock row last changed (UTC)
 */
public record WarehouseStockResponse(
        Long    id,
        Long    productId,
        String  productName,
        String  sku,
        int     quantity,
        int     minStockLevel,
        boolean lowStock,
        Instant lastUpdated
) {
    /**
     * Maps a {@link WarehouseStockItem} to its response projection. Must be called
     * within the service transaction so the lazy {@code product} association resolves.
     *
     * @param w the entity to map; must not be {@code null}
     * @return the corresponding response DTO
     */
    public static WarehouseStockResponse from(WarehouseStockItem w) {
        int minStockLevel = w.getProduct().getMinStockLevel();
        return new WarehouseStockResponse(
                w.getId(),
                w.getProduct().getId(),
                w.getProduct().getName(),
                w.getProduct().getSku(),
                w.getQuantity(),
                minStockLevel,
                w.getQuantity() < minStockLevel,
                w.getUpdatedAt()
        );
    }
}
