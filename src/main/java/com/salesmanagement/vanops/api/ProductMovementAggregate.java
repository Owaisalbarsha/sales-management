package com.salesmanagement.vanops.api;

/**
 * Per-product stock-movement quantity published by the {@code vanops} module for the reporting
 * module's warehouse movement summary (FR-122).
 *
 * <p><strong>Two directions, two queries, one shape.</strong> The same record carries either the
 * quantity <em>loaded out</em> to vans (demand orders reaching {@code LOADED}) or the quantity
 * <em>returned in</em> from vans (return sheets reaching {@code COMPLETED}). Reporting calls both
 * {@link VanopsFacade#aggregateLoadedByProduct} and {@link VanopsFacade#aggregateReturnedByProduct}
 * for a window and joins them per product to present inbound vs outbound. The third movement
 * direction — sold — comes from {@code InvoiceFacade.aggregateProductSales}; reporting composes all
 * three. No movement-ledger table exists (nor is one needed at this scale): the movements are derived
 * from the terminal states of documents that already record them.</p>
 *
 * @param productId cross-module id of the product ({@code inventory.products.id})
 * @param quantity  total units moved in this direction over the window ({@code >= 0})
 */
public record ProductMovementAggregate(
        Long productId,
        long quantity
) {}
