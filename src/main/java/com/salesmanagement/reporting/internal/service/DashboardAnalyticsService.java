package com.salesmanagement.reporting.internal.service;

import com.salesmanagement.customer.api.CustomerFacade;
import com.salesmanagement.identity.api.UserFacade;
import com.salesmanagement.inventory.api.InventoryFacade;
import com.salesmanagement.inventory.api.ProductStockValueInfo;
import com.salesmanagement.inventory.api.StockHealthSummary;
import com.salesmanagement.invoicing.api.CustomerPurchaseAggregate;
import com.salesmanagement.invoicing.api.DailySalesAggregate;
import com.salesmanagement.invoicing.api.DailyUnitsSoldAggregate;
import com.salesmanagement.invoicing.api.InvoiceFacade;
import com.salesmanagement.invoicing.api.InvoiceStatusCount;
import com.salesmanagement.invoicing.api.ProductSalesAggregate;
import com.salesmanagement.invoicing.api.RepSalesAggregate;
import com.salesmanagement.reporting.internal.dto.DashboardAnalyticsDtos.AgingInventoryRow;
import com.salesmanagement.reporting.internal.dto.DashboardAnalyticsDtos.CustomerRankRow;
import com.salesmanagement.reporting.internal.dto.DashboardAnalyticsDtos.FastMovingRow;
import com.salesmanagement.reporting.internal.dto.DashboardAnalyticsDtos.FillRateTrendPoint;
import com.salesmanagement.reporting.internal.dto.DashboardAnalyticsDtos.InventoryAnalytics;
import com.salesmanagement.reporting.internal.dto.DashboardAnalyticsDtos.InvoiceStatusSlice;
import com.salesmanagement.reporting.internal.dto.DashboardAnalyticsDtos.MovementTrendPoint;
import com.salesmanagement.reporting.internal.dto.DashboardAnalyticsDtos.ProductRankRow;
import com.salesmanagement.reporting.internal.dto.DashboardAnalyticsDtos.RepRankRow;
import com.salesmanagement.reporting.internal.dto.DashboardAnalyticsDtos.SalesAnalytics;
import com.salesmanagement.reporting.internal.dto.DashboardAnalyticsDtos.SalesPeriodSummary;
import com.salesmanagement.reporting.internal.dto.DashboardAnalyticsDtos.SalesTrendPoint;
import com.salesmanagement.reporting.internal.dto.DashboardAnalyticsDtos.StockHealth;
import com.salesmanagement.reporting.internal.dto.DashboardAnalyticsDtos.StockValueRow;
import com.salesmanagement.reporting.internal.dto.DashboardAnalyticsDtos.TerritoryRankRow;
import com.salesmanagement.reporting.internal.dto.InventoryReportDtos;
import com.salesmanagement.reporting.internal.dto.TerritoryReportDtos.TerritorySalesRow;
import com.salesmanagement.reporting.internal.support.DateRangeResolver.DateRange;
import com.salesmanagement.reporting.internal.support.Granularity;
import com.salesmanagement.vanops.api.DailyFulfillmentAggregate;
import com.salesmanagement.vanops.api.DailyMovementAggregate;
import com.salesmanagement.vanops.api.VanopsFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the two analytics payloads behind the dashboard charts. Sibling of {@link DashboardService},
 * which builds the KPI tiles: same window semantics, same facades, different question — tiles are
 * "what is it now", these are "how did it move and who moved it".
 *
 * <h2>The shape of every calculation here</h2>
 * Each chart is one grouped read from the module that owns the data, folded in memory. Nothing in
 * this class loops over dates, products, customers, territories or reps issuing queries: the query
 * count is a function of how many <em>sections</em> the payload has, not of how much data is in them.
 * Concretely, sales analytics costs a fixed handful of reads for a one-day window and the same
 * handful for a one-year window.
 *
 * <p>The two trends are built from DAILY aggregates and bucketed to DAY / WEEK / MONTH by
 * {@link Granularity}. That is one query per series regardless of granularity, and one definition of
 * where a week starts — rather than three SQL variants of every aggregate across three modules.</p>
 *
 * <h2>Realised sales</h2>
 * Every money figure counts only realised invoices ({@code SENT}, {@code APPROVED}) — not because
 * this class filters them, but because the invoicing facade's sales aggregates bake that policy in and
 * cannot be asked for anything else. The one deliberate exception is the status distribution, which
 * counts invoices in all four statuses because it is a workflow chart, not a revenue chart.
 *
 * <h2>Zero-filling</h2>
 * Trends return every bucket in the requested window, silent ones at zero. This is presentation
 * repair done in memory over real aggregates — no fabricated rows reach the database, and no
 * fabricated <em>measurements</em> reach the client: a zero bucket asserts "nothing happened", which
 * is exactly what the absence of rows means. The one place zero would be a lie is fill rate, where no
 * demand is reported as {@code null} rather than 0%.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardAnalyticsService {

    /** Placeholder for an id whose name could not be resolved — a real figure never vanishes over a name. */
    private static final String UNRESOLVED_NAME = "—";

    private static final int MONEY_SCALE = 2;
    private static final int PERCENT_SCALE = 1;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final InvoiceFacade invoiceFacade;
    private final InventoryFacade inventoryFacade;
    private final VanopsFacade vanopsFacade;
    private final UserFacade userFacade;
    private final CustomerFacade customerFacade;
    private final TerritoryReportService territoryReportService;
    private final RouteReportService routeReportService;
    private final InventoryReportService inventoryReportService;

    // ══ Sales analytics ═══════════════════════════════════════════════════════

    /**
     * Everything the sales dashboard's charts need for one window.
     *
     * @param range       validated half-open {@code [from, to)} window
     * @param granularity trend bucket size
     * @param top         how many rows each ranking returns (already validated by the controller)
     */
    public SalesAnalytics salesAnalytics(DateRange range, Granularity granularity, int top) {
        LocalDate from = range.from();
        LocalDate to = range.to();

        // ── one grouped read per section ──
        List<DailySalesAggregate> daily = invoiceFacade.aggregateDailySales(from, to);
        List<RepSalesAggregate> byRep = invoiceFacade.aggregateByRep(from, to);
        List<CustomerPurchaseAggregate> byCustomer = invoiceFacade.aggregateByCustomer(from, to);
        List<ProductSalesAggregate> byProduct = invoiceFacade.aggregateProductSales(from, to);
        List<InvoiceStatusCount> statusCounts = invoiceFacade.countByStatus(from, to);
        List<TerritorySalesRow> byTerritory = territoryReportService.salesByTerritory(range);

        // ── period summary, derived from the same daily rows the trend uses ──
        // Summing the trend rather than issuing a separate total query is not just thrift: it makes
        // the headline number and the chart provably the same figure, so they can never disagree.
        BigDecimal totalSales = daily.stream()
                .map(DailySalesAggregate::totalSales)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        long invoiceCount = daily.stream().mapToLong(DailySalesAggregate::invoiceCount).sum();
        BigDecimal averageInvoiceValue = invoiceCount == 0
                ? BigDecimal.ZERO.setScale(MONEY_SCALE)
                : totalSales.divide(BigDecimal.valueOf(invoiceCount), MONEY_SCALE, RoundingMode.HALF_UP);
        SalesPeriodSummary summary = new SalesPeriodSummary(
                totalSales, invoiceCount, averageInvoiceValue, byCustomer.size());

        return new SalesAnalytics(
                from, to, granularity.name(),
                summary,
                salesTrend(daily, granularity, from, to),
                topRepresentatives(byRep, totalSales, top),
                topTerritories(byTerritory, totalSales, top),
                topProducts(byProduct, top),
                topCustomers(byCustomer, top),
                statusCounts.stream()
                        .map(c -> new InvoiceStatusSlice(c.status(), c.count()))
                        .toList(),
                routeReportService.routeOutcomes(range, null));
    }

    /** Daily aggregates folded into the requested buckets and zero-filled across the window. */
    private List<SalesTrendPoint> salesTrend(List<DailySalesAggregate> daily, Granularity g,
                                             LocalDate from, LocalDate to) {
        Map<LocalDate, BigDecimal> sales = new LinkedHashMap<>();
        Map<LocalDate, Long> counts = new LinkedHashMap<>();
        for (DailySalesAggregate d : daily) {
            LocalDate bucket = g.bucketStart(d.date());
            sales.merge(bucket, nz(d.totalSales()), BigDecimal::add);
            counts.merge(bucket, d.invoiceCount(), Long::sum);
        }
        return g.buckets(from, to).stream()
                .map(b -> new SalesTrendPoint(
                        b,
                        sales.getOrDefault(b, BigDecimal.ZERO).setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                        counts.getOrDefault(b, 0L)))
                .toList();
    }

    /**
     * Top reps by realised sales. Names come from ONE batch call over the ranked ids — and only over
     * the ids that survive the top-N cut, so the batch is at most {@code top} entries wide however
     * many reps sold in the window.
     */
    private List<RepRankRow> topRepresentatives(List<RepSalesAggregate> byRep,
                                                BigDecimal totalSales, int top) {
        List<RepSalesAggregate> ranked = byRep.stream()
                .filter(a -> a.representativeId() != null)
                .sorted(Comparator.comparing((RepSalesAggregate a) -> nz(a.totalSales())).reversed()
                        .thenComparing(RepSalesAggregate::representativeId))
                .limit(top)
                .toList();

        Map<Long, String> names = safeNames(() -> userFacade.getNamesByIds(
                ranked.stream().map(RepSalesAggregate::representativeId).collect(Collectors.toSet())));

        return ranked.stream()
                .map(a -> new RepRankRow(
                        a.representativeId(),
                        names.getOrDefault(a.representativeId(), UNRESOLVED_NAME),
                        a.invoiceCount(),
                        nz(a.totalSales()).setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                        share(a.totalSales(), totalSales)))
                .toList();
    }

    /**
     * Top territories by realised sales, straight off the existing territory report (which already
     * owns the customer→territory regrouping) — re-ranked here only to apply the deterministic
     * tie-break and the top-N cut.
     *
     * <p>Territories with no sales are dropped rather than padded to {@code top}: the existing report
     * zero-fills every territory so a silent one stays visible <em>there</em>, but a "top territories"
     * bar chart listing zero-height bars communicates nothing.</p>
     */
    private List<TerritoryRankRow> topTerritories(List<TerritorySalesRow> rows,
                                                  BigDecimal totalSales, int top) {
        return rows.stream()
                .filter(r -> nz(r.totalSales()).signum() > 0)
                .sorted(Comparator.comparing((TerritorySalesRow r) -> nz(r.totalSales())).reversed()
                        .thenComparing(TerritorySalesRow::territoryId))
                .limit(top)
                .map(r -> new TerritoryRankRow(
                        r.territoryId(), r.territoryName(), r.invoiceCount(),
                        nz(r.totalSales()).setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                        share(r.totalSales(), totalSales)))
                .toList();
    }

    /**
     * Top products by revenue. The share denominator is total <em>product</em> revenue (the sum of
     * line subtotals), not the invoice-total sum used elsewhere — the two differ whenever an invoice
     * carries a discount, and dividing by the wrong one would produce shares that do not sum to 100.
     */
    private List<ProductRankRow> topProducts(List<ProductSalesAggregate> byProduct, int top) {
        BigDecimal totalRevenue = byProduct.stream()
                .map(a -> nz(a.revenue()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<ProductSalesAggregate> ranked = byProduct.stream()
                .filter(a -> a.productId() != null)
                .sorted(Comparator.comparing((ProductSalesAggregate a) -> nz(a.revenue())).reversed()
                        .thenComparing(ProductSalesAggregate::productId))
                .limit(top)
                .toList();

        Map<Long, String> names = safeNames(() -> inventoryFacade.getNamesByIds(
                ranked.stream().map(ProductSalesAggregate::productId).collect(Collectors.toSet())));

        return ranked.stream()
                .map(a -> new ProductRankRow(
                        a.productId(),
                        names.getOrDefault(a.productId(), UNRESOLVED_NAME),
                        a.unitsSold(),
                        nz(a.revenue()).setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                        share(a.revenue(), totalRevenue)))
                .toList();
    }

    /** Top customers by realised spend; names in one batch over the cut ids. */
    private List<CustomerRankRow> topCustomers(List<CustomerPurchaseAggregate> byCustomer, int top) {
        List<CustomerPurchaseAggregate> ranked = byCustomer.stream()
                .filter(a -> a.customerId() != null)
                .sorted(Comparator.comparing((CustomerPurchaseAggregate a) -> nz(a.totalSpent())).reversed()
                        .thenComparing(CustomerPurchaseAggregate::customerId))
                .limit(top)
                .toList();

        Map<Long, String> names = safeNames(() -> customerFacade.getNamesByIds(
                ranked.stream().map(CustomerPurchaseAggregate::customerId).collect(Collectors.toSet())));

        return ranked.stream()
                .map(a -> new CustomerRankRow(
                        a.customerId(),
                        names.getOrDefault(a.customerId(), UNRESOLVED_NAME),
                        a.invoiceCount(),
                        nz(a.totalSpent()).setScale(MONEY_SCALE, RoundingMode.HALF_UP)))
                .toList();
    }

    // ══ Inventory analytics ═══════════════════════════════════════════════════

    /**
     * Everything the inventory dashboard's charts need.
     *
     * <p>Mixed tenses by design: stock health and stock value are snapshots of the warehouse as it
     * stands, the trends and the movers cover the window. The system stores no stock-level history, so
     * a "stock health over time" chart could only be reconstructed from movements — a guess dressed as
     * a record. The snapshot is labelled as a snapshot instead.</p>
     */
    public InventoryAnalytics inventoryAnalytics(DateRange range, Granularity granularity, int top) {
        LocalDate from = range.from();
        LocalDate to = range.to();

        StockHealthSummary health = inventoryFacade.getStockHealth();

        List<DailyMovementAggregate> loaded = vanopsFacade.aggregateLoadedByDate(from, to);
        List<DailyMovementAggregate> returned = vanopsFacade.aggregateReturnedByDate(from, to);
        List<DailyUnitsSoldAggregate> sold = invoiceFacade.aggregateDailyUnitsSold(from, to);
        List<DailyFulfillmentAggregate> fulfillment = vanopsFacade.aggregateFulfillmentByDate(from, to);
        List<ProductSalesAggregate> productSales = invoiceFacade.aggregateProductSales(from, to);
        List<ProductStockValueInfo> stockValue = inventoryFacade.findTopStockValueProducts(top);

        return new InventoryAnalytics(
                from, to, granularity.name(),
                new StockHealth(health.totalSkus(), health.outOfStock(),
                        health.belowMinimum(), health.healthy()),
                movementTrend(loaded, returned, sold, granularity, from, to),
                fillRateTrend(fulfillment, granularity, from, to),
                stockValue.stream()
                        .map(p -> new StockValueRow(
                                p.productId(), p.productName(), p.sku(), p.onHand(),
                                nz(p.unitPrice()).setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                                nz(p.stockValue()).setScale(MONEY_SCALE, RoundingMode.HALF_UP)))
                        .toList(),
                fastMovingProducts(productSales, top),
                agingInventory(top));
    }

    /**
     * The three movement components merged onto one bucket axis and zero-filled. Each component is
     * carried through raw — see the DTO for why no {@code net} is derived here.
     */
    private List<MovementTrendPoint> movementTrend(List<DailyMovementAggregate> loaded,
                                                   List<DailyMovementAggregate> returned,
                                                   List<DailyUnitsSoldAggregate> sold,
                                                   Granularity g, LocalDate from, LocalDate to) {
        Map<LocalDate, Long> loadedByBucket = bucketSum(
                loaded, DailyMovementAggregate::date, DailyMovementAggregate::quantity, g);
        Map<LocalDate, Long> returnedByBucket = bucketSum(
                returned, DailyMovementAggregate::date, DailyMovementAggregate::quantity, g);
        Map<LocalDate, Long> soldByBucket = bucketSum(
                sold, DailyUnitsSoldAggregate::date, DailyUnitsSoldAggregate::unitsSold, g);

        return g.buckets(from, to).stream()
                .map(b -> new MovementTrendPoint(
                        b,
                        loadedByBucket.getOrDefault(b, 0L),
                        returnedByBucket.getOrDefault(b, 0L),
                        soldByBucket.getOrDefault(b, 0L)))
                .toList();
    }

    /**
     * Fill rate per bucket as a WEIGHTED rate: the requested and fulfilled quantities are summed
     * across the bucket first, then divided once. Averaging the days' individual percentages would
     * give a day with a single one-unit order the same weight as a day with fifty full pallets, which
     * is how a fill-rate chart ends up telling a comfortable lie.
     *
     * <p>A bucket with no demand gets {@code null}, not {@code 0} — "nobody asked" and "we failed
     * everyone who asked" are opposite findings and must not render as the same point.</p>
     */
    private List<FillRateTrendPoint> fillRateTrend(List<DailyFulfillmentAggregate> fulfillment,
                                                   Granularity g, LocalDate from, LocalDate to) {
        Map<LocalDate, long[]> byBucket = new LinkedHashMap<>(); // bucket -> [requested, fulfilled]
        for (DailyFulfillmentAggregate f : fulfillment) {
            long[] slot = byBucket.computeIfAbsent(g.bucketStart(f.date()), k -> new long[2]);
            slot[0] += f.totalRequested();
            slot[1] += f.totalFulfilled();
        }

        return g.buckets(from, to).stream()
                .map(b -> {
                    long[] slot = byBucket.getOrDefault(b, new long[2]);
                    BigDecimal percent = slot[0] == 0
                            ? null
                            : BigDecimal.valueOf(slot[1])
                                    .multiply(HUNDRED)
                                    .divide(BigDecimal.valueOf(slot[0]), PERCENT_SCALE, RoundingMode.HALF_UP);
                    return new FillRateTrendPoint(b, slot[0], slot[1], percent);
                })
                .toList();
    }

    /** Top products by units sold in the window; names in one batch over the cut ids. */
    private List<FastMovingRow> fastMovingProducts(List<ProductSalesAggregate> sales, int top) {
        List<ProductSalesAggregate> ranked = sales.stream()
                .filter(a -> a.productId() != null)
                .sorted(Comparator.comparingLong(ProductSalesAggregate::unitsSold).reversed()
                        .thenComparing(ProductSalesAggregate::productId))
                .limit(top)
                .toList();

        Map<Long, String> names = safeNames(() -> inventoryFacade.getNamesByIds(
                ranked.stream().map(ProductSalesAggregate::productId).collect(Collectors.toSet())));

        return ranked.stream()
                .map(a -> new FastMovingRow(
                        a.productId(),
                        names.getOrDefault(a.productId(), UNRESOLVED_NAME),
                        a.unitsSold(),
                        nz(a.revenue()).setScale(MONEY_SCALE, RoundingMode.HALF_UP)))
                .toList();
    }

    /**
     * The existing aging report, trimmed to a dashboard-sized slice. The algorithm — what counts as
     * "not moving", and over how many days — is not reimplemented here; only the ordering changes,
     * from the report's alphabetical listing to biggest-idle-quantity-first, which is the order a
     * chart of dead stock is actually read in.
     */
    private List<AgingInventoryRow> agingInventory(int top) {
        return inventoryReportService.aging().stream()
                .sorted(Comparator.comparingInt(InventoryReportDtos.AgingRow::onHand).reversed()
                        .thenComparing(InventoryReportDtos.AgingRow::productId))
                .limit(top)
                .map(r -> new AgingInventoryRow(
                        r.productId(), r.productName(), r.sku(), r.onHand(), r.agingDays()))
                .toList();
    }

    // ══ helpers ═══════════════════════════════════════════════════════════════

    /** Groups a daily series into granularity buckets, summing a long measure. */
    private <T> Map<LocalDate, Long> bucketSum(List<T> rows,
                                               Function<T, LocalDate> date,
                                               java.util.function.ToLongFunction<T> measure,
                                               Granularity g) {
        Map<LocalDate, Long> out = new LinkedHashMap<>();
        for (T row : rows) {
            out.merge(g.bucketStart(date.apply(row)), measure.applyAsLong(row), Long::sum);
        }
        return out;
    }

    /** {@code part / whole * 100} at 1dp, or {@code 0} when the whole is zero — never NaN, never Infinity. */
    private BigDecimal share(BigDecimal part, BigDecimal whole) {
        BigDecimal w = nz(whole);
        if (w.signum() == 0) {
            return BigDecimal.ZERO.setScale(PERCENT_SCALE);
        }
        return nz(part).multiply(HUNDRED).divide(w, PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * Runs a batch name lookup, degrading to "no names" rather than failing the whole dashboard if the
     * owning module throws — the {@code safeUserName} posture used across the report services, applied
     * to the batch call. Rows keep their ids and show the placeholder; a chart with an unlabelled bar
     * is far better than a 500.
     */
    private Map<Long, String> safeNames(java.util.function.Supplier<Map<Long, String>> lookup) {
        try {
            Map<Long, String> names = lookup.get();
            return names == null ? Map.of() : names;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }
}
