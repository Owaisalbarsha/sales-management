package com.salesmanagement.inventory.internal.dto;

import com.salesmanagement.inventory.internal.entity.Product;
import com.salesmanagement.inventory.internal.enums.ProductStatus;
import com.salesmanagement.inventory.internal.entity.VanInventoryItem;

import java.math.BigDecimal;

/**
 * Response projection of a {@link VanInventoryItem} — one product line on a rep's van.
 *
 * <p>Carries a nested {@link ProductDetails} object with the full catalog information for
 * the product (price, unit of measure, barcode, etc.), so the mobile app can render a van
 * screen from this single call without a separate catalog lookup. The product is already
 * loaded on the entity (a {@code @ManyToOne}), so including it costs no extra query.</p>
 *
 * @param id                 van-inventory row id
 * @param representativeId   owning sales rep's user id
 * @param representativeName owning sales rep's display name (resolved via {@code UserFacade})
 * @param quantity           quantity currently on the van
 * @param product            full product details
 */
public record VanInventoryResponse(
        Long           id,
        Long           representativeId,
        String         representativeName,
        int            quantity,
        ProductDetails product
) {
    /**
     * Full product information nested in each van line — mirrors the fields of the catalog's
     * {@code ProductResponse} so the mobile app has everything it needs to display and price
     * the item.
     *
     * @param id            product id
     * @param name          product name
     * @param sku           stock keeping unit
     * @param barcode       scannable barcode (may be {@code null})
     * @param price         unit price
     * @param unitOfMeasure unit of measure
     * @param minStockLevel reorder threshold
     * @param status        lifecycle status (ACTIVE / DISCONTINUED)
     */
    public record ProductDetails(
            Long          id,
            String        name,
            String        sku,
            String        barcode,
            BigDecimal    price,
            String        unitOfMeasure,
            int           minStockLevel,
            ProductStatus status
    ) {
        static ProductDetails from(Product p) {
            return new ProductDetails(
                    p.getId(),
                    p.getName(),
                    p.getSku(),
                    p.getBarcode(),
                    p.getPrice(),
                    p.getUnitOfMeasure(),
                    p.getMinStockLevel(),
                    p.getStatus()
            );
        }
    }

    /**
     * Maps a {@link VanInventoryItem} to its response projection. Must be called within
     * the service transaction so the lazy {@code product} association resolves.
     *
     * @param v                  the entity to map; must not be {@code null}
     * @param representativeName resolved rep name (may be {@code null} if lookup failed)
     */
    public static VanInventoryResponse from(VanInventoryItem v, String representativeName) {
        return new VanInventoryResponse(
                v.getId(),
                v.getRepresentativeId(),
                representativeName,
                v.getQuantity(),
                ProductDetails.from(v.getProduct())
        );
    }
}