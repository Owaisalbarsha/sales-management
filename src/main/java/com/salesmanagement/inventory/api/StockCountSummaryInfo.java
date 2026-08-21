package com.salesmanagement.inventory.api;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Immutable public projection of a stock-count header — the picker row for the
 * {@code reporting} module, so a user can choose which stocktake's variance to view.
 *
 * <p>{@code status} is surfaced as a {@code String} ("DRAFT" / "FINALIZED") rather than the
 * internal {@code StockCountStatus} enum, keeping the internal type inside the module (the same
 * way {@link ProductInfo} exposes a derived {@code active} flag, not {@code ProductStatus}).
 * Variance is only defined for a FINALIZED count; {@code reporting} filters on {@code status}
 * before calling {@link InventoryFacade#getStockVariance(Long)}.</p>
 *
 * @param stockCountId  the count id (pass to {@code getStockVariance})
 * @param countedById   the user who owns the count
 * @param countDate     business date the count represents
 * @param status        "DRAFT" or "FINALIZED"
 * @param lineCount      number of product lines on the count
 * @param finalizedAt    instant the recorded snapshot was taken; {@code null} while DRAFT
 */
public record StockCountSummaryInfo(
        Long      stockCountId,
        Long      countedById,
        LocalDate countDate,
        String    status,
        int       lineCount,
        Instant   finalizedAt
) {}
