package com.salesmanagement.territory.internal.dto;

import jakarta.validation.constraints.Size;

/**
 * Request body for partially updating an existing territory.
 *
 * <p>Both fields are optional individually, but at least one must be non-null —
 * a completely empty update is rejected at the service layer. Fields sent as
 * {@code null} are left unchanged on the entity.</p>
 *
 * @param name        new territory name; if provided, must be 2–100 characters
 * @param description new description; if provided, must not exceed 500 characters
 */
public record UpdateTerritoryRequest(

        @Size(min = 2, max = 100, message = "name must be between 2 and 100 characters")
        String name,

        @Size(max = 500, message = "description must not exceed 500 characters")
        String description
) {}