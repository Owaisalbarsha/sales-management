package com.salesmanagement.reporting.internal.controller;

import com.salesmanagement.reporting.internal.dto.DashboardAnalyticsDtos.InventoryAnalytics;
import com.salesmanagement.reporting.internal.dto.DashboardAnalyticsDtos.SalesAnalytics;
import com.salesmanagement.reporting.internal.dto.DashboardDtos.InventoryDashboard;
import com.salesmanagement.reporting.internal.dto.DashboardDtos.SalesDashboard;
import com.salesmanagement.reporting.internal.service.DashboardAnalyticsService;
import com.salesmanagement.reporting.internal.service.DashboardService;
import com.salesmanagement.reporting.internal.support.DateRangeResolver;
import com.salesmanagement.reporting.internal.support.DateRangeResolver.DateRange;
import com.salesmanagement.reporting.internal.support.Granularity;
import com.salesmanagement.shared.api.ApiResponse;
import com.salesmanagement.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Dashboard KPI endpoints — JSON-only, split by role so each dashboard is reachable only by the role it
 * serves (locked decision, option c). The split keeps authorization entirely on {@code @PreAuthorize}:
 * no role-aware payload logic in the service, and a warehouse manager simply cannot call the sales
 * dashboard (403), rather than calling it and receiving a trimmed body.
 *
 * <p>Two tiers per dashboard. The bare endpoint returns the headline tiles for a fixed today/this-month
 * window and takes no parameters — that is what makes it a stable, cacheable landing call. The
 * {@code /analytics} endpoint returns the chart data for a caller-chosen window. They are separate
 * endpoints rather than one parameterised one so the tiles' contract never shifts under the frontend
 * that already depends on it.</p>
 */
@RestController
@RequestMapping("/api/reports/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    /** Rankings default to a chart-sized handful. */
    private static final int DEFAULT_TOP = 5;

    /**
     * Upper bound on {@code top}. Not arbitrary: these are bar charts, and a request for 500 rows is
     * either a mistake or an attempt to use the dashboard as a bulk export — for which the
     * {@code /api/reports/*} endpoints already exist and paginate/export properly.
     */
    private static final int MAX_TOP = 20;

    private final DashboardService dashboardService;
    private final DashboardAnalyticsService dashboardAnalyticsService;

    /** Sales KPI tiles. ADMIN and SALES_MANAGER only. */
    @GetMapping("/sales")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public ApiResponse<SalesDashboard> sales() {
        return ApiResponse.ok(dashboardService.salesDashboard());
    }

    /** Inventory KPI tiles. ADMIN and WAREHOUSE_MANAGER only. */
    @GetMapping("/inventory")
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER')")
    public ApiResponse<InventoryDashboard> inventory() {
        return ApiResponse.ok(dashboardService.inventoryDashboard());
    }

    /**
     * Sales analytics: trends and rankings for the chart layer. ADMIN and SALES_MANAGER only — same
     * audience as the sales tiles, since this is strictly more detail about the same figures.
     *
     * @param from        inclusive window start; defaults with {@code to} per {@link DateRangeResolver}
     * @param to          EXCLUSIVE window end (the project-wide half-open convention)
     * @param granularity {@code DAY} (default), {@code WEEK} or {@code MONTH} — trend bucket size
     * @param top         rows per ranking, 1..20, default 5
     */
    @GetMapping("/sales/analytics")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public ApiResponse<SalesAnalytics> salesAnalytics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String granularity,
            @RequestParam(required = false) Integer top) {

        DateRange range = DateRangeResolver.resolve(from, to);
        return ApiResponse.ok(dashboardAnalyticsService.salesAnalytics(
                range, Granularity.parse(granularity), resolveTop(top)));
    }

    /**
     * Inventory analytics: stock health, movement and fill-rate trends, and the product rankings.
     * ADMIN and WAREHOUSE_MANAGER only.
     *
     * <p>Same parameters as the sales analytics endpoint. Note that stock health and stock value are
     * current-state snapshots and do not vary with the window — the payload says so explicitly rather
     * than implying a history the system does not keep.</p>
     */
    @GetMapping("/inventory/analytics")
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER')")
    public ApiResponse<InventoryAnalytics> inventoryAnalytics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String granularity,
            @RequestParam(required = false) Integer top) {

        DateRange range = DateRangeResolver.resolve(from, to);
        return ApiResponse.ok(dashboardAnalyticsService.inventoryAnalytics(
                range, Granularity.parse(granularity), resolveTop(top)));
    }

    /**
     * Applies the default and the bounds to {@code top}. An out-of-range value is rejected rather than
     * clamped: silently returning 20 rows to someone who asked for 100 makes the response a lie about
     * what was requested, and the caller has no way to notice.
     *
     * @throws BusinessException 400 if {@code top} is outside {@code [1, 20]}
     */
    private int resolveTop(Integer top) {
        if (top == null) {
            return DEFAULT_TOP;
        }
        if (top < 1 || top > MAX_TOP) {
            throw BusinessException.badRequest(
                    "Parameter 'top' must be between 1 and " + MAX_TOP + " (was " + top + ").",
                    "REPORT_TOP_N_INVALID");
        }
        return top;
    }
}
