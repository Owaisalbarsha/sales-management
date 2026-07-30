package com.salesmanagement.reporting.internal.service;

import com.salesmanagement.inventory.api.InventoryFacade;
import com.salesmanagement.inventory.api.WarehouseStockInfo;
import com.salesmanagement.invoicing.api.InvoiceFacade;
import com.salesmanagement.invoicing.api.ProductSalesAggregate;
import com.salesmanagement.reporting.internal.config.ReportingConfig;
import com.salesmanagement.reporting.internal.dto.InventoryReportDtos.AgingRow;
import com.salesmanagement.reporting.internal.dto.InventoryReportDtos.FillRateRow;
import com.salesmanagement.reporting.internal.dto.InventoryReportDtos.MovementRow;
import com.salesmanagement.reporting.internal.dto.InventoryReportDtos.ProductMovementRow;
import com.salesmanagement.reporting.internal.dto.InventoryReportDtos.StockLevelRow;
import com.salesmanagement.reporting.internal.support.DateRangeResolver.DateRange;
import com.salesmanagement.reporting.internal.support.ReportTable;
import com.salesmanagement.systemconfig.api.ConfigFacade;
import com.salesmanagement.vanops.api.FulfillmentAggregate;
import com.salesmanagement.vanops.api.ProductMovementAggregate;
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
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the inventory reports. The most facade-composed service in the module:
 * <ul>
 *   <li>FR-120 stock levels, FR-121 below-minimum — {@code InventoryFacade}</li>
 *   <li>FR-122 movement summary — {@code VanopsFacade} (loaded/returned) + {@code InvoiceFacade} (sold)</li>
 *   <li>FR-123 fast/slow-moving — {@code InvoiceFacade} product sales, ranked; N from {@code ConfigFacade}</li>
 *   <li>Aging — stocked products absent from the last-N-days movement set (window proxy)</li>
 *   <li>Fill-rate — {@code VanopsFacade} requested vs fulfilled</li>
 * </ul>
 *
 * <p>Product names are resolved in ONE {@code InventoryFacade.getNamesByIds} batch per report (product
 * cardinality is unbounded — the D1 batch case).</p>
 *
 * <p><strong>FR-124 (stock variance) is DEFERRED</strong> — it needs the inventory stock-count feature
 * (manager submits physical counts) which is not yet built. When that ships and exposes a variance
 * read, add a {@code variance(...)} method here and an endpoint on the controller; ~30 min. Do not stub
 * it before the upstream facade exists.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryReportService {

    private final InventoryFacade inventoryFacade;
    private final VanopsFacade vanopsFacade;
    private final InvoiceFacade invoiceFacade;
    private final ConfigFacade configFacade;

    // ── FR-120 / FR-121: stock levels ─────────────────────────────────────────

    /** All warehouse stock (FR-120), or only shortages when {@code belowMinOnly} (FR-121). */
    public List<StockLevelRow> stockLevels(boolean belowMinOnly) {
        List<WarehouseStockInfo> stock = belowMinOnly
                ? inventoryFacade.findWarehouseStockBelowMinimum()
                : inventoryFacade.findAllWarehouseStock();

        return stock.stream()
                .map(s -> new StockLevelRow(
                        s.productId(), s.productName(), s.sku(),
                        s.onHand(), s.minStockLevel(), s.belowMin()))
                .toList();
    }

    public ReportTable stockLevelsTable(boolean belowMinOnly) {
        List<List<String>> rows = stockLevels(belowMinOnly).stream()
                .map(r -> List.of(
                        r.productName(), r.sku(),
                        String.valueOf(r.onHand()),
                        String.valueOf(r.minStockLevel()),
                        r.belowMin() ? "نعم" : "لا"))          // yes / no
                .toList();
        String title = belowMinOnly ? "تقرير النواقص" : "تقرير مستويات المخزون"; // shortages / stock levels
        return new ReportTable(title,
                List.of("المنتج", "الرمز", "المتوفر", "الحد الأدنى", "أقل من الحد"),
                rows);
    }

    // ── FR-122: movement summary ──────────────────────────────────────────────

    /**
     * Net movement per product over the window. Joins three sources by productId: loaded-out and
     * returned-in from vanops, sold from invoicing. Net = returned − loaded − sold (effect on warehouse
     * on-hand: returns add, loads and sales remove). A product appearing in any source appears in the
     * report.
     */
    public List<MovementRow> movementSummary(DateRange range) {
        Map<Long, Long> loaded = toMap(vanopsFacade.aggregateLoadedByProduct(range.from(), range.to()));
        Map<Long, Long> returned = toMap(vanopsFacade.aggregateReturnedByProduct(range.from(), range.to()));
        Map<Long, Long> sold = invoiceFacade.aggregateProductSales(range.from(), range.to()).stream()
                .collect(Collectors.toMap(ProductSalesAggregate::productId, ProductSalesAggregate::unitsSold));

        Set<Long> allIds = new java.util.HashSet<>();
        allIds.addAll(loaded.keySet());
        allIds.addAll(returned.keySet());
        allIds.addAll(sold.keySet());

        Map<Long, String> names = inventoryFacade.getNamesByIds(allIds);

        return allIds.stream()
                .map(id -> {
                    long l = loaded.getOrDefault(id, 0L);
                    long r = returned.getOrDefault(id, 0L);
                    long s = sold.getOrDefault(id, 0L);
                    return new MovementRow(id, names.getOrDefault(id, "—"), l, r, s, l - s - r);
                })
                .sorted(Comparator.comparing(MovementRow::productName))
                .toList();
    }

    public ReportTable movementSummaryTable(DateRange range) {
        List<List<String>> rows = movementSummary(range).stream()
                .map(r -> List.of(
                        r.productName(),
                        String.valueOf(r.loadedToVans()),
                        String.valueOf(r.returnedFromVans()),
                        String.valueOf(r.sold()),
                        String.valueOf(r.net())))
                .toList();
        return new ReportTable("تقرير حركة المخزون",  // "Stock movement report"
                List.of("المنتج", "المحمّل للشاحنات", "المرتجع", "المباع", "الصافي"),
                rows);
    }

    // ── FR-123: fast / slow-moving ────────────────────────────────────────────

    /**
     * Products ranked by units sold in the window, classified FAST (top-N), SLOW (bottom-N), MID
     * (between). N is the configurable {@code REPORTING_FAST_SLOW_TOP_N} (default 10). Only products
     * with sales appear (a product never sold is not "slow-moving" here, it is "not moving" — that is
     * the aging report's concern, a different question).
     */
    public List<ProductMovementRow> fastSlowMoving(DateRange range) {
        int topN = configFacade.getInt(
                ReportingConfig.KEY_FAST_SLOW_TOP_N, ReportingConfig.DEFAULT_FAST_SLOW_TOP_N);

        List<ProductSalesAggregate> sales = invoiceFacade.aggregateProductSales(range.from(), range.to())
                .stream()
                .sorted(Comparator.comparingLong(ProductSalesAggregate::unitsSold).reversed())
                .toList();

        Map<Long, String> names = inventoryFacade.getNamesByIds(
                sales.stream().map(ProductSalesAggregate::productId).collect(Collectors.toSet()));

        int size = sales.size();
        List<ProductMovementRow> out = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            ProductSalesAggregate a = sales.get(i);
            String cls = (i < topN) ? "FAST"
                       : (i >= size - topN) ? "SLOW"
                       : "MID";
            out.add(new ProductMovementRow(
                    a.productId(), names.getOrDefault(a.productId(), "—"),
                    a.unitsSold(), a.revenue(), cls));
        }
        return out;
    }

    public ReportTable fastSlowMovingTable(DateRange range) {
        List<List<String>> rows = fastSlowMoving(range).stream()
                .map(r -> List.of(
                        r.productName(),
                        String.valueOf(r.unitsSold()),
                        money(r.revenue()),
                        classAr(r.classification())))
                .toList();
        return new ReportTable("تقرير المنتجات الأكثر والأقل حركة", // "Fast/slow-moving products"
                List.of("المنتج", "الكمية المباعة", "الإيرادات", "التصنيف"),
                rows);
    }

    // ── Aging (window-based proxy) ────────────────────────────────────────────

    /**
     * Stocked products with NO outbound movement (sold or loaded) in the last N days. N is the
     * configurable {@code REPORTING_STOCK_AGING_DAYS} (default 30). Computed as: all stocked products
     * (on-hand > 0) minus any product that appears in the movement set for {@code [today−N, today)}.
     * Absence from that set is exactly "not moved in N days" — the proxy answers the FR precisely
     * without a last-movement-date query.
     */
    public List<AgingRow> aging() {
        int agingDays = configFacade.getInt(
                ReportingConfig.KEY_STOCK_AGING_DAYS, ReportingConfig.DEFAULT_STOCK_AGING_DAYS);
        LocalDate today = LocalDate.now(ReportingConfig.BUSINESS_ZONE);
        LocalDate windowStart = today.minusDays(agingDays);
        LocalDate windowEnd = today.plusDays(1); // exclusive upper, include today

        // Outbound movement in the window = sold (invoices) ∪ loaded to vans (demand orders).
        Set<Long> movedIds = new java.util.HashSet<>();
        invoiceFacade.aggregateProductSales(windowStart, windowEnd)
                .forEach(a -> movedIds.add(a.productId()));
        vanopsFacade.aggregateLoadedByProduct(windowStart, windowEnd)
                .forEach(a -> movedIds.add(a.productId()));

        // Stocked products not in the moved set, on-hand > 0 (zero on-hand is not "aging stock").
        return inventoryFacade.findAllWarehouseStock().stream()
                .filter(s -> s.onHand() > 0)
                .filter(s -> !movedIds.contains(s.productId()))
                .map(s -> new AgingRow(s.productId(), s.productName(), s.sku(), s.onHand(), agingDays))
                .sorted(Comparator.comparing(AgingRow::productName))
                .toList();
    }

    public ReportTable agingTable() {
        List<AgingRow> aging = aging();
        int days = aging.isEmpty() ? ReportingConfig.DEFAULT_STOCK_AGING_DAYS : aging.get(0).agingDays();
        List<List<String>> rows = aging.stream()
                .map(r -> List.of(r.productName(), r.sku(), String.valueOf(r.onHand())))
                .toList();
        return new ReportTable(
                "تقرير المخزون الراكد (" + days + " يوم)", // "Dead-stock report (N days)"
                List.of("المنتج", "الرمز", "المتوفر"),
                rows);
    }

    // ── Fill-rate ─────────────────────────────────────────────────────────────

    /**
     * Per-product fill rate over the window: fulfilled ÷ requested across loaded demand orders. A
     * product never requested has no fill rate and is omitted (div-by-zero guard). Ordered by fill rate
     * ascending — the worst-served products first, which is what the report is for.
     */
    public List<FillRateRow> fillRate(DateRange range) {
        List<FulfillmentAggregate> fulfilment =
                vanopsFacade.aggregateFulfillment(range.from(), range.to());

        Map<Long, String> names = inventoryFacade.getNamesByIds(
                fulfilment.stream().map(FulfillmentAggregate::productId).collect(Collectors.toSet()));

        return fulfilment.stream()
                .filter(f -> f.totalRequested() > 0)
                .map(f -> new FillRateRow(
                        f.productId(),
                        names.getOrDefault(f.productId(), "—"),
                        f.totalRequested(),
                        f.totalFulfilled(),
                        percent(f.totalFulfilled(), f.totalRequested())))
                .sorted(Comparator.comparing(FillRateRow::fillRatePercent))
                .toList();
    }

    public ReportTable fillRateTable(DateRange range) {
        List<List<String>> rows = fillRate(range).stream()
                .map(r -> List.of(
                        r.productName(),
                        String.valueOf(r.requested()),
                        String.valueOf(r.fulfilled()),
                        r.fillRatePercent().toPlainString() + "%"))
                .toList();
        return new ReportTable("تقرير نسبة تلبية الطلب", // "Fill-rate report"
                List.of("المنتج", "المطلوب", "المُلبّى", "نسبة التلبية"),
                rows);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private Map<Long, Long> toMap(List<ProductMovementAggregate> list) {
        return list.stream().collect(Collectors.toMap(
                ProductMovementAggregate::productId, ProductMovementAggregate::quantity,
                (a, b) -> a, LinkedHashMap::new));
    }

    private BigDecimal percent(long numerator, long denominator) {
        if (denominator == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP);
    }

    private String money(BigDecimal v) {
        BigDecimal safe = (v == null ? BigDecimal.ZERO : v);
        return safe.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String classAr(String classification) {
        return switch (classification) {
            case "FAST" -> "سريع الحركة";  // fast-moving
            case "SLOW" -> "بطيء الحركة";  // slow-moving
            default -> "متوسط";            // mid
        };
    }
}
