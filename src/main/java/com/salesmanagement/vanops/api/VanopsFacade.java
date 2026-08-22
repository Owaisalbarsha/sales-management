package com.salesmanagement.vanops.api;

import com.salesmanagement.vanops.internal.enums.DemandOrderStatus;
import com.salesmanagement.vanops.internal.enums.ReturnSheetStatus;
import com.salesmanagement.vanops.internal.repository.DemandOrderRepository;
import com.salesmanagement.vanops.internal.repository.ReturnSheetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Public API surface of the {@code vanops} module for cross-module <em>reads</em> — the only type the
 * {@code reporting} module imports from vanops. Mirrors {@code InvoiceFacade}: no business logic,
 * delegates straight to the repositories' aggregate projections.
 *
 * <p><strong>Why it exists.</strong> The warehouse movement summary (FR-122) and the fill-rate report
 * need what physically left the warehouse (demand orders reaching {@code LOADED}) and what came back
 * (return sheets reaching {@code COMPLETED}). That data is vanops' internal state; reporting may not
 * read vanops' tables, so vanops publishes it here as flat aggregates — one grouped query per
 * direction, never a per-document loop (D1).</p>
 *
 * <p><strong>Terminal-state filters are baked in.</strong> Only {@code LOADED} demand orders and
 * {@code COMPLETED} return sheets represent real stock movement; a SUBMITTED order or a DRAFT sheet
 * moved nothing. Reporting cannot pass a status — the internal enums never cross the boundary, and the
 * definition of "moved" belongs to vanops.</p>
 *
 * <p><strong>Date + snapshot semantics (D8/D9).</strong> Half-open {@code [from, to)} on the business
 * date ({@code order_date} / {@code return_date}, both {@code LocalDate}); all methods
 * {@code @Transactional(readOnly = true)}, best-effort snapshot, no cross-module lock.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VanopsFacade {

    /** Demand orders count as "loaded out" only once terminal in {@code LOADED}. */
    private static final Set<DemandOrderStatus> LOADED_ONLY =
            Set.of(DemandOrderStatus.LOADED);

    /** Return sheets count as "returned in" only once terminal in {@code COMPLETED}. */
    private static final Set<ReturnSheetStatus> COMPLETED_ONLY =
            Set.of(ReturnSheetStatus.COMPLETED);

    private final DemandOrderRepository demandOrderRepository;
    private final ReturnSheetRepository returnSheetRepository;

    /**
     * Per-product quantity loaded OUT to vans in the window (FR-122 outbound). Sums {@code fulfilledQty}
     * across {@code LOADED} demand orders.
     */
    public List<ProductMovementAggregate> aggregateLoadedByProduct(LocalDate from, LocalDate to) {
        return demandOrderRepository.aggregateLoadedByProduct(from, to, LOADED_ONLY);
    }

    /**
     * Per-product quantity returned IN from vans in the window (FR-122 inbound). Sums line
     * {@code quantity} across {@code COMPLETED} return sheets.
     */
    public List<ProductMovementAggregate> aggregateReturnedByProduct(LocalDate from, LocalDate to) {
        return returnSheetRepository.aggregateReturnedByProduct(from, to, COMPLETED_ONLY);
    }

    /**
     * Per-product requested vs fulfilled in the window (fill-rate report). Across {@code LOADED}
     * demand orders; reporting divides fulfilled by requested to get the rate.
     */
    public List<FulfillmentAggregate> aggregateFulfillment(LocalDate from, LocalDate to) {
        return demandOrderRepository.aggregateFulfillment(from, to, LOADED_ONLY);
    }

    // ── Daily (time-series) shapes for the inventory dashboard ────────────────

    /**
     * Per-DAY quantity loaded OUT to vans in the window — the outbound series of the movement chart.
     * Same LOADED-only scope as {@link #aggregateLoadedByProduct}, grouped by date instead of product.
     * Days with no loading are absent; the caller zero-fills for the chart.
     */
    public List<DailyMovementAggregate> aggregateLoadedByDate(LocalDate from, LocalDate to) {
        return demandOrderRepository.aggregateLoadedByDate(from, to, LOADED_ONLY);
    }

    /**
     * Per-DAY quantity returned IN from vans in the window — the inbound series of the movement chart.
     * Same COMPLETED-only scope as {@link #aggregateReturnedByProduct}, grouped by date.
     */
    public List<DailyMovementAggregate> aggregateReturnedByDate(LocalDate from, LocalDate to) {
        return returnSheetRepository.aggregateReturnedByDate(from, to, COMPLETED_ONLY);
    }

    /**
     * Per-DAY requested vs fulfilled in the window — the fill-rate trend. Raw numerator and
     * denominator per day, never a pre-divided percentage, so the caller can (and must) compute a
     * weighted rate per bucket rather than averaging per-day percentages.
     */
    public List<DailyFulfillmentAggregate> aggregateFulfillmentByDate(LocalDate from, LocalDate to) {
        return demandOrderRepository.aggregateFulfillmentByDate(from, to, LOADED_ONLY);
    }
}
