package com.salesmanagement.customer.internal.dto;

import com.salesmanagement.customer.internal.CustomerCategory;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request body for partially updating a customer (PUT).
 *
 * <p>Every field is optional individually, but the service rejects a wholly empty
 * payload ({@code EMPTY_UPDATE}, 400) via {@link #isEmpty()}. Fields sent as
 * {@code null} are left unchanged on the entity.</p>
 *
 * <p>{@code status} is deliberately absent: lifecycle changes go through the
 * dedicated {@code /activate} and {@code /deactivate} endpoints so they are
 * explicit and auditable rather than buried in a generic field update.</p>
 *
 * @param territoryId new owning territory id; if present, must reference an existing territory
 * @param name        new name; if present, 2–150 characters
 * @param address     new address; if present, up to 500 characters
 * @param phone       new phone; if present, lenient format up to 30 characters
 * @param latitude    new latitude; if present, −90..90 with ≤6 decimals
 * @param longitude   new longitude; if present, −180..180 with ≤6 decimals
 * @param category    new classification; if present
 */
public record UpdateCustomerRequest(

        Long territoryId,

        @Size(min = 2, max = 150, message = "name must be between 2 and 150 characters")
        String name,

        @Size(max = 500, message = "address must not exceed 500 characters")
        String address,

        @Size(max = 30, message = "phone must not exceed 30 characters")
        @Pattern(regexp = "^[+0-9()\\s-]{6,30}$",
                message = "phone may contain digits, spaces, +, -, () and must be 6–30 characters")
        String phone,

        @DecimalMin(value = "-90.0",  message = "latitude must be between -90 and 90")
        @DecimalMax(value = "90.0",   message = "latitude must be between -90 and 90")
        @Digits(integer = 3, fraction = 6, message = "latitude must have at most 6 decimal places")
        BigDecimal latitude,

        @DecimalMin(value = "-180.0", message = "longitude must be between -180 and 180")
        @DecimalMax(value = "180.0",  message = "longitude must be between -180 and 180")
        @Digits(integer = 3, fraction = 6, message = "longitude must have at most 6 decimal places")
        BigDecimal longitude,

        CustomerCategory category
) {
    /**
     * @return {@code true} if no updatable field was supplied — the service uses
     *         this to reject an empty PUT with a clear {@code EMPTY_UPDATE} error.
     */
    public boolean isEmpty() {
        return territoryId == null
                && name == null
                && address == null
                && phone == null
                && latitude == null
                && longitude == null
                && category == null;
    }
}