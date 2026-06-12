package com.salesmanagement.territory.internal.dto;

import com.salesmanagement.territory.internal.entity.Territory;

import java.time.Instant;

/**
 * Response projection of a {@link Territory}, returned by every territory endpoint.
 *
 * <p>This is the outward-facing shape served to the Vue dashboard and the mobile
 * client. It is a flat, stable contract — distinct from the {@code Territory}
 * entity so that schema changes do not silently alter the API.</p>
 *
 * @param id          the territory's unique identifier
 * @param name        the territory name
 * @param description the territory description (may be {@code null})
 * @param createdAt   when the territory was created (UTC)
 * @param updatedAt   when the territory was last modified (UTC)
 */
public record TerritoryResponse(
        Long    id,
        String  name,
        String  description,
        Instant createdAt,
        Instant updatedAt
) {
    /**
     * Maps a {@link Territory} entity to its response projection.
     *
     * @param t the entity to map; must not be {@code null}
     * @return the corresponding response DTO
     */
    public static TerritoryResponse from(Territory t) {
        return new TerritoryResponse(
                t.getId(),
                t.getName(),
                t.getDescription(),
                t.getCreatedAt(),
                t.getUpdatedAt()
        );
    }
}