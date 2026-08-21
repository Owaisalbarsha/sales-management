package com.salesmanagement.inventory.internal.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for receiving incoming warehouse stock — adds a positive delta to the
 * product's on-hand quantity ({@code POST /api/inventory/warehouse-stock/{productId}/receive}).
 *
 * <p>Unlike {@code SetWarehouseStockRequest} this is additive (a shipment arriving),
 * so the quantity must be at least 1. The addition is applied atomically.</p>
 *
 * @param quantity the number of units received; required, ≥ 1
 */
public record ReceiveStockRequest(

        @NotNull(message = "quantity is required")
        @Min(value = 1, message = "quantity must be at least 1")
        Integer quantity
) {}
