package com.salesmanagement.reporting.internal.controller;

import com.salesmanagement.reporting.internal.dto.DashboardAnalyticsDtos.InventoryAnalytics;
import com.salesmanagement.reporting.internal.dto.DashboardAnalyticsDtos.RouteOutcomes;
import com.salesmanagement.reporting.internal.dto.DashboardAnalyticsDtos.SalesAnalytics;
import com.salesmanagement.reporting.internal.dto.DashboardAnalyticsDtos.SalesPeriodSummary;
import com.salesmanagement.reporting.internal.dto.DashboardAnalyticsDtos.StockHealth;
import com.salesmanagement.reporting.internal.service.DashboardAnalyticsService;
import com.salesmanagement.reporting.internal.service.DashboardService;
import com.salesmanagement.shared.security.UserPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authorization matrix for {@link DashboardController}, plus the two request-parameter guards.
 *
 * <p><strong>Why the role matrix is worth testing here even though the expressions are plain.</strong>
 * The dashboards are role-split by design — that split <em>is</em> the feature, not an incidental
 * annotation — and the two new analytics endpoints must inherit exactly the split their tile
 * counterparts have. A copy-paste that gave the inventory analytics the sales role list would leak the
 * warehouse's stock valuation to sales managers and nothing else in the suite would notice. Four lines
 * of test per endpoint is cheap insurance against a silent widening.</p>
 *
 * <p>The service layer is mocked: these tests are about who gets through the door, not about what is
 * on the other side of it.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardControllerSecurityTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean DashboardService dashboardService;
    @MockitoBean DashboardAnalyticsService dashboardAnalyticsService;

    private static final String SALES = "/api/reports/dashboard/sales/analytics";
    private static final String INVENTORY = "/api/reports/dashboard/inventory/analytics";

    // ── sales analytics: ADMIN + SALES_MANAGER ────────────────────────────────

    @Test
    @DisplayName("sales analytics: ADMIN is allowed")
    void salesAnalytics_admin_ok() throws Exception {
        stubSales();
        mockMvc.perform(get(SALES).with(authentication(as("ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("sales analytics: SALES_MANAGER is allowed")
    void salesAnalytics_salesManager_ok() throws Exception {
        stubSales();
        mockMvc.perform(get(SALES).with(authentication(as("SALES_MANAGER"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("sales analytics: WAREHOUSE_MANAGER is refused")
    void salesAnalytics_warehouseManager_forbidden() throws Exception {
        mockMvc.perform(get(SALES).with(authentication(as("WAREHOUSE_MANAGER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("sales analytics: SALES_REP is refused")
    void salesAnalytics_salesRep_forbidden() throws Exception {
        mockMvc.perform(get(SALES).with(authentication(as("SALES_REP"))))
                .andExpect(status().isForbidden());
    }

    // ── inventory analytics: ADMIN + WAREHOUSE_MANAGER ────────────────────────

    @Test
    @DisplayName("inventory analytics: ADMIN is allowed")
    void inventoryAnalytics_admin_ok() throws Exception {
        stubInventory();
        mockMvc.perform(get(INVENTORY).with(authentication(as("ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("inventory analytics: WAREHOUSE_MANAGER is allowed")
    void inventoryAnalytics_warehouseManager_ok() throws Exception {
        stubInventory();
        mockMvc.perform(get(INVENTORY).with(authentication(as("WAREHOUSE_MANAGER"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("inventory analytics: SALES_MANAGER is refused")
    void inventoryAnalytics_salesManager_forbidden() throws Exception {
        mockMvc.perform(get(INVENTORY).with(authentication(as("SALES_MANAGER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("inventory analytics: SALES_REP is refused")
    void inventoryAnalytics_salesRep_forbidden() throws Exception {
        mockMvc.perform(get(INVENTORY).with(authentication(as("SALES_REP"))))
                .andExpect(status().isForbidden());
    }

    // ── the existing tile endpoints keep their own split ──────────────────────

    @Test
    @DisplayName("the pre-existing tile endpoints still enforce their original split")
    void tileEndpointsUnchanged() throws Exception {
        mockMvc.perform(get("/api/reports/dashboard/sales")
                        .with(authentication(as("WAREHOUSE_MANAGER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/reports/dashboard/inventory")
                        .with(authentication(as("SALES_MANAGER"))))
                .andExpect(status().isForbidden());
    }

    // ── parameter guards ──────────────────────────────────────────────────────

    @Test
    @DisplayName("an out-of-range 'top' is rejected rather than silently clamped")
    void topOutOfRangeIsRejected() throws Exception {
        mockMvc.perform(get(SALES).param("top", "50").with(authentication(as("ADMIN"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(get(SALES).param("top", "0").with(authentication(as("ADMIN"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an unknown granularity is rejected rather than silently falling back to DAY")
    void unknownGranularityIsRejected() throws Exception {
        mockMvc.perform(get(SALES).param("granularity", "FORTNIGHT")
                        .with(authentication(as("ADMIN"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an inverted window is rejected by the shared date-range resolver")
    void invertedWindowIsRejected() throws Exception {
        mockMvc.perform(get(SALES)
                        .param("from", "2026-09-01").param("to", "2026-08-01")
                        .with(authentication(as("ADMIN"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an empty period is 200 with a structurally valid body, never 404")
    void emptyPeriodIsOkNotNotFound() throws Exception {
        stubSales();
        mockMvc.perform(get(SALES)
                        .param("from", "2001-01-01").param("to", "2001-01-08")
                        .with(authentication(as("ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.salesTrend").isArray());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void stubSales() {
        given(dashboardAnalyticsService.salesAnalytics(any(), any(), anyInt()))
                .willReturn(new SalesAnalytics(
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1), "DAY",
                        new SalesPeriodSummary(BigDecimal.ZERO, 0, BigDecimal.ZERO, 0),
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        new RouteOutcomes(0, 0, 0, 0, 0, BigDecimal.ZERO)));
    }

    private void stubInventory() {
        given(dashboardAnalyticsService.inventoryAnalytics(any(), any(), anyInt()))
                .willReturn(new InventoryAnalytics(
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1), "DAY",
                        new StockHealth(0, 0, 0, 0),
                        List.of(), List.of(), List.of(), List.of(), List.of()));
    }

    /**
     * An authenticated principal carrying exactly one role. Mockito mock rather than a constructed
     * {@code UserPrincipal} so these tests survive a constructor change on that class — method
     * security consults only the authorities here.
     */
    private static Authentication as(String role) {
        UserPrincipal principal = mock(UserPrincipal.class);
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
