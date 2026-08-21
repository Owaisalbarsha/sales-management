package com.salesmanagement.inventory.internal.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request body for creating a product (POST).
 *
 * <p>Bean-validation runs via {@code @Valid} at the controller boundary; failures
 * become a 400 through {@code GlobalExceptionHandler} before any service code runs.
 * {@code status} is not accepted here — a new product is always {@code ACTIVE}; use
 * the {@code PATCH /{id}/status} endpoint to change it.</p>
 *
 * <p>{@code sku}/{@code barcode} uniqueness is a stateful check resolved in the
 * service (clean 409), not by annotations.</p>
 *
 * @param name          product name; required, 2–150 characters
 * @param sku           stock keeping unit; required, up to 50 characters, must be unique
 * @param barcode       optional scannable barcode; up to 50 characters, unique when present
 * @param price         unit price; required, ≥ 0, up to 10 integer + 2 fraction digits
 * @param unitOfMeasure unit of measure (e.g. "BOX"); required, up to 30 characters
 * @param minStockLevel optional reorder threshold; if present must be ≥ 0, defaults to 0
 */
public record CreateProductRequest(

        @NotBlank(message = "name must not be blank")
        @Size(min = 2, max = 150, message = "name must be between 2 and 150 characters")
        String name,

        @NotBlank(message = "sku must not be blank")
        @Size(max = 50, message = "sku must not exceed 50 characters")
        String sku,

        @Size(max = 50, message = "barcode must not exceed 50 characters")
        String barcode,

        @NotNull(message = "price is required")
        @DecimalMin(value = "0.00", message = "price must not be negative")
        @Digits(integer = 10, fraction = 2, message = "price must have at most 10 integer and 2 fraction digits")
        BigDecimal price,

        @NotBlank(message = "unitOfMeasure must not be blank")
        @Size(max = 30, message = "unitOfMeasure must not exceed 30 characters")
        String unitOfMeasure,

        @Min(value = 0, message = "minStockLevel must be 0 or greater")
        Integer minStockLevel
) {}
