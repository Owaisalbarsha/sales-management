package com.salesmanagement.reporting.internal.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Chart-ready payloads for the two analytics dashboards. Companion to {@link DashboardDtos}, which
 * carries the headline KPI tiles: the tiles answer "what is the number right now", these answer "how
 * did it move, and who is driving it".
 *
 * <p><strong>Business data, not chart configuration.</strong> Every record here is a semantic
 * measurement — a date and an amount, an id and a name and a total. There is no {@code series},
 * {@code colors}, {@code x}/{@code y} or any other rendering vocabulary, and there never should be:
 * the moment the backend starts shipping chart config, the charting library becomes an API contract
 * and swapping it becomes a backend change. The frontend decides how a sales figure is drawn; the
 * backend only decides what it is.</p>
 *
 * <p><strong>No display strings.</strong> Statuses and granularities travel as stable identifiers
 * ({@code APPROVED}, {@code WEEK}), never as translated labels — localisation is the frontend's, and
 * a backend that emits Arabic labels cannot serve an English client. The only human-readable strings
 * are entity names, which are data, not UI copy.</p>
 *
 * <p><strong>Money and percentages are {@code BigDecimal}</strong> throughout, at the module's
 * existing 2dp (money) / 1dp (percent) HALF_UP conventions. A percentage whose denominator is zero is
 * either {@code 0} or {@code null} depending on which is the truthful answer — see
 * {@link FillRateTrendPoint}, the one case where the distinction carries meaning.</p>
 */
public final class DashboardAnalyticsDtos {

    private DashboardAnalyticsDtos() {}

    // ── Sales analytics ───────────────────────────────────────────────────────

    /**
     * The full sales analytics payload for one window.
     *
     * @param from        inclusive window start as resolved by the server (the caller may have omitted it)
     * @param to          EXCLUSIVE window end — the project's half-open {@code [from, to)} convention
     * @param granularity the trend bucket size actually used: {@code DAY}, {@code WEEK} or {@code MONTH}
     */
    public record SalesAnalytics(
            LocalDate                 from,
            LocalDate                 to,
            String                    granularity,
            SalesPeriodSummary        periodSummary,
            List<SalesTrendPoint>     salesTrend,
            List<RepRankRow>          topRepresentatives,
            List<TerritoryRankRow>    topTerritories,
            List<ProductRankRow>      topProducts,
            List<CustomerRankRow>     topCustomers,
            List<InvoiceStatusSlice>  invoiceStatusDistribution,
            RouteOutcomes             routeOutcomes
    ) {}

    /**
     * Window totals over realised (SENT / APPROVED) invoices only — the same definition the trend and
     * every ranking below use, so the summary and the charts agree.
     *
     * @param totalSales          sum of realised invoice totals
     * @param invoiceCount        number of realised invoices
     * @param averageInvoiceValue {@code totalSales / invoiceCount}, or {@code 0} when there are no
     *                            invoices (an average of nothing is not a number; zero is the tile
     *                            the dashboard can actually render)
     * @param uniqueCustomers     distinct customers with at least one realised invoice in the window
     */
    public record SalesPeriodSummary(
            BigDecimal totalSales,
            long       invoiceCount,
            BigDecimal averageInvoiceValue,
            long       uniqueCustomers
    ) {}

    /**
     * One bucket of the sales trend. Every bucket in the requested window is present, including
     * silent ones at zero — a chart that skips empty days draws a straight line across them and
     * quietly misrepresents a shutdown as steady trade.
     *
     * @param periodStart the bucket's start date (the day, the ISO week's Monday, or the 1st of the month)
     */
    public record SalesTrendPoint(
            LocalDate  periodStart,
            BigDecimal salesTotal,
            long       invoiceCount
    ) {}

    /**
     * A representative's realised sales in the window.
     *
     * @param sharePercent this rep's share of the window's total realised sales, 1dp; {@code 0} when
     *                     there were no sales at all
     */
    public record RepRankRow(
            Long       representativeId,
            String     representativeName,
            long       invoiceCount,
            BigDecimal totalSales,
            BigDecimal sharePercent
    ) {}

    /** A territory's realised sales in the window. {@code sharePercent} as in {@link RepRankRow}. */
    public record TerritoryRankRow(
            Long       territoryId,
            String     territoryName,
            long       invoiceCount,
            BigDecimal totalSales,
            BigDecimal sharePercent
    ) {}

    /**
     * A product's realised sales in the window.
     *
     * @param revenueSharePercent share of the window's total product revenue, 1dp
     */
    public record ProductRankRow(
            Long       productId,
            String     productName,
            long       unitsSold,
            BigDecimal revenue,
            BigDecimal revenueSharePercent
    ) {}

    /** A customer's realised spend in the window. */
    public record CustomerRankRow(
            Long       customerId,
            String     customerName,
            long       invoiceCount,
            BigDecimal totalSpent
    ) {}

