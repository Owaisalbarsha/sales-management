package com.salesmanagement.invoicing.internal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request body for editing a DRAFT invoice's lines (decision D5). The whole line set is
 * replaced with the supplied list — the client sends the complete desired state, the service
 * clears existing lines and rebuilds, re-capturing each product's current price (D5a).
 *
 * <p>Replacing the whole set (rather than PATCH-ing individual lines) keeps the operation
 * simple and idempotent: the same request applied twice yields the same draft. Only permitted
 * while DRAFT; the service and entity both reject edits to a SENT/terminal invoice.</p>
 *
 * <p>Customer and visit are fixed at creation and not editable here — a different customer is a
 * different invoice. Only lines change.</p>
 *
 * @param lines the complete new set of lines; at least one, validated per element
 */
public record UpdateInvoiceRequest(

        @NotEmpty(message = "lines must not be empty")
        @Valid
        List<Line> lines
) {
    /**
     * @param productId cross-module product reference; required
     * @param quantity  units to sell; required, ≥ 1
     * @param discount  optional line-level fixed discount; ≥ 0 when present, defaults to 0
     */
    public record Line(

            @NotNull(message = "productId is required")
            Long productId,

            @NotNull(message = "quantity is required")
            @Min(value = 1, message = "quantity must be at least 1")
            Integer quantity,

            @DecimalMin(value = "0.0", message = "discount must be 0 or greater")
            BigDecimal discount
    ) {}
}
