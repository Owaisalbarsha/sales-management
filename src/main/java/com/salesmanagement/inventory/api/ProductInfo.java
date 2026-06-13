package com.salesmanagement.inventory.api;

import java.math.BigDecimal;

/**
 * Immutable public projection of a {@code Product}, safe to pass across module
 * boundaries. The only product type other modules may hold.
 *
 * <p>Carries exactly what downstream modules need:</p>
 * <ul>
 *   <li>{@code invoicing} — {@code name} for the line description, {@code price} to
 *       capture the unit price at invoice time (BR-9: later price changes do not affect
 *       existing invoices), and {@code active} to reject lines for discontinued products;</li>
 *   <li>{@code restock} — {@code name}/{@code sku} for display and validation.</li>
 * </ul>
 *
 * <p>The internal {@code ProductStatus} enum is <em>not</em> exposed — status is surfaced
 * as a derived {@code active} flag (intent, not representation), mirroring how
 * {@code CustomerInfo} exposes {@code active}. {@code barcode} and {@code minStockLevel}
 * are omitted because no module needs them through the facade ({@code reporting} queries
 * the tables directly).</p>
 *
 * @param id            unique identifier
 * @param name          product name
 * @param sku           stock keeping unit
 * @param price         unit price
 * @param unitOfMeasure unit of measure
 * @param active        {@code true} if the product is ACTIVE (sellable)
 */
public record ProductInfo(
        Long       id,
        String     name,
        String     sku,
        BigDecimal price,
        String     unitOfMeasure,
        boolean    active
) {}
