package com.salesmanagement.inventory.internal.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request body for partially updating a product (PUT).
 *
 * <p>Every field is optional individually, but the service rejects a wholly empty
 * payload ({@code EMPTY_UPDATE}, 400) via {@link #isEmpty()}. Fields sent as
 * {@code null} are left unchanged on the entity.</p>
 *
 * <p>{@code status} is deliberately absent: lifecycle changes go through the
 * dedicated {@code PATCH /{id}/status} endpoint so they are explicit and auditable
 * rather than buried in a generic field update. {@code sku}/{@code barcode}
 * uniqueness is re-checked in the service only when the value actually changes.</p>
 *
 * @param name          new name; if present, 2–150 characters
 * @param sku           new SKU; if present, ≤ 50 characters and must remain unique
 * @param barcode       new barcode; if present, ≤ 50 characters and must remain unique
 * @param price         new price; if present, ≥ 0
 * @param unitOfMeasure new unit of measure; if present, ≤ 30 characters
 * @param minStockLevel new reorder threshold; if present, ≥ 0
 */
public record UpdateProductRequest(

        @Size(min = 2, max = 150, message = "name must be between 2 and 150 characters")
        String name,

        @Size(max = 50, message = "sku must not exceed 50 characters")
        String sku,

        @Size(max = 50, message = "barcode must not exceed 50 characters")
        String barcode,

        @DecimalMin(value = "0.00", message = "price must not be negative")
        @Digits(integer = 10, fraction = 2, message = "price must have at most 10 integer and 2 fraction digits")
        BigDecimal price,

        @Size(max = 30, message = "unitOfMeasure must not exceed 30 characters")
        String unitOfMeasure,

        @Min(value = 0, message = "minStockLevel must be 0 or greater")
        Integer minStockLevel
) {
    /**
     * @return {@code true} if no updatable field was supplied — the service uses this
     *         to reject an empty PUT with a clear {@code EMPTY_UPDATE} error.
     */
    public boolean isEmpty() {
        return name == null
                && sku == null
                && barcode == null
                && price == null
                && unitOfMeasure == null
                && minStockLevel == null;
    }
}
