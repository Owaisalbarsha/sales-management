package com.salesmanagement.routing.internal.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Request body for a manual reorder. The list must contain exactly the customers currently
 * on the route — no more, no fewer, no duplicates — in the desired visit order. Sequence
 * numbers are rewritten 1..n in this order, and {@code isOptimized} is set to false (a manual
 * order is not an auto-computed one).
 *
 * @param orderedCustomerIds the full set of current stops, in the new order
 */
public record ReorderRouteRequest(

        @NotEmpty(message = "orderedCustomerIds must not be empty")
        List<@NotNull Long> orderedCustomerIds
) {}
