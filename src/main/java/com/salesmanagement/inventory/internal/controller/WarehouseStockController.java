package com.salesmanagement.inventory.internal.controller;

import com.salesmanagement.inventory.internal.dto.ReceiveStockRequest;
import com.salesmanagement.inventory.internal.dto.SetWarehouseStockRequest;
import com.salesmanagement.inventory.internal.dto.WarehouseStockResponse;
import com.salesmanagement.inventory.internal.service.StockService;
import com.salesmanagement.shared.api.ApiResponse;
import com.salesmanagement.shared.api.PageRequest;
import com.salesmanagement.shared.api.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for central warehouse stock.
 *
 * <p><strong>Authorization (decision E):</strong> writes (set / receive) are open to
 * {@code ADMIN} and {@code WAREHOUSE_MANAGER} (FR-31). Reads are open to those two plus
 * {@code SALES_MANAGER} (oversight / reports) — but <em>not</em> to {@code SALES_REP}, who
 * cares about their own van stock, not central warehouse levels.</p>
 *
 * <p>Stock is addressed by {@code productId} (one warehouse row per product). There is no
 * stock-row id in the URL — the product is the natural key from the caller's perspective.</p>
 */
@RestController
@RequestMapping("/api/inventory/warehouse-stock")
@RequiredArgsConstructor
public class WarehouseStockController {

    private final StockService stockService;

    /**
     * Page of warehouse stock. ADMIN, WAREHOUSE_MANAGER, or SALES_MANAGER.
     * Optional filters: {@code ?productId=}, {@code ?lowStock=true} (only rows below
     * their reorder threshold — FR-32/FR-121). Pagination as usual.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER', 'SALES_MANAGER')")
    public ApiResponse<PageResponse<WarehouseStockResponse>> list(
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false, defaultValue = "false") boolean lowStock,
            @Valid PageRequest pageRequest) {
        return ApiResponse.ok(
                stockService.listWarehouseStock(productId, lowStock, pageRequest.toPageable()));
    }

    /** Warehouse stock for one product. ADMIN, WAREHOUSE_MANAGER, or SALES_MANAGER. 404 if none. */
    @GetMapping("/{productId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER', 'SALES_MANAGER')")
    public ApiResponse<WarehouseStockResponse> getByProduct(@PathVariable Long productId) {
        return ApiResponse.ok(stockService.getWarehouseStock(productId));
    }

    /**
     * Sets the absolute on-hand quantity (initial count / stock-take correction).
     * ADMIN or WAREHOUSE_MANAGER. Creates the row if it does not exist yet.
     */
    @PutMapping("/{productId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER')")
    public ApiResponse<WarehouseStockResponse> set(
            @PathVariable Long productId,
            @Valid @RequestBody SetWarehouseStockRequest request) {
        return ApiResponse.ok(
                stockService.setWarehouseStock(productId, request.quantity()),
                "Warehouse stock updated");
    }

    /**
     * Receives an incoming shipment (adds to on-hand quantity). ADMIN or WAREHOUSE_MANAGER.
     */
    @PostMapping("/{productId}/receive")
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER')")
    public ApiResponse<WarehouseStockResponse> receive(
            @PathVariable Long productId,
            @Valid @RequestBody ReceiveStockRequest request) {
        return ApiResponse.ok(
                stockService.receiveWarehouseStock(productId, request.quantity()),
                "Stock received");
    }
}
