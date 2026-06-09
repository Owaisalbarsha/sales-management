package com.salesmanagement.customer.internal.dto;

import com.salesmanagement.customer.internal.CustomerStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for changing a customer's lifecycle status.
 *
 * @param status the target status; required
 */
public record ChangeCustomerStatusRequest(

        @NotNull(message = "status is required")
        CustomerStatus status
) {}