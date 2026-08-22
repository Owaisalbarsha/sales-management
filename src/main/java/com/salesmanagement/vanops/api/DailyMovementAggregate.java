package com.salesmanagement.vanops.api;

import java.time.LocalDate;

/**
 * Per-business-day stock-movement quantity published for the dashboard's inventory movement chart —
 * the time-series counterpart of {@link ProductMovementAggregate}.
 *
 * <p><strong>Two directions, two queries, one shape</strong> (the same arrangement the per-product
 * record uses): the record carries either the quantity <em>loaded out</em> to vans (demand orders
 * reaching {@code LOADED}, grouped by {@code order_date}) or the quantity <em>returned in</em> from
 * vans (return sheets reaching {@code COMPLETED}, grouped by {@code return_date}). The third
 * direction — sold — comes from {@code InvoiceFacade.aggregateDailyUnitsSold} in the identical shape,
 * so reporting merges three flat date-keyed series without any per-day query.</p>
 *
 * <p>Days with no movement are absent rather than zero: the aggregate reports what happened, and the
 * chart's zero-filling is a presentation concern the caller handles in memory.</p>
 *
 * @param date     the business date of the bucket
 * @param quantity total units moved in this direction on that date ({@code >= 0})
 */
public record DailyMovementAggregate(
        LocalDate date,
        long      quantity
) {}
