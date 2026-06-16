package com.salesmanagement.vanops.internal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Request body for submitting a demand order.
 *
 * <p>Bean-validation catches the obvious shape errors (no rep, no lines, non-positive
 * quantities). Stateful checks — that the rep is a {@code SALES_REP}, every product exists
 * and is active, no duplicate products on the order — are done in the service.</p>
 *
 * @param representativeId the sales rep whose van this load is for; required
 * @param lines            at least one product+quantity; required
 */
public record CreateDemandOrderRequest(

        @NotNull(message = "representativeId is required")
        Long representativeId,

        @NotEmpty(message = "lines must not be empty")
        @Valid
        List<Line> lines
) {
    /**
     * @param productId    cross-module product reference; required
     * @param requestedQty desired quantity; required, ≥ 1
     */
    public record Line(

            @NotNull(message = "productId is required")
            Long productId,

            @NotNull(message = "requestedQty is required")
            @Min(value = 1, message = "requestedQty must be at least 1")
            Integer requestedQty
    ) {}
}