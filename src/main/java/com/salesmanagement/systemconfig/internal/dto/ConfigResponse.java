package com.salesmanagement.systemconfig.internal.dto;

import com.salesmanagement.systemconfig.internal.entity.SystemConfig;

import java.time.Instant;

/**
 * Admin-facing view of one config override. {@code updatedAt} is the ERD's LastUpdated, surfaced from
 * BaseEntity so the dashboard can show when a setting was last changed.
 */
public record ConfigResponse(
        String key,
        String value,
        String valueType,
        String description,
        Instant updatedAt
) {
    public static ConfigResponse from(SystemConfig e) {
        return new ConfigResponse(
                e.getConfigKey(),
                e.getConfigValue(),
                e.getValueType(),
                e.getDescription(),
                e.getUpdatedAt());
    }
}
