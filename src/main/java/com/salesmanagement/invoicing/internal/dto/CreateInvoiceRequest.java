package com.salesmanagement.invoicing.internal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request body for creating a DRAFT invoice (decision D4).
 *
 * <p>Bean validation catches shape errors (no customer, no lines, non-positive quantity,
 * negative discount). Stateful checks live in the service: customer exists, visit (if given)
 * belongs to this customer + rep, products exist, no duplicate products, discount within
 * {@code price*quantity}. Unit prices are <em>not</em> accepted here — the server captures the
 * current price from inventory (BR-9, D10); any client-sent price would be ignored, so it is
 * simply not part of the contract.</p>
 *
 * <p>{@code representativeId} is also <em>not</em> in the body — it is taken from the
 * authenticated principal (D18). {@code invoiceDate} is server-set online (D15).</p>
 *
 * @param customerId target customer; required
 * @param visitId    optional linked visit; {@code null} for an ad-hoc/off-route sale (D13)
 * @param clientUuid optional mobile idempotency key (D22); {@code null} for online creation
 * @param lines      at least one product+quantity(+discount); required
 */
public record CreateInvoiceRequest(

        @NotNull(message = "customerId is required")
        Long customerId,

        Long visitId,

        String clientUuid,

        @NotEmpty(message = "lines must not be empty")
        @Valid
        List<Line> lines
) {
    /**
     * One requested line. Price is captured server-side, not supplied here.
     *
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
