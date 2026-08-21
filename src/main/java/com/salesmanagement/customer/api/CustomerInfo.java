package com.salesmanagement.customer.api;

import java.math.BigDecimal;

/**
 * Immutable public projection of a {@code Customer}, safe to pass across module
 * boundaries. The only customer type other modules may hold.
 *
 * <p>Carries exactly what downstream modules need:</p>
 * <ul>
 *   <li>{@code routing} — {@code latitude}/{@code longitude} for route
 *       optimisation (FR-51/52), plus {@code name}/{@code address} for display;</li>
 *   <li>{@code visit} — {@code name}, {@code address}, and {@code phone}
 *       (the ERD derives a visit's phone from the customer, not a stored copy);</li>
 *   <li>{@code invoicing} — {@code name} for the invoice header and {@code active}
 *       to enforce the disabled-customer rule (FR-95).</li>
 * </ul>
 *
 * <p>Note the design choices that keep the boundary clean: the internal
 * {@code CustomerCategory} and {@code CustomerStatus} enums are <em>not</em>
 * exposed. Status is surfaced as a derived {@code active} flag — intent, not
 * representation — and category is omitted entirely because no module needs it
 * through the facade ({@code reporting} queries the table directly).</p>
 *
 * @param id          unique identifier
 * @param name        customer name
 * @param territoryId owning territory id
 * @param address     postal address (may be {@code null})
 * @param phone       contact phone (may be {@code null})
 * @param latitude    GPS latitude (may be {@code null})
 * @param longitude   GPS longitude (may be {@code null})
 * @param active      {@code true} if the customer is ACTIVE (may be invoiced)
 */
public record CustomerInfo(
        Long       id,
        String     name,
        Long       territoryId,
        String     address,
        String     phone,
        BigDecimal latitude,
        BigDecimal longitude,
        boolean    active
) {}