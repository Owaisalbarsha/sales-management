package com.salesmanagement.inventory.internal.dto;

import com.salesmanagement.inventory.internal.enums.ProductStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for changing a product's lifecycle status.
 *
 * @param status the target status ({@code ACTIVE} or {@code DISCONTINUED}); required
 */
public record ChangeProductStatusRequest(

        @NotNull(message = "status is required")
        ProductStatus status
) {}
