package com.salesmanagement.territory.api;

/**
 * Immutable public projection of a Territory, safe to pass across module
 * boundaries. The {@code customer} module uses this to resolve a territory name
 * for display without importing the Territory entity.
 *
 * <p>Mirrors the identity module's {@code UserInfo} pattern: never expose the
 * entity or an internal DTO outside the module.</p>
 *
 * @param id          the territory's unique identifier
 * @param name        the territory name
 * @param description the territory description (may be {@code null})
 */
public record TerritoryInfo(
        Long   id,
        String name,
        String description
) {}





