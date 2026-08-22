package com.salesmanagement.vanops.api;

import java.time.LocalDate;

/**
 * Per-business-day requested-vs-fulfilled rollup published for the dashboard's fill-rate trend — the
 * time-series counterpart of {@link FulfillmentAggregate}.
 *
 * <p><strong>Numerator and denominator, never the ratio.</strong> The facade returns the two summed
 * quantities and lets reporting divide, exactly as the per-product version does. That is not
 * politeness about layering: a fill-rate trend must be a <em>weighted</em> rate
 * ({@code sum(fulfilled) / sum(requested)}), and shipping a pre-divided per-day percentage would
 * invite the caller to average percentages across buckets — which silently gives a day with one
 * trivial order the same weight as a day with fifty. Keeping the components raw makes the correct
 * aggregation the only convenient one.</p>
 *
 * <p>Scope matches {@link FulfillmentAggregate}: only demand orders that reached {@code LOADED}, since
 * a still-SUBMITTED order has no final fulfilled figure. A day with no demand is absent from the
 * result — and, in the trend, is a day with no fill rate at all rather than a 0% one.</p>
 *
 * @param date           the business date of the bucket ({@code order_date})
 * @param totalRequested sum of {@code requested_qty} across loaded orders that day
 * @param totalFulfilled sum of {@code fulfilled_qty} across the same orders ({@code <= requested})
 */
public record DailyFulfillmentAggregate(
        LocalDate date,
        long      totalRequested,
        long      totalFulfilled
) {}
