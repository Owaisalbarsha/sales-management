package com.salesmanagement.customer.internal.dto;

import com.salesmanagement.customer.internal.CustomerCategory;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request body for creating a customer (POST).
 *
 * <p>Bean-validation runs via {@code @Valid} at the controller boundary; failures
 * become a 400 through {@code GlobalExceptionHandler} before any service code
 * runs. {@code status} is not accepted here — a new customer is always
 * {@code ACTIVE}; use the activate/deactivate endpoints to change it.</p>
 *
 * <p>Note: the {@code territoryId} must reference an existing territory. That is a
 * cross-module check (resolved via {@code TerritoryFacade}) and is enforced in the
 * service, not by annotations.</p>
 *
 * @param territoryId owning territory id; required, must exist
 * @param name        customer name; required, 2–150 characters (not required to be unique)
 * @param address     optional postal address; up to 500 characters
 * @param phone       optional contact phone; up to 30 characters, lenient format
 * @param latitude    optional GPS latitude; if present, −90..90 with ≤6 decimals
 * @param longitude   optional GPS longitude; if present, −180..180 with ≤6 decimals
 * @param category    outlet classification; required
 */
public record CreateCustomerRequest(

        @NotNull(message = "territoryId is required")
        Long territoryId,

        @NotBlank(message = "name must not be blank")
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

        @NotNull(message = "category is required")
        CustomerCategory category
) {}