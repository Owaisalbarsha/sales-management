package com.salesmanagement.inventory.internal.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for setting the absolute on-hand warehouse quantity for a product
 * ({@code PUT /api/inventory/warehouse-stock/{productId}}).
 *
 * <p>Used for an initial count or a stock-take correction. {@code 0} is allowed
 * (a product can legitimately be set to zero on hand).</p>
 *
 * @param quantity the new absolute on-hand quantity; required, ≥ 0
 */
public record SetWarehouseStockRequest(

        @NotNull(message = "quantity is required")
        @Min(value = 0, message = "quantity must be 0 or greater")
        Integer quantity
) {}
