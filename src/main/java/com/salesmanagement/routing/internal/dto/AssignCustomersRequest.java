package com.salesmanagement.routing.internal.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Request body for appending customers to a route. Each customer is validated (exists,
 * ACTIVE, in the route's territory) and appended after the current last stop. Appending
 * any customer resets the route's {@code isOptimized} flag to false.
 *
 * @param customerIds customers to add; at least one, no nulls
 */
public record AssignCustomersRequest(

        @NotEmpty(message = "customerIds must not be empty")
        List<@NotNull Long> customerIds
) {}
