package com.salesmanagement.vanops.api;

/**
 * Per-product fill-rate rollup published for the reporting module's warehouse fill-rate report
 * (the "could the warehouse meet demand?" metric added alongside FR-120–124).
 *
 * <p>Fill rate = {@code fulfilled / requested}. A product whose demand orders were routinely trimmed
 * (fulfilled &lt; requested) is a chronic-shortage signal — the single most operationally meaningful
 * warehouse metric, and derivable directly from the demand-order lines already stored. Reporting
 * computes the ratio and formats it; the facade returns the raw summed numerator and denominator so
 * the ratio math (and any rounding policy) lives in one place, on the reporting side.</p>
 *
 * <p>Scope: only demand orders that reached {@code LOADED} count — a still-SUBMITTED order has no
 * final fulfilled figure yet. Over a window, one row per product.</p>
 *
 * @param productId     cross-module id of the product
 * @param totalRequested sum of {@code requested_qty} across loaded orders in the window
 * @param totalFulfilled sum of {@code fulfilled_qty} across the same orders ({@code <= requested})
 */
public record FulfillmentAggregate(
        Long productId,
        long totalRequested,
        long totalFulfilled
) {}
