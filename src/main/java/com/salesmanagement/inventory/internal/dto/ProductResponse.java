package com.salesmanagement.inventory.internal.dto;

import com.salesmanagement.inventory.internal.entity.Product;
import com.salesmanagement.inventory.internal.enums.ProductStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response projection of a {@link Product}, returned by every product endpoint.
 *
 * <p>The outward-facing shape served to the Vue dashboard and the Flutter client.
 * Flat and stable, deliberately distinct from the entity so that schema changes do
 * not silently alter the API. {@code status} serialises to its enum name
 * (e.g. {@code "ACTIVE"}).</p>
 *
 * @param id            unique identifier
 * @param name          product name
 * @param sku           stock keeping unit
 * @param barcode       scannable barcode (may be {@code null})
 * @param price         unit price
 * @param unitOfMeasure unit of measure
 * @param minStockLevel reorder threshold
 * @param status        lifecycle status
 * @param createdAt     creation timestamp (UTC)
 * @param updatedAt     last-modified timestamp (UTC)
 */
public record ProductResponse(
        Long          id,
        String        name,
        String        sku,
        String        barcode,
        BigDecimal    price,
        String        unitOfMeasure,
        int           minStockLevel,
        ProductStatus status,
        Instant       createdAt,
        Instant       updatedAt
) {
    /**
     * Maps a {@link Product} entity to its response projection.
     *
     * @param p the entity to map; must not be {@code null}
     * @return the corresponding response DTO
     */
    public static ProductResponse from(Product p) {
        return new ProductResponse(
                p.getId(),
                p.getName(),
                p.getSku(),
                p.getBarcode(),
                p.getPrice(),
                p.getUnitOfMeasure(),
                p.getMinStockLevel(),
                p.getStatus(),
                p.getCreatedAt(),
                p.getUpdatedAt()
        );
    }
}
