package com.salesmanagement.reporting.internal.service;

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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

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

    // ── Sales dashboard ───────────────────────────────────────────────────────

    public SalesDashboard salesDashboard() {
        LocalDate today = LocalDate.now(ReportingConfig.BUSINESS_ZONE);
        LocalDate tomorrow = today.plusDays(1);
        LocalDate monthStart = today.withDayOfMonth(1);

        // Today's realised invoices: summaries are unfiltered by status, so filter to realised here.
        List<InvoiceSummary> todays = invoiceFacade.findSummaries(today, tomorrow, null, null);
        List<InvoiceSummary> todaysRealised = todays.stream().filter(this::isRealised).toList();

        List<InvoiceSummary> month = invoiceFacade.findSummaries(monthStart, tomorrow, null, null);
        List<InvoiceSummary> monthRealised = month.stream().filter(this::isRealised).toList();

        long activeRoutes = routingFacade.findRoutesInRange(today, tomorrow, null).stream()
                .filter(r -> "ACTIVE".equals(r.status()))
                .count();

        return new SalesDashboard(
                sumTotals(todaysRealised), todaysRealised.size(),
                sumTotals(monthRealised), monthRealised.size(),
                activeRoutes);
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
        BigDecimal avgFillRate = fillRates.isEmpty()
                ? BigDecimal.ZERO
                : fillRates.stream()
                    .map(FillRateRow::fillRatePercent)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(fillRates.size()), 1, RoundingMode.HALF_UP);

        return new InventoryDashboard(belowMin, aging, totalSkus, avgFillRate);
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
}
