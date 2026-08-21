package com.salesmanagement.vanops.internal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Request body for creating a return sheet (DRAFT). The sheet is not applied to stock
 * yet — that happens on the {@code /complete} endpoint, performed by the WAREHOUSE_MANAGER.
 *
 * @param representativeId the sales rep whose van is being returned; required
 * @param lines            at least one product+quantity; required
 */
public record CreateReturnSheetRequest(

        @NotNull(message = "representativeId is required")
        Long representativeId,

        @NotEmpty(message = "lines must not be empty")
        @Valid
        List<Line> lines
) {
    /**
     * @param productId cross-module product reference; required
     * @param quantity  quantity being returned; required, ≥ 1
     */
    public record Line(

            @NotNull(message = "productId is required")
            Long productId,

            @NotNull(message = "quantity is required")
            @Min(value = 1, message = "quantity must be at least 1")
            Integer quantity
    ) {}
}