package com.salesmanagement.inventory.internal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Request to replace the lines of a DRAFT stock count.
 *
 * <p>The supplied set is the desired state of the count: products present here are inserted or
 * updated (counted quantity set), products absent here are removed. The service applies this by
 * mutating existing line rows in place and only inserting/deleting the true delta — never
 * clear-and-rebuild — so no product is deleted and re-inserted in one flush (which the
 * {@code UNIQUE(stock_count_id, product_id)} constraint would reject on flush ordering).</p>
 */
public record UpdateStockCountLinesRequest(

        @NotNull(message = "lines is required")
        @Valid
        List<Line> lines
) {
    public record Line(
            @NotNull(message = "productId is required")
            Long productId,

            @Min(value = 0, message = "countedQuantity must be >= 0")
            int countedQuantity
    ) {}
}
