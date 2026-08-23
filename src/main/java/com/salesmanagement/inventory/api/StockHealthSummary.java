package com.salesmanagement.inventory.api;

/**
 * Warehouse stock health as three <strong>mutually exclusive</strong> buckets over every carried
 * product — the dashboard's stock-health donut.
 *
 * <p><strong>Why this record exists rather than arithmetic on the existing tiles.</strong> The
 * obvious composition, {@code healthy = totalSkus − belowMinimum − aging}, is wrong: a product can be
 * both below minimum and aging (slow-moving stock that has also been drawn down), so subtracting both
 * double-counts it and a donut built that way does not add up — sometimes to a negative slice. The
 * categories here are defined so they cannot overlap, evaluated in this order:</p>
 * <ol>
 *   <li>{@code outOfStock}   — {@code onHand == 0}</li>
 *   <li>{@code belowMinimum} — {@code onHand > 0 AND onHand < minStockLevel}</li>
 *   <li>{@code healthy}      — {@code onHand > 0 AND onHand >= minStockLevel}</li>
 * </ol>
 *
 * <p>The order matters at one real edge: a product whose minimum is {@code 0} and whose on-hand is
 * {@code 0} satisfies both "out of stock" and "at or above minimum". It is counted out-of-stock —
 * the reading an operator expects — and counted exactly once. The invariant
 * {@code outOfStock + belowMinimum + healthy == totalSkus} therefore holds for every possible data
 * state, which is what makes the donut arithmetically valid.</p>
 *
 * <p><strong>Aging is deliberately not a category here.</strong> Aging is orthogonal — it is about
 * time without movement, not about quantity — so it stays its own series in the dashboard and never
 * perturbs these totals.</p>
 *
 * <p>{@code totalSkus} counts every product, including ones the warehouse has never received (they
 * appear as {@code onHand = 0}), matching {@code findAllWarehouseStock()} and the existing
 * {@code totalSkus} dashboard tile.</p>
 *
 * @param totalSkus    every product carried
 * @param outOfStock   products with nothing on hand
 * @param belowMinimum products holding some stock but under their reorder minimum
 * @param healthy      products at or above their minimum with stock on hand
 */
public record StockHealthSummary(
        long totalSkus,
        long outOfStock,
        long belowMinimum,
        long healthy
) {}
