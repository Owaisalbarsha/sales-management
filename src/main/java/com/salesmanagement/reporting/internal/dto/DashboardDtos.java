package com.salesmanagement.reporting.internal.dto;

import java.math.BigDecimal;

/**
 * Headline KPI tiles for the two role-split dashboards. JSON-only — a dashboard is a live view, not a
 * document, so there is no xlsx/pdf path here.
 *
 * <p>Split by role, not merged with a role-aware payload: the sales manager hits {@code /dashboard/sales},
 * the warehouse manager hits {@code /dashboard/inventory}, and {@code @PreAuthorize} enforces the line.
 * Keeping authorization on the controller (not branching the payload in the service) is the locked
 * decision — no auth logic leaks into the service layer.</p>
 */
public final class DashboardDtos {

    private DashboardDtos() {}

    /**
     * Sales dashboard tiles.
     *
     * @param todaySalesTotal    sum of realised invoice totals dated today (business zone)
     * @param todayInvoiceCount  number of realised invoices today
     * @param monthSalesTotal    sum of realised invoice totals this calendar month
     * @param monthInvoiceCount  number of realised invoices this month
     * @param activeRoutesToday  routes dated today that are ACTIVE (in-progress in the field)
     */
    public record SalesDashboard(
            BigDecimal todaySalesTotal,
            long       todayInvoiceCount,
            BigDecimal monthSalesTotal,
            long       monthInvoiceCount,
            long       activeRoutesToday
    ) {}

    /**
     * Inventory dashboard tiles.
     *
     * @param belowMinimumCount products currently under their minimum
     * @param agingCount        products with no outbound movement in the configured aging window
     * @param totalSkus         distinct products carried in the warehouse
     * @param monthFillRatePercent average fill rate across products this month (0 if no demand)
     */
    public record InventoryDashboard(
            long       belowMinimumCount,
            long       agingCount,
            long       totalSkus,
            BigDecimal monthFillRatePercent
    ) {}
}
