package com.salesmanagement.reporting.internal.controller;

import com.salesmanagement.reporting.internal.dto.DashboardDtos.InventoryDashboard;
import com.salesmanagement.reporting.internal.dto.DashboardDtos.SalesDashboard;
import com.salesmanagement.reporting.internal.service.DashboardService;
import com.salesmanagement.shared.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dashboard KPI endpoints — JSON-only, split by role so each dashboard is reachable only by the role it
 * serves (locked decision, option c). The split keeps authorization entirely on {@code @PreAuthorize}:
 * no role-aware payload logic in the service, and a warehouse manager simply cannot call the sales
 * dashboard (403), rather than calling it and receiving a trimmed body.
 */
@RestController
@RequestMapping("/api/reports/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

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
}
