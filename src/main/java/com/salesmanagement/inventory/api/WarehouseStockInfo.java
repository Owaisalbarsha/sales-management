package com.salesmanagement.inventory.api;

/**
 * Public per-product warehouse stock read model for the reporting module's inventory reports
 * (FR-120 warehouse stock levels, FR-121 products below minimum).
 *
 * <p><strong>Why a dedicated DTO (D1).</strong> {@code ProductInfo} carries no quantity and no
 * minimum, so FR-121 (below-min) cannot be composed from existing facade calls without one call per
 * product — the N+1 trap. This record is the flat result of a single join query inside inventory:
 * product identity + current on-hand + minimum, with the below-min verdict precomputed so reporting
 * neither re-derives it nor needs the threshold.</p>
 *
 * <p><strong>Access.</strong> Warehouse stock is visible only to ADMIN / WAREHOUSE_MANAGER /
 * SALES_MANAGER (locked decision); that check lives on the reporting controller, not here.</p>
 *
 * @param productId     the product id ({@code inventory.products.id})
 * @param productName   product name, denormalised so reporting needn't resolve it separately
 * @param sku           product SKU
 * @param onHand        current warehouse quantity ({@code >= 0}; 0 if no stock row yet)
 * @param minStockLevel the product's reorder minimum
 * @param belowMin      {@code true} iff {@code onHand < minStockLevel} — precomputed for FR-121
 */
public record WarehouseStockInfo(
        Long    productId,
        String  productName,
        String  sku,
        int     onHand,
        int     minStockLevel,
        boolean belowMin
) {}
