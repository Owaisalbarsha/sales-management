package com.salesmanagement.customer.internal.dto;

import com.salesmanagement.customer.internal.Customer;
import com.salesmanagement.customer.internal.CustomerStatus;
import com.salesmanagement.customer.internal.CustomerCategory;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response projection of a {@link Customer}, returned by every customer endpoint.
 *
 * <p>The outward-facing shape served to the Vue dashboard and the Flutter client.
 * Flat and stable, deliberately distinct from the entity so that schema changes do
 * not silently alter the API. {@code category} and {@code status} serialise to
 * their enum names (e.g. {@code "RETAIL"}, {@code "ACTIVE"}).</p>
 *
 * @param id          unique identifier
 * @param territoryId owning territory id
 * @param name        customer name
 * @param address     postal address (may be {@code null})
 * @param phone       contact phone (may be {@code null})
 * @param latitude    GPS latitude (may be {@code null})
 * @param longitude   GPS longitude (may be {@code null})
 * @param category    outlet classification
 * @param status      lifecycle status
 * @param createdAt   creation timestamp (UTC)
 * @param updatedAt   last-modified timestamp (UTC)
 */
public record CustomerResponse(
        Long             id,
        Long             territoryId,
        String           territoryName,
        String           name,
        String           address,
        String           phone,
        BigDecimal       latitude,
        BigDecimal       longitude,
        CustomerCategory category,
        CustomerStatus   status,
        Instant          createdAt,
        Instant          updatedAt
) {
    /**
     * Maps a {@link Customer} entity to its response projection.
     *
     * @param c the entity to map; must not be {@code null}
     * @return the corresponding response DTO
     */
    public static CustomerResponse from(Customer c, String territoryName) {
        return new CustomerResponse(
                c.getId(),
                c.getTerritoryId(),
                territoryName,
                c.getName(),
                c.getAddress(),
                c.getPhone(),
                c.getLatitude(),
                c.getLongitude(),
                c.getCategory(),
                c.getStatus(),
                c.getCreatedAt(),
                c.getUpdatedAt()
        );
    }
}