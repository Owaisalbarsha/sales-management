package com.salesmanagement.inventory.api;

import java.math.BigDecimal;

/**
 * A product's warehouse position valued at its current unit price — the dashboard's "where is the
 * money sitting?" ranking.
 *
 * <p><strong>Computed and ranked in the database (D1).</strong> {@code stockValue} is
 * {@code onHand × unitPrice} evaluated in SQL, the ordering is applied in SQL, and the top-N cut is a
 * {@code LIMIT}. The alternative — reading every stock row, resolving prices product by product, then
 * sorting in Java — is exactly the per-product lookup loop the facades exist to prevent, and it grows
 * with the catalogue while this query returns at most N rows.</p>
 *
 * <p><strong>Current price, not historical cost.</strong> This is a valuation at today's selling
 * price, which is what a stock-value tile means here; it is not a COGS figure and does not attempt to
 * reconstruct what the stock was bought for (the system stores no purchase cost). Products with
 * nothing on hand are excluded — a zero-value bar carries no information.</p>
 *
 * @param productId   the product id
 * @param productName product name, denormalised so reporting needs no second lookup
 * @param sku         product SKU
 * @param onHand      current warehouse quantity ({@code > 0})
 * @param unitPrice   the product's current unit price
 * @param stockValue  {@code onHand × unitPrice}
 */
public record ProductStockValueInfo(
        Long       productId,
        String     productName,
        String     sku,
        int        onHand,
        BigDecimal unitPrice,
        BigDecimal stockValue
) {}