    /**
     * One slice of the invoice-status donut. Unlike every money figure on this dashboard, this counts
     * invoices in ALL statuses — DRAFT and REJECTED included — because the question it answers is
     * about workflow, not revenue. Every status appears, zero-filled, in a fixed lifecycle order.
     *
     * @param status stable enum name, e.g. {@code DRAFT} — not a localised label
     */
    public record InvoiceStatusSlice(
            String status,
            long   count
    ) {}

    /**
     * Field execution across every route in the window, using the route report's existing visit
     * semantics.
     *
     * @param plannedStops      stops planned across all routes in the window
     * @param completed         visits that reached COMPLETED
     * @param missed            visits explicitly marked MISSED
     * @param inProgress        visits checked in but not yet checked out
     * @param notVisited        planned stops with no visit row at all ({@code >= 0}) — a stop that was
     *                          never reached and never marked
     * @param completionPercent {@code completed / plannedStops * 100}, 1dp; {@code 0} when nothing
     *                          was planned
     */
    public record RouteOutcomes(
            long       plannedStops,
            long       completed,
            long       missed,
            long       inProgress,
            long       notVisited,
            BigDecimal completionPercent
    ) {}

    // ── Inventory analytics ───────────────────────────────────────────────────

    /**
     * The full inventory analytics payload for one window.
     *
     * <p>Note the split of tenses: {@code stockHealth} and {@code topStockValueProducts} are
     * snapshots of the warehouse as it stands right now and ignore the window entirely, while the
     * trends and the movers cover {@code [from, to)}. Both are labelled as what they are rather than
     * pretending the snapshot is historical — the system keeps no stock-level history, and
     * back-filling one from movements would be a reconstruction, not a record.</p>
     */
    public record InventoryAnalytics(
            LocalDate                  from,
            LocalDate                  to,
            String                     granularity,
            StockHealth                stockHealth,
            List<MovementTrendPoint>   movementTrend,
            List<FillRateTrendPoint>   fillRateTrend,
            List<StockValueRow>        topStockValueProducts,
            List<FastMovingRow>        fastMovingProducts,
            List<AgingInventoryRow>    agingInventory
    ) {}

    /**
     * Current stock health in three mutually exclusive buckets, guaranteed to satisfy
     * {@code outOfStock + belowMinimum + healthy == totalSkus} — which is what makes it a valid donut.
     * Aging stock is intentionally absent: it overlaps these categories and lives in its own series.
     */
    public record StockHealth(
            long totalSkus,
            long outOfStock,
            long belowMinimum,
            long healthy
    ) {}

    /**
     * One bucket of the movement chart, as three independent raw components.
     *
     * <p><strong>No {@code net} field, deliberately.</strong> "Net" only means something once you fix
     * whose balance you are netting — the warehouse's or the van's — and the existing FR-122 movement
     * report already carries an ambiguity there between its documentation and its arithmetic. Rather
     * than propagate a number whose sign convention the reader has to guess, this exposes the three
     * measured quantities and lets the chart stack or subtract them explicitly.</p>
     *
     * @param loadedToVans     units that left the warehouse onto vans (loaded demand orders)
     * @param returnedFromVans units that came back from vans (completed return sheets)
     * @param unitsSold        units sold on realised invoices
     */
    public record MovementTrendPoint(
            LocalDate periodStart,
            long      loadedToVans,
            long      returnedFromVans,
            long      unitsSold
    ) {}

    /**
     * One bucket of the fill-rate trend, as a weighted rate over the bucket.
     *
     * @param fillRatePercent {@code sum(fulfilled) / sum(requested) * 100} across the bucket, 1dp —
     *        a weighted aggregate, never an average of per-product percentages, so one trivial order
     *        cannot outvote fifty large ones. {@code null} when {@code requested == 0}: a period with
     *        no demand has no fill rate, and reporting it as 0% would slander the warehouse for
     *        orders nobody placed. Clients must render null as a gap, not as zero.
     */
    public record FillRateTrendPoint(
            LocalDate  periodStart,
            long       requested,
            long       fulfilled,
            BigDecimal fillRatePercent
    ) {}

    /** A product and the value of the stock sitting on it right now ({@code onHand × unitPrice}). */
    public record StockValueRow(
            Long       productId,
            String     productName,
            String     sku,
            int        onHand,
            BigDecimal unitPrice,
            BigDecimal stockValue
    ) {}

    /** A product ranked by units sold in the window — the fast movers. */
    public record FastMovingRow(
            Long       productId,
            String     productName,
            long       unitsSold,
            BigDecimal revenue
    ) {}

    /**
     * A product sitting on stock with no outbound movement in the configured aging window. Straight
     * from the existing aging report, trimmed to a dashboard-sized top slice by on-hand.
     *
     * @param agingDays the threshold used, so the chart can label itself without a second call
     */
    public record AgingInventoryRow(
            Long   productId,
            String productName,
            String sku,
            int    onHand,
            int    agingDays
    ) {}
}
