package com.salesmanagement.systemconfig.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin request body to set (create or replace) a config override. The key itself is a path variable,
 * not part of the body, so an edit and a create use the same PUT shape.
 *
 * <p>Bean validation here catches the cheap, transport-level problems (blank, over-length). Semantic
 * validation — value_type is supported, value actually parses as that type — is the service's job,
 * because it is a domain rule, not a format rule.</p>
 */
public record UpsertConfigRequest(

        @NotBlank(message = "value is required")
        @Size(max = 255, message = "value must be at most 255 characters")
        String value,

        @NotBlank(message = "valueType is required")
        @Size(max = 20, message = "valueType must be at most 20 characters")
        String valueType,

        @Size(max = 500, message = "description must be at most 500 characters")
        String description
) {}
