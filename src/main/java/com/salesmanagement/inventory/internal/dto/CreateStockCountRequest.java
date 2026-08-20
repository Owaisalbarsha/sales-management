package com.salesmanagement.inventory.internal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;

/**
 * Request to open a new (DRAFT) stock count.
 *
 * <p>{@code countDate} is optional — the service defaults it to today. {@code lines} may be
 * empty: a manager can open an empty draft and add counts as they walk the warehouse (via
 * {@link UpdateStockCountLinesRequest}). Counted quantity may be 0 (counted, none present).</p>
 */
public record CreateStockCountRequest(

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate countDate,

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
