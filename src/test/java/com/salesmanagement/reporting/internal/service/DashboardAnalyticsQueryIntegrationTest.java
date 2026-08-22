package com.salesmanagement.reporting.internal.service;

import com.salesmanagement.inventory.api.InventoryFacade;
import com.salesmanagement.inventory.api.StockHealthSummary;
import com.salesmanagement.invoicing.api.InvoiceFacade;
import com.salesmanagement.vanops.api.VanopsFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executes every aggregate query added for the analytics dashboards against a real PostgreSQL, in one
 * context.
 *
 * <p><strong>Why this exists alongside the mocked unit tests.</strong> The unit tests mock the facades,
 * so they prove the folding arithmetic and prove nothing whatsoever about the SQL underneath. A JPQL
 * string that Hibernate parses at bootstrap can still fail when Postgres runs it — a {@code sum()} over
 * no rows returning null into a primitive, a constructor projection whose numeric types do not line up,
 * a {@code group by} the dialect rejects. Those failures surface only on execution, and they surface as
 * a broken dashboard in production if nothing executes them first. So each new read is invoked here for
 * real.</p>
 *
 * <p><strong>These assertions are deliberately shape-only.</strong> The test database is whatever the
 * developer's {@code sm_test} happens to hold — usually empty, sometimes not — so asserting figures
 * would make the suite depend on data it does not own. What is asserted instead is what must hold for
 * <em>any</em> data: the calls return, they return non-null, and the stock-health invariant balances.
 * The empty-database case is the interesting one anyway, since that is where null-vs-zero aggregate
 * bugs live.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class DashboardAnalyticsQueryIntegrationTest {

    @Autowired InvoiceFacade invoiceFacade;
    @Autowired VanopsFacade vanopsFacade;
    @Autowired InventoryFacade inventoryFacade;
    @Autowired DashboardAnalyticsService analyticsService;

    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 1);

    @Test
    @DisplayName("the new invoicing aggregates execute on PostgreSQL")
    void invoicingAggregatesRun() {
        assertThat(invoiceFacade.aggregateDailySales(FROM, TO)).isNotNull();
        assertThat(invoiceFacade.aggregateDailyUnitsSold(FROM, TO)).isNotNull();

        // Zero-filled to the full status set, in lifecycle order, whatever the data holds.
        assertThat(invoiceFacade.countByStatus(FROM, TO))
                .extracting("status")
                .containsExactly("DRAFT", "SENT", "APPROVED", "REJECTED");
        assertThat(invoiceFacade.countByStatus(FROM, TO))
                .allSatisfy(c -> assertThat(c.count()).isGreaterThanOrEqualTo(0L));
    }

    @Test
    @DisplayName("the new vanops daily aggregates execute on PostgreSQL")
    void vanopsAggregatesRun() {
        assertThat(vanopsFacade.aggregateLoadedByDate(FROM, TO)).isNotNull();
        assertThat(vanopsFacade.aggregateReturnedByDate(FROM, TO)).isNotNull();
        assertThat(vanopsFacade.aggregateFulfillmentByDate(FROM, TO)).isNotNull();
    }

    @Test
    @DisplayName("stock health returns a balanced, non-null summary even on an empty warehouse")
    void stockHealthBalances() {
        StockHealthSummary h = inventoryFacade.getStockHealth();

        assertThat(h).isNotNull();
        // The whole point of the record: the slices are exhaustive and disjoint, so they add up.
        assertThat(h.outOfStock() + h.belowMinimum() + h.healthy()).isEqualTo(h.totalSkus());
        assertThat(h.totalSkus()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("top-stock-value honours its limit in the database and is ordered by value desc")
    void topStockValueRunsAndOrders() {
        var rows = inventoryFacade.findTopStockValueProducts(5);

        assertThat(rows).isNotNull().hasSizeLessThanOrEqualTo(5);
        assertThat(rows).isSortedAccordingTo(
                java.util.Comparator.comparing(
                        com.salesmanagement.inventory.api.ProductStockValueInfo::stockValue).reversed());
        assertThat(rows).allSatisfy(r -> assertThat(r.onHand()).isPositive());

        assertThat(inventoryFacade.findTopStockValueProducts(0)).isEmpty();
    }

    @Test
    @DisplayName("the full sales analytics payload builds end to end against the real database")
    void salesAnalyticsBuilds() {
        var a = analyticsService.salesAnalytics(
                new com.salesmanagement.reporting.internal.support.DateRangeResolver.DateRange(FROM, TO),
                com.salesmanagement.reporting.internal.support.Granularity.DAY, 5);

        assertThat(a.from()).isEqualTo(FROM);
        assertThat(a.to()).isEqualTo(TO);
        assertThat(a.salesTrend()).hasSize(31);                       // every day zero-filled
        assertThat(a.salesTrend()).extracting("periodStart").isSorted();
        assertThat(a.invoiceStatusDistribution()).hasSize(4);
        assertThat(a.periodSummary().totalSales()).isNotNull();
        assertThat(a.routeOutcomes()).isNotNull();
        assertThat(a.topRepresentatives()).hasSizeLessThanOrEqualTo(5);
        assertThat(a.topTerritories()).hasSizeLessThanOrEqualTo(5);
        assertThat(a.topProducts()).hasSizeLessThanOrEqualTo(5);
        assertThat(a.topCustomers()).hasSizeLessThanOrEqualTo(5);
    }

    @Test
    @DisplayName("the full inventory analytics payload builds end to end against the real database")
    void inventoryAnalyticsBuilds() {
        var a = analyticsService.inventoryAnalytics(
                new com.salesmanagement.reporting.internal.support.DateRangeResolver.DateRange(FROM, TO),
                com.salesmanagement.reporting.internal.support.Granularity.WEEK, 5);

        assertThat(a.granularity()).isEqualTo("WEEK");
        assertThat(a.movementTrend()).isNotEmpty();
        assertThat(a.movementTrend()).extracting("periodStart").isSorted();
        assertThat(a.fillRateTrend()).hasSameSizeAs(a.movementTrend());
        assertThat(a.stockHealth().outOfStock()
                + a.stockHealth().belowMinimum()
                + a.stockHealth().healthy()).isEqualTo(a.stockHealth().totalSkus());
        assertThat(a.topStockValueProducts()).hasSizeLessThanOrEqualTo(5);
        assertThat(a.fastMovingProducts()).hasSizeLessThanOrEqualTo(5);
        assertThat(a.agingInventory()).hasSizeLessThanOrEqualTo(5);
    }
}
