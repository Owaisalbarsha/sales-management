package com.salesmanagement.inventory.api;

import java.time.Instant;

/**
 * Domain event: a product's warehouse quantity dropped below its configured
 * minimum stock level (FR-106). Consumed by the {@code notification} module to
 * alert the users who manage stock.
 *
 * <p><strong>Why this type lives in {@code inventory.api}.</strong> It is a fact
 * <em>about a product's stock</em>, produced by {@code inventory}. Consumers
 * depend on {@code inventory}, never the reverse, so declaring it here keeps the
 * edge one-directional and cycle-free — the same rule the routing events follow.</p>
 *
 * <p><strong>Where it is published (critical detail).</strong> {@code StockService}
 * publishes it <em>after</em> the atomic BR-4 deduction has committed, never inside
 * that guarded {@code UPDATE ... WHERE quantity &gt;= :qty} transaction. The
 * consuming listener is an {@code @ApplicationModuleListener} (async, own
 * transaction, post-commit), so notification delivery is fully decoupled from the
 * stock movement: a failed or slow notification can never roll back or delay a
 * sale. The event fires only on the transition across the threshold (quantity was
 * at/above minimum before the movement and is below it after), not on every
 * deduction while already low, so a rep making ten sales from a low product does
 * not generate ten alerts.</p>
 *
 * <p><strong>Recipients.</strong> The event carries product identity only;
 * {@code notification} decides who is told (warehouse/sales managers and admin —
 * the roles that act on stock). Keeping the recipient policy in {@code notification}
 * rather than baking it into the event keeps {@code inventory} ignorant of who
 * cares, which is the point of the event.</p>
 *
 * @param productId    the product that fell below its minimum
 * @param productName  the product's display name (so the message needs no lookup)
 * @param quantity     the on-hand quantity after the movement that crossed the threshold
 * @param minStockLevel the configured minimum that was breached
 * @param at           when the threshold was crossed (server UTC instant)
 */
public record LowStockDetectedEvent(
        Long    productId,
        String  productName,
        int     quantity,
        int     minStockLevel,
        Instant at
) {}
