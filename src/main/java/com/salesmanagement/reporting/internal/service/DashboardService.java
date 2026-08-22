package com.salesmanagement.reporting.internal.service;

import com.salesmanagement.inventory.api.InventoryFacade;
import com.salesmanagement.invoicing.api.InvoiceFacade;
import com.salesmanagement.invoicing.api.InvoiceSummary;
import com.salesmanagement.reporting.internal.config.ReportingConfig;
import com.salesmanagement.reporting.internal.dto.DashboardDtos.InventoryDashboard;
import com.salesmanagement.reporting.internal.dto.DashboardDtos.SalesDashboard;
import com.salesmanagement.reporting.internal.dto.InventoryReportDtos.FillRateRow;
import com.salesmanagement.reporting.internal.support.DateRangeResolver.DateRange;
import com.salesmanagement.routing.api.RoutingFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.salesmanagement.reporting.internal.dto.TerritoryReportDtos.TerritorySalesRow;
import com.salesmanagement.invoicing.api.RepSalesAggregate;
import java.util.List;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Builds the two dashboard KPI payloads. Reuses the existing facades and, where a figure is already a
 * report, the existing report services — a dashboard tile is just a report reduced to a single number,
 * so there is no reason to re-derive it. Read-only snapshot (D8).
 *
 * <p>"Today" and "this month" resolve in the business zone ({@link ReportingConfig#BUSINESS_ZONE}) so a
 * late-evening invoice counts under the right business day, not the server's UTC day.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    private final InvoiceFacade invoiceFacade;
    private final RoutingFacade routingFacade;
    private final InventoryReportService inventoryReportService;
    private final TerritoryReportService territoryReportService;
    private final InventoryFacade inventoryFacade;
    private final com.salesmanagement.identity.api.UserFacade userFacade;

    // ── Sales dashboard ───────────────────────────────────────────────────────

    public SalesDashboard salesDashboard() {
        LocalDate today = LocalDate.now(ReportingConfig.BUSINESS_ZONE);
        LocalDate tomorrow = today.plusDays(1);
        LocalDate monthStart = today.withDayOfMonth(1);

        // --- existing tiles (unchanged) ---
        List<InvoiceSummary> todays = invoiceFacade.findSummaries(today, tomorrow, null, null);
        List<InvoiceSummary> todaysRealised = todays.stream().filter(this::isRealised).toList();
        List<InvoiceSummary> month = invoiceFacade.findSummaries(monthStart, tomorrow, null, null);
        List<InvoiceSummary> monthRealised = month.stream().filter(this::isRealised).toList();
        long activeRoutes = routingFacade.findRoutesInRange(today, tomorrow, null).stream()
                .filter(r -> "ACTIVE".equals(r.status())).count();

        // --- NEW: top territory this month ---
        List<TerritorySalesRow> terr = territoryReportService.salesByTerritory(new DateRange(monthStart, tomorrow));
        TerritorySalesRow topTerr = terr.isEmpty() ? null : terr.get(0); // already sorted desc
        String topTerrName = topTerr == null ? "\u2014" : topTerr.territoryName();
        java.math.BigDecimal topTerrSales = topTerr == null ? java.math.BigDecimal.ZERO : topTerr.totalSales();

        // --- NEW: top rep this month ---
        List<RepSalesAggregate> reps = invoiceFacade.aggregateByRep(monthStart, tomorrow);
        RepSalesAggregate topRep = reps.stream()
                .max(java.util.Comparator.comparing(RepSalesAggregate::totalSales))
                .orElse(null);
        String topRepName = topRep == null ? "\u2014" : safeUserName(topRep.representativeId());
        java.math.BigDecimal topRepSales = topRep == null ? java.math.BigDecimal.ZERO : topRep.totalSales();

        // --- NEW: month-over-month sales change ---
        LocalDate lastMonthStart = monthStart.minusMonths(1);
        List<InvoiceSummary> lastMonth = invoiceFacade.findSummaries(lastMonthStart, monthStart, null, null);
        java.math.BigDecimal lastMonthTotal = sumTotals(lastMonth.stream().filter(this::isRealised).toList());
        java.math.BigDecimal thisMonthTotal = sumTotals(monthRealised);
        java.math.BigDecimal mom = lastMonthTotal.signum() == 0
                ? java.math.BigDecimal.ZERO
                : thisMonthTotal.subtract(lastMonthTotal)
                .multiply(java.math.BigDecimal.valueOf(100))
                .divide(lastMonthTotal, 1, java.math.RoundingMode.HALF_UP);

        return new SalesDashboard(
                sumTotals(todaysRealised), todaysRealised.size(),
                thisMonthTotal, monthRealised.size(),
                activeRoutes,
                topTerrName, topTerrSales,
                topRepName, topRepSales,
                mom);
    }

    // ── Inventory dashboard ───────────────────────────────────────────────────

    public InventoryDashboard inventoryDashboard() {
        LocalDate today = LocalDate.now(ReportingConfig.BUSINESS_ZONE);
        LocalDate tomorrow = today.plusDays(1);
        LocalDate monthStart = today.withDayOfMonth(1);

        long belowMin = inventoryReportService.stockLevels(true).size();
        long aging = inventoryReportService.aging().size();
        long totalSkus = inventoryReportService.stockLevels(false).size();

        List<FillRateRow> fillRates = inventoryReportService.fillRate(new DateRange(monthStart, tomorrow));
        java.math.BigDecimal avgFillRate = fillRates.isEmpty()
                ? java.math.BigDecimal.ZERO
                : fillRates.stream().map(FillRateRow::fillRatePercent)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)
                .divide(java.math.BigDecimal.valueOf(fillRates.size()), 1, java.math.RoundingMode.HALF_UP);

        // --- NEW: total stock value (one query in the facade) ---
        java.math.BigDecimal stockValue = inventoryFacade.getTotalStockValue();

        return new InventoryDashboard(belowMin, aging, totalSkus, avgFillRate, stockValue);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Realised = a sale the business stands behind. Mirrors the invoice aggregates' server-side filter. */
    private boolean isRealised(InvoiceSummary s) {
        return "SENT".equals(s.status()) || "APPROVED".equals(s.status());
    }

    private BigDecimal sumTotals(List<InvoiceSummary> invoices) {
        return invoices.stream()
                .map(InvoiceSummary::totalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private String safeUserName(Long userId) {
        if (userId == null) return "\u2014";
        try {
            String name = userFacade.getNameById(userId);
            return (name == null || name.isBlank()) ? "\u2014" : name;
        } catch (RuntimeException e) {
            return "\u2014";
        }
    }
}
