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
import com.salesmanagement.reporting.internal.dto.DashboardAnalyticsDtos.FillRateTrendPoint;
import com.salesmanagement.reporting.internal.dto.DashboardAnalyticsDtos.InventoryAnalytics;
import com.salesmanagement.reporting.internal.dto.DashboardAnalyticsDtos.MovementTrendPoint;
import com.salesmanagement.reporting.internal.dto.DashboardAnalyticsDtos.RouteOutcomes;
import com.salesmanagement.reporting.internal.dto.DashboardAnalyticsDtos.SalesAnalytics;
import com.salesmanagement.reporting.internal.dto.DashboardAnalyticsDtos.SalesTrendPoint;
import com.salesmanagement.reporting.internal.dto.InventoryReportDtos.AgingRow;
import com.salesmanagement.reporting.internal.dto.TerritoryReportDtos.TerritorySalesRow;
import com.salesmanagement.reporting.internal.support.DateRangeResolver.DateRange;
import com.salesmanagement.reporting.internal.support.Granularity;
import com.salesmanagement.vanops.api.DailyFulfillmentAggregate;
import com.salesmanagement.vanops.api.DailyMovementAggregate;
import com.salesmanagement.vanops.api.VanopsFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DashboardAnalyticsService} — the folding, bucketing, ranking and percentage
 * arithmetic that turns module aggregates into chart data.
 *
 * <p>Every collaborator is mocked, which is the point: the facades' own filters (realised statuses,
 * LOADED-only movement) are their modules' contracts and are tested where they live. What is
 * genuinely at risk here is the composition — a week bucket that starts on the wrong day, a zero-fill
 * that drops the last bucket, a percentage that divides by zero, a ranking that reorders itself
 * between calls. Those are what these tests pin down.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DashboardAnalyticsServiceTest {

    @Mock InvoiceFacade invoiceFacade;
    @Mock InventoryFacade inventoryFacade;
    @Mock VanopsFacade vanopsFacade;
    @Mock UserFacade userFacade;
    @Mock CustomerFacade customerFacade;
    @Mock TerritoryReportService territoryReportService;
    @Mock RouteReportService routeReportService;
    @Mock InventoryReportService inventoryReportService;

    @InjectMocks DashboardAnalyticsService service;

    /** August 2026: the 1st is a Saturday, so week boundaries fall on the 3rd, 10th, 17th, 24th, 31st. */
    private static final LocalDate AUG_1 = LocalDate.of(2026, 8, 1);
    private static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);

    @BeforeEach
    void emptyByDefault() {
        // Every collaborator returns "nothing happened" unless a test says otherwise, so each test
        // states only the data it is actually about.
        when(invoiceFacade.aggregateDailySales(any(), any())).thenReturn(List.of());
        when(invoiceFacade.aggregateByRep(any(), any())).thenReturn(List.of());
        when(invoiceFacade.aggregateByCustomer(any(), any())).thenReturn(List.of());
        when(invoiceFacade.aggregateProductSales(any(), any())).thenReturn(List.of());
        when(invoiceFacade.aggregateDailyUnitsSold(any(), any())).thenReturn(List.of());
        when(invoiceFacade.countByStatus(any(), any())).thenReturn(List.of());
        when(territoryReportService.salesByTerritory(any())).thenReturn(List.of());
        when(routeReportService.routeOutcomes(any(), any()))
                .thenReturn(new RouteOutcomes(0, 0, 0, 0, 0, BigDecimal.ZERO.setScale(1)));
        when(vanopsFacade.aggregateLoadedByDate(any(), any())).thenReturn(List.of());
        when(vanopsFacade.aggregateReturnedByDate(any(), any())).thenReturn(List.of());
        when(vanopsFacade.aggregateFulfillmentByDate(any(), any())).thenReturn(List.of());
        when(vanopsFacade.aggregateFulfillment(any(), any())).thenReturn(List.of());
        when(inventoryFacade.getStockHealth()).thenReturn(new StockHealthSummary(0, 0, 0, 0));
        when(inventoryFacade.findTopStockValueProducts(anyInt())).thenReturn(List.of());
        when(inventoryFacade.getNamesByIds(any())).thenReturn(Map.of());
        when(inventoryReportService.aging()).thenReturn(List.of());
        when(userFacade.getNamesByIds(any())).thenReturn(Map.of());
        when(customerFacade.getNamesByIds(any())).thenReturn(Map.of());
    }

    private SalesAnalytics sales(Granularity g, int top) {
        return service.salesAnalytics(new DateRange(AUG_1, SEP_1), g, top);
    }

    private InventoryAnalytics inventory(Granularity g, int top) {
        return service.inventoryAnalytics(new DateRange(AUG_1, SEP_1), g, top);
    }

    // ══ Sales trend ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("sales trend — bucketing and zero-fill")
    class Trend {

        @Test
        @DisplayName("every day in the window appears, silent days at zero")
        void dailyZeroFill() {
            when(invoiceFacade.aggregateDailySales(AUG_1, SEP_1)).thenReturn(List.of(
                    new DailySalesAggregate(LocalDate.of(2026, 8, 2), 1, new BigDecimal("25000")),
                    new DailySalesAggregate(LocalDate.of(2026, 8, 5), 2, new BigDecimal("40000"))));

            List<SalesTrendPoint> trend = sales(Granularity.DAY, 5).salesTrend();

            assertThat(trend).hasSize(31);                       // 1..31 August, `to` exclusive
            assertThat(trend.get(0).periodStart()).isEqualTo(AUG_1);
            assertThat(trend.get(30).periodStart()).isEqualTo(LocalDate.of(2026, 8, 31));

            assertThat(trend.get(0).salesTotal()).isEqualByComparingTo("0");
            assertThat(trend.get(0).invoiceCount()).isZero();
            assertThat(trend.get(1).salesTotal()).isEqualByComparingTo("25000");
            assertThat(trend.get(1).invoiceCount()).isEqualTo(1);
            assertThat(trend.get(2).salesTotal()).isEqualByComparingTo("0");  // Aug 3, silent
            assertThat(trend.get(4).salesTotal()).isEqualByComparingTo("40000");
        }

        @Test
        @DisplayName("trend is sorted ascending by periodStart even when aggregates arrive unordered")
        void ascendingRegardlessOfInputOrder() {
            when(invoiceFacade.aggregateDailySales(AUG_1, SEP_1)).thenReturn(List.of(
                    new DailySalesAggregate(LocalDate.of(2026, 8, 20), 1, new BigDecimal("10")),
                    new DailySalesAggregate(LocalDate.of(2026, 8, 3), 1, new BigDecimal("10"))));

            List<LocalDate> dates = sales(Granularity.DAY, 5).salesTrend().stream()
                    .map(SalesTrendPoint::periodStart).toList();

            assertThat(dates).isSorted();
        }

        @Test
        @DisplayName("WEEK groups by ISO week and labels each bucket with its Monday")
        void weekGrouping() {
            // Aug 3 and Aug 7 2026 are both in the Mon-Aug-3 week; Aug 10 starts the next one.
            when(invoiceFacade.aggregateDailySales(AUG_1, SEP_1)).thenReturn(List.of(
                    new DailySalesAggregate(LocalDate.of(2026, 8, 3), 1, new BigDecimal("100")),
                    new DailySalesAggregate(LocalDate.of(2026, 8, 7), 2, new BigDecimal("200")),
                    new DailySalesAggregate(LocalDate.of(2026, 8, 10), 1, new BigDecimal("50"))));

            List<SalesTrendPoint> trend = sales(Granularity.WEEK, 5).salesTrend();

            // First bucket is the week CONTAINING Aug 1 (a Saturday), i.e. Monday Jul 27.
            assertThat(trend.get(0).periodStart()).isEqualTo(LocalDate.of(2026, 7, 27));
            assertThat(trend).allSatisfy(p ->
                    assertThat(p.periodStart().getDayOfWeek()).isEqualTo(java.time.DayOfWeek.MONDAY));

            SalesTrendPoint firstFullWeek = trend.get(1);
            assertThat(firstFullWeek.periodStart()).isEqualTo(LocalDate.of(2026, 8, 3));
            assertThat(firstFullWeek.salesTotal()).isEqualByComparingTo("300"); // 100 + 200 merged
            assertThat(firstFullWeek.invoiceCount()).isEqualTo(3);

            assertThat(trend.get(2).periodStart()).isEqualTo(LocalDate.of(2026, 8, 10));
            assertThat(trend.get(2).salesTotal()).isEqualByComparingTo("50");
        }

        @Test
        @DisplayName("MONTH groups by calendar month and labels each bucket with the 1st")
        void monthGrouping() {
            when(invoiceFacade.aggregateDailySales(any(), any())).thenReturn(List.of(
                    new DailySalesAggregate(LocalDate.of(2026, 8, 2), 1, new BigDecimal("100")),
                    new DailySalesAggregate(LocalDate.of(2026, 8, 30), 1, new BigDecimal("200")),
                    new DailySalesAggregate(LocalDate.of(2026, 9, 4), 1, new BigDecimal("70"))));

            List<SalesTrendPoint> trend = service.salesAnalytics(
                    new DateRange(AUG_1, LocalDate.of(2026, 10, 1)), Granularity.MONTH, 5).salesTrend();

            assertThat(trend).hasSize(2);
            assertThat(trend.get(0).periodStart()).isEqualTo(AUG_1);
            assertThat(trend.get(0).salesTotal()).isEqualByComparingTo("300");
            assertThat(trend.get(0).invoiceCount()).isEqualTo(2);
            assertThat(trend.get(1).periodStart()).isEqualTo(SEP_1);
            assertThat(trend.get(1).salesTotal()).isEqualByComparingTo("70");
        }

        @Test
        @DisplayName("a window with no data still returns a full, structurally valid zero-filled trend")
        void emptyRangeIsStillAChart() {
            SalesAnalytics a = sales(Granularity.DAY, 5);

            assertThat(a.salesTrend()).hasSize(31);
            assertThat(a.salesTrend()).allSatisfy(p -> {
                assertThat(p.salesTotal()).isEqualByComparingTo("0");
                assertThat(p.invoiceCount()).isZero();
            });
            assertThat(a.topRepresentatives()).isEmpty();
            assertThat(a.topTerritories()).isEmpty();
            assertThat(a.topProducts()).isEmpty();
            assertThat(a.topCustomers()).isEmpty();
            assertThat(a.periodSummary().totalSales()).isEqualByComparingTo("0");
            assertThat(a.periodSummary().averageInvoiceValue()).isEqualByComparingTo("0");
            assertThat(a.periodSummary().uniqueCustomers()).isZero();
        }
    }

    // ══ Period summary ════════════════════════════════════════════════════════

    @Nested
    @DisplayName("period summary")
    class Summary {

        @Test
        @DisplayName("totals come from the same realised rows as the trend; average is total / count")
        void totalsAndAverage() {
            when(invoiceFacade.aggregateDailySales(AUG_1, SEP_1)).thenReturn(List.of(
                    new DailySalesAggregate(LocalDate.of(2026, 8, 2), 2, new BigDecimal("30000")),
                    new DailySalesAggregate(LocalDate.of(2026, 8, 9), 2, new BigDecimal("10000"))));
            when(invoiceFacade.aggregateByCustomer(AUG_1, SEP_1)).thenReturn(List.of(
                    new CustomerPurchaseAggregate(1L, 3, new BigDecimal("30000")),
                    new CustomerPurchaseAggregate(2L, 1, new BigDecimal("10000"))));

            SalesAnalytics a = sales(Granularity.DAY, 5);

            assertThat(a.periodSummary().totalSales()).isEqualByComparingTo("40000");
            assertThat(a.periodSummary().invoiceCount()).isEqualTo(4);
            assertThat(a.periodSummary().averageInvoiceValue()).isEqualByComparingTo("10000.00");
            assertThat(a.periodSummary().uniqueCustomers()).isEqualTo(2);

            // The headline total must equal the charted total — they are the same rows.
            BigDecimal charted = a.salesTrend().stream()
                    .map(SalesTrendPoint::salesTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(charted).isEqualByComparingTo(a.periodSummary().totalSales());
        }

        @Test
        @DisplayName("zero invoices gives an average of 0, not a division error")
        void averageGuardsDivisionByZero() {
            assertThat(sales(Granularity.DAY, 5).periodSummary().averageInvoiceValue())
                    .isEqualByComparingTo("0");
        }
    }

    // ══ Realised-sales contract ═══════════════════════════════════════════════

    @Nested
    @DisplayName("realised sales vs the status donut")
    class Realised {

        @Test
        @DisplayName("revenue is read only through the facade's realised aggregates")
        void revenueUsesRealisedAggregatesOnly() {
            // The realised filter belongs to invoicing and cannot be passed from here; what this
            // pins is that the service asks for the *filtered* reads and never the unfiltered
            // per-invoice summaries, which would let DRAFT and REJECTED into the revenue figures.
            sales(Granularity.DAY, 5);

            org.mockito.Mockito.verify(invoiceFacade).aggregateDailySales(AUG_1, SEP_1);
            org.mockito.Mockito.verify(invoiceFacade).aggregateByRep(AUG_1, SEP_1);
            org.mockito.Mockito.verify(invoiceFacade).aggregateByCustomer(AUG_1, SEP_1);
            org.mockito.Mockito.verify(invoiceFacade).aggregateProductSales(AUG_1, SEP_1);
            org.mockito.Mockito.verify(invoiceFacade, org.mockito.Mockito.never())
                    .findSummaries(any(), any(), any(), any());
        }

        @Test
        @DisplayName("the status donut carries every status, DRAFT and REJECTED included, in order")
        void statusDistributionCountsAllStatuses() {
            when(invoiceFacade.countByStatus(AUG_1, SEP_1)).thenReturn(List.of(
                    new InvoiceStatusCount("DRAFT", 3),
                    new InvoiceStatusCount("SENT", 5),
                    new InvoiceStatusCount("APPROVED", 8),
                    new InvoiceStatusCount("REJECTED", 1)));

            var slices = sales(Granularity.DAY, 5).invoiceStatusDistribution();

            assertThat(slices).extracting("status")
                    .containsExactly("DRAFT", "SENT", "APPROVED", "REJECTED");
            assertThat(slices).extracting("count").containsExactly(3L, 5L, 8L, 1L);
        }
    }

    // ══ Rankings ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("rankings — limit, ordering, shares")
    class Rankings {

        @Test
        @DisplayName("top N is respected and rows are ordered by metric desc")
        void topNAndOrdering() {
            when(invoiceFacade.aggregateByRep(AUG_1, SEP_1)).thenReturn(List.of(
                    new RepSalesAggregate(1L, 1, new BigDecimal("100")),
                    new RepSalesAggregate(2L, 1, new BigDecimal("500")),
                    new RepSalesAggregate(3L, 1, new BigDecimal("300")),
                    new RepSalesAggregate(4L, 1, new BigDecimal("400"))));
            when(userFacade.getNamesByIds(any())).thenReturn(Map.of(2L, "Rep Two", 4L, "Rep Four"));

            var reps = sales(Granularity.DAY, 2).topRepresentatives();

            assertThat(reps).hasSize(2);
            assertThat(reps).extracting("representativeId").containsExactly(2L, 4L);
            assertThat(reps.get(0).representativeName()).isEqualTo("Rep Two");
        }

        @Test
        @DisplayName("ties break on id ascending, so the same data always ranks the same way")
        void deterministicTieBreak() {
            when(invoiceFacade.aggregateByRep(AUG_1, SEP_1)).thenReturn(List.of(
                    new RepSalesAggregate(9L, 1, new BigDecimal("100")),
                    new RepSalesAggregate(3L, 1, new BigDecimal("100")),
                    new RepSalesAggregate(7L, 1, new BigDecimal("100"))));

            assertThat(sales(Granularity.DAY, 5).topRepresentatives())
                    .extracting("representativeId").containsExactly(3L, 7L, 9L);
        }

        @Test
        @DisplayName("an unresolved name leaves the figure intact behind a placeholder")
        void unresolvedNameNeverDropsARow() {
            when(invoiceFacade.aggregateByRep(AUG_1, SEP_1))
                    .thenReturn(List.of(new RepSalesAggregate(42L, 1, new BigDecimal("100"))));
            when(userFacade.getNamesByIds(any())).thenReturn(Map.of());

            var reps = sales(Granularity.DAY, 5).topRepresentatives();

            assertThat(reps).hasSize(1);
            assertThat(reps.get(0).totalSales()).isEqualByComparingTo("100");
            assertThat(reps.get(0).representativeName()).isEqualTo("—");
        }

        @Test
        @DisplayName("rep shares are a percentage of the window's realised total")
        void repSharePercent() {
            when(invoiceFacade.aggregateDailySales(AUG_1, SEP_1)).thenReturn(List.of(
                    new DailySalesAggregate(LocalDate.of(2026, 8, 2), 4, new BigDecimal("1000"))));
            when(invoiceFacade.aggregateByRep(AUG_1, SEP_1)).thenReturn(List.of(
                    new RepSalesAggregate(1L, 3, new BigDecimal("750")),
                    new RepSalesAggregate(2L, 1, new BigDecimal("250"))));

            var reps = sales(Granularity.DAY, 5).topRepresentatives();

            assertThat(reps.get(0).sharePercent()).isEqualByComparingTo("75.0");
            assertThat(reps.get(1).sharePercent()).isEqualByComparingTo("25.0");
        }

        @Test
        @DisplayName("shares are 0, never NaN, when the window sold nothing")
        void shareGuardsDivisionByZero() {
            when(invoiceFacade.aggregateByRep(AUG_1, SEP_1))
                    .thenReturn(List.of(new RepSalesAggregate(1L, 0, BigDecimal.ZERO)));

            assertThat(sales(Granularity.DAY, 5).topRepresentatives().get(0).sharePercent())
                    .isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("territories with no sales are excluded from the top-territories ranking")
        void silentTerritoriesAreNotTopTerritories() {
            when(invoiceFacade.aggregateDailySales(AUG_1, SEP_1)).thenReturn(List.of(
                    new DailySalesAggregate(LocalDate.of(2026, 8, 2), 2, new BigDecimal("100"))));
            when(territoryReportService.salesByTerritory(any())).thenReturn(List.of(
                    new TerritorySalesRow(1L, "Malki", 2, new BigDecimal("100")),
                    new TerritorySalesRow(2L, "Mezzeh", 0, BigDecimal.ZERO)));

            var terr = sales(Granularity.DAY, 5).topTerritories();

            assertThat(terr).hasSize(1);
            assertThat(terr.get(0).territoryName()).isEqualTo("Malki");
            assertThat(terr.get(0).sharePercent()).isEqualByComparingTo("100.0");
        }

        @Test
        @DisplayName("product shares divide by total product revenue, so the top rows sum sensibly")
        void productShares() {
            when(invoiceFacade.aggregateProductSales(AUG_1, SEP_1)).thenReturn(List.of(
                    new ProductSalesAggregate(1L, 10, new BigDecimal("600")),
                    new ProductSalesAggregate(2L, 30, new BigDecimal("400"))));
            when(inventoryFacade.getNamesByIds(any())).thenReturn(Map.of(1L, "Widget", 2L, "Gadget"));

            var products = sales(Granularity.DAY, 5).topProducts();

            assertThat(products).extracting("productId").containsExactly(1L, 2L); // by revenue desc
            assertThat(products.get(0).revenueSharePercent()).isEqualByComparingTo("60.0");
            assertThat(products.get(1).revenueSharePercent()).isEqualByComparingTo("40.0");
        }

        @Test
        @DisplayName("customers rank by total spent descending")
        void customersBySpend() {
            when(invoiceFacade.aggregateByCustomer(AUG_1, SEP_1)).thenReturn(List.of(
                    new CustomerPurchaseAggregate(1L, 1, new BigDecimal("100")),
                    new CustomerPurchaseAggregate(2L, 5, new BigDecimal("900"))));
            when(customerFacade.getNamesByIds(any())).thenReturn(Map.of(1L, "A", 2L, "B"));

            assertThat(sales(Granularity.DAY, 5).topCustomers())
                    .extracting("customerId").containsExactly(2L, 1L);
        }
    }

    // ══ Inventory ═════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("stock health")
    class StockHealth {

        @Test
        @DisplayName("the three categories sum exactly to totalSkus")
        void categoriesSumToTotal() {
            when(inventoryFacade.getStockHealth()).thenReturn(new StockHealthSummary(15, 2, 4, 9));

            var h = inventory(Granularity.DAY, 5).stockHealth();

            assertThat(h.outOfStock() + h.belowMinimum() + h.healthy()).isEqualTo(h.totalSkus());
            assertThat(h.totalSkus()).isEqualTo(15);
        }

        @Test
        @DisplayName("aging stock overlapping low stock does not perturb the health totals")
        void agingDoesNotCorruptStockHealth() {
            // Product 1 is BOTH below minimum and aging. The old, wrong composition
            // (healthy = total - belowMin - aging) would count it twice and under-report healthy.
            when(inventoryFacade.getStockHealth()).thenReturn(new StockHealthSummary(10, 1, 1, 8));
            when(inventoryReportService.aging()).thenReturn(List.of(
                    new AgingRow(1L, "Also below minimum", "SKU-1", 3, 30)));

            var a = inventory(Granularity.DAY, 5);

            assertThat(a.stockHealth().healthy()).isEqualTo(8);
            assertThat(a.stockHealth().outOfStock()
                    + a.stockHealth().belowMinimum()
                    + a.stockHealth().healthy()).isEqualTo(a.stockHealth().totalSkus());
            assertThat(a.agingInventory()).hasSize(1); // aging is reported, just kept separate
        }
    }

    @Nested
    @DisplayName("movement trend")
    class Movement {

        @Test
        @DisplayName("the three components are merged by date and stay independent")
        void componentsMergeIndependently() {
            when(vanopsFacade.aggregateLoadedByDate(AUG_1, SEP_1)).thenReturn(List.of(
                    new DailyMovementAggregate(LocalDate.of(2026, 8, 2), 100)));
            when(vanopsFacade.aggregateReturnedByDate(AUG_1, SEP_1)).thenReturn(List.of(
                    new DailyMovementAggregate(LocalDate.of(2026, 8, 2), 20)));
            when(invoiceFacade.aggregateDailyUnitsSold(AUG_1, SEP_1)).thenReturn(List.of(
                    new DailyUnitsSoldAggregate(LocalDate.of(2026, 8, 3), 70)));

            List<MovementTrendPoint> trend = inventory(Granularity.DAY, 5).movementTrend();

            assertThat(trend).hasSize(31);
            MovementTrendPoint aug2 = trend.get(1);
            assertThat(aug2.loadedToVans()).isEqualTo(100);
            assertThat(aug2.returnedFromVans()).isEqualTo(20);
            assertThat(aug2.unitsSold()).isZero();          // sold landed on the 3rd, not the 2nd

            MovementTrendPoint aug3 = trend.get(2);
            assertThat(aug3.loadedToVans()).isZero();
            assertThat(aug3.unitsSold()).isEqualTo(70);
        }

        @Test
        @DisplayName("components sum within a WEEK bucket without bleeding into each other")
        void weeklyMovementBuckets() {
            when(vanopsFacade.aggregateLoadedByDate(AUG_1, SEP_1)).thenReturn(List.of(
                    new DailyMovementAggregate(LocalDate.of(2026, 8, 3), 10),
                    new DailyMovementAggregate(LocalDate.of(2026, 8, 6), 15)));

            MovementTrendPoint weekOfAug3 = inventory(Granularity.WEEK, 5).movementTrend().stream()
                    .filter(p -> p.periodStart().equals(LocalDate.of(2026, 8, 3)))
                    .findFirst().orElseThrow();

            assertThat(weekOfAug3.loadedToVans()).isEqualTo(25);
            assertThat(weekOfAug3.returnedFromVans()).isZero();
            assertThat(weekOfAug3.unitsSold()).isZero();
        }
    }

    @Nested
    @DisplayName("fill-rate trend")
    class FillRate {

        @Test
        @DisplayName("the bucket rate is weighted by quantity, not an average of daily rates")
        void weightedNotAveraged() {
            // Day 1: 1 of 1 fulfilled (100%). Day 2: 10 of 100 (10%).
            // Averaging the two rates gives 55%. The weighted answer is 11/101 = 10.9%.
            when(vanopsFacade.aggregateFulfillmentByDate(AUG_1, SEP_1)).thenReturn(List.of(
                    new DailyFulfillmentAggregate(LocalDate.of(2026, 8, 3), 1, 1),
                    new DailyFulfillmentAggregate(LocalDate.of(2026, 8, 4), 100, 10)));

            FillRateTrendPoint week = inventory(Granularity.WEEK, 5).fillRateTrend().stream()
                    .filter(p -> p.periodStart().equals(LocalDate.of(2026, 8, 3)))
                    .findFirst().orElseThrow();

            assertThat(week.requested()).isEqualTo(101);
            assertThat(week.fulfilled()).isEqualTo(11);
            assertThat(week.fillRatePercent()).isEqualByComparingTo("10.9");
        }

        @Test
        @DisplayName("a bucket with no demand reports null, not 0% — they are opposite findings")
        void zeroDemandIsNullNotZero() {
            when(vanopsFacade.aggregateFulfillmentByDate(AUG_1, SEP_1)).thenReturn(List.of(
                    new DailyFulfillmentAggregate(LocalDate.of(2026, 8, 2), 10, 0)));

            List<FillRateTrendPoint> trend = inventory(Granularity.DAY, 5).fillRateTrend();

            // Aug 1: nobody asked -> no rate at all.
            assertThat(trend.get(0).requested()).isZero();
            assertThat(trend.get(0).fillRatePercent()).isNull();

            // Aug 2: they asked for 10 and got none -> a real, dreadful 0%.
            assertThat(trend.get(1).requested()).isEqualTo(10);
            assertThat(trend.get(1).fillRatePercent()).isEqualByComparingTo("0.0");
        }
    }

    @Nested
    @DisplayName("inventory rankings")
    class InventoryRankings {

        @Test
        @DisplayName("top stock-value rows keep the database's value ordering and honour top N")
        void stockValueOrderingAndLimit() {
            when(inventoryFacade.findTopStockValueProducts(3)).thenReturn(List.of(
                    new ProductStockValueInfo(1L, "Big", "S1", 100, new BigDecimal("50"), new BigDecimal("5000")),
                    new ProductStockValueInfo(2L, "Mid", "S2", 10, new BigDecimal("100"), new BigDecimal("1000")),
                    new ProductStockValueInfo(3L, "Small", "S3", 1, new BigDecimal("10"), new BigDecimal("10"))));

            var rows = inventory(Granularity.DAY, 3).topStockValueProducts();

            assertThat(rows).extracting("productId").containsExactly(1L, 2L, 3L);
            assertThat(rows.get(0).stockValue()).isEqualByComparingTo("5000.00");
            org.mockito.Mockito.verify(inventoryFacade).findTopStockValueProducts(3);
        }

        @Test
        @DisplayName("fast movers rank by units sold, not revenue, with an id tie-break")
        void fastMoversRankByUnits() {
            when(invoiceFacade.aggregateProductSales(AUG_1, SEP_1)).thenReturn(List.of(
                    new ProductSalesAggregate(1L, 5, new BigDecimal("10000")),   // pricey, slow
                    new ProductSalesAggregate(2L, 500, new BigDecimal("100")),   // cheap, fast
                    new ProductSalesAggregate(3L, 500, new BigDecimal("90"))));  // ties with 2

            assertThat(inventory(Granularity.DAY, 5).fastMovingProducts())
                    .extracting("productId").containsExactly(2L, 3L, 1L);
        }

        @Test
        @DisplayName("aging rows are trimmed to top N, biggest idle quantity first")
        void agingTopN() {
            when(inventoryReportService.aging()).thenReturn(List.of(
                    new AgingRow(1L, "A", "S1", 5, 30),
                    new AgingRow(2L, "B", "S2", 90, 30),
                    new AgingRow(3L, "C", "S3", 40, 30)));

            var aging = inventory(Granularity.DAY, 2).agingInventory();

            assertThat(aging).extracting("productId").containsExactly(2L, 3L);
            assertThat(aging.get(0).onHand()).isEqualTo(90);
            assertThat(aging.get(0).agingDays()).isEqualTo(30);
        }

        @Test
        @DisplayName("an empty inventory window is a valid empty payload, not an error")
        void emptyInventoryPayload() {
            InventoryAnalytics a = inventory(Granularity.DAY, 5);

            assertThat(a.from()).isEqualTo(AUG_1);
            assertThat(a.to()).isEqualTo(SEP_1);
            assertThat(a.granularity()).isEqualTo("DAY");
            assertThat(a.movementTrend()).hasSize(31);
            assertThat(a.fillRateTrend()).hasSize(31);
            assertThat(a.fillRateTrend()).allSatisfy(p -> assertThat(p.fillRatePercent()).isNull());
            assertThat(a.topStockValueProducts()).isEmpty();
            assertThat(a.fastMovingProducts()).isEmpty();
            assertThat(a.agingInventory()).isEmpty();
        }
    }

    // ══ Window echo ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("the payload echoes the resolved window and granularity it actually used")
    void echoesResolvedWindow() {
        SalesAnalytics a = sales(Granularity.MONTH, 5);

        assertThat(a.from()).isEqualTo(AUG_1);
        assertThat(a.to()).isEqualTo(SEP_1);
        assertThat(a.granularity()).isEqualTo("MONTH");
    }
}
