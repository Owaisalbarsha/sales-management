package com.salesmanagement.vanops.internal.controller;

import com.salesmanagement.shared.api.ApiResponse;
import com.salesmanagement.shared.api.PageRequest;
import com.salesmanagement.shared.api.PageResponse;
import com.salesmanagement.shared.security.UserPrincipal;
import com.salesmanagement.vanops.internal.enums.DemandOrderStatus;
import com.salesmanagement.vanops.internal.dto.CreateDemandOrderRequest;
import com.salesmanagement.vanops.internal.dto.DemandOrderResponse;
import com.salesmanagement.vanops.internal.service.DemandOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * REST API for demand orders — the morning fill workflow.
 *
 * <p><strong>Authorization:</strong> sales managers (and admin) submit; warehouse managers
 * (and admin) load. Reads are open to admin/sales-manager/warehouse-manager (oversight); a
 * {@code SALES_REP} is intentionally <em>not</em> given read access (they don't read paperwork,
 * they look at their van inventory).</p>
 *
 * <p>The submitter's id is taken from the authenticated principal, not the request body —
 * a manager cannot submit "as" another manager.</p>
 */
@RestController
@RequestMapping("/api/demand-orders")
@RequiredArgsConstructor
public class DemandOrderController {

    private final DemandOrderService demandOrderService;

    /** Submit a new demand order. ADMIN or SALES_MANAGER. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public ApiResponse<DemandOrderResponse> submit(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateDemandOrderRequest request) {
        return ApiResponse.created(demandOrderService.submit(principal.getUserId(), request));
    }

    /** Mark a demand order LOADED — physically moves stock warehouse → van. ADMIN or WAREHOUSE_MANAGER. */
    @PostMapping("/{id}/load")
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER')")
    public ApiResponse<DemandOrderResponse> load(@PathVariable Long id) {
        return ApiResponse.ok(demandOrderService.load(id), "Demand order loaded");
    }

    /** Read one demand order. ADMIN, SALES_MANAGER, or WAREHOUSE_MANAGER. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'WAREHOUSE_MANAGER')")
    public ApiResponse<DemandOrderResponse> getById(@PathVariable Long id) {
        return ApiResponse.ok(demandOrderService.getById(id));
    }

    /**
     * Page of demand orders. Optional filters: representativeId, status, orderDate.
     * ADMIN, SALES_MANAGER, or WAREHOUSE_MANAGER.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'WAREHOUSE_MANAGER')")
    public ApiResponse<PageResponse<DemandOrderResponse>> list(
            @RequestParam(required = false) Long representativeId,
            @RequestParam(required = false) DemandOrderStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate orderDate,
            @Valid PageRequest pageRequest) {
        return ApiResponse.ok(
                demandOrderService.list(representativeId, status, orderDate, pageRequest.toPageable()));
    }
}