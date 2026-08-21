package com.salesmanagement.invoicing.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for rejecting a SENT invoice (SENT → REJECTED, decision D2 / BR-3).
 *
 * <p>The reason is mandatory and non-blank — enforced here by {@code @NotBlank} as the first
 * line of defence, and again in the {@code Invoice.reject} domain method so the invariant holds
 * regardless of entry path (e.g. a future sync path). Approval needs no body, so there is no
 * corresponding approve request.</p>
 *
 * @param reason the non-blank rejection reason (trimmed and stored)
 */
public record RejectInvoiceRequest(

        @NotBlank(message = "rejection reason is required")
        @Size(max = 1000, message = "rejection reason must not exceed 1000 characters")
        String reason
) {}
