package com.salesmanagement.territory.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for creating a new territory (POST) or fully updating an existing
 * one (PUT). Both operations take the same fields, so they share one DTO.
 *
 * <p>Bean-validation constraints are enforced by {@code @Valid} at the controller
 * boundary; failures are converted to a 400 by {@code GlobalExceptionHandler}
 * before any service code runs.</p>
 *
 * @param name        the territory name; required, 2–100 characters, must be
 *                    unique (uniqueness is checked in the service, not here)
 * @param description optional description; up to 500 characters
 */
public record CreateTerritoryRequest(

        @NotBlank(message = "name must not be blank")
        @Size(min = 2, max = 100, message = "name must be between 2 and 100 characters")
        String name,

        @Size(max = 500, message = "description must not exceed 500 characters")
        String description
) {}