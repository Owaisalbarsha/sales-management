package com.salesmanagement.inventory.internal.controller;

import com.salesmanagement.inventory.internal.dto.CreateStockCountRequest;
import com.salesmanagement.inventory.internal.dto.StockCountResponse;
import com.salesmanagement.inventory.internal.dto.UpdateStockCountLinesRequest;
import com.salesmanagement.inventory.internal.service.StockCountService;
import com.salesmanagement.shared.api.ApiResponse;
import com.salesmanagement.shared.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST API for physical stock counts — the write half of FR-124 (Stock Variance Report).
 *
 * <p><strong>Authorization:</strong> only ADMIN and WAREHOUSE_MANAGER may open, edit, and
 * finalize counts (they run the warehouse). Reads are open to those two plus SALES_MANAGER for
 * oversight. A SALES_REP has no access. The variance itself is served to the {@code reporting}
 * module through {@code InventoryFacade}, not this controller.</p>
 *
 * <p>The owner's id is taken from the authenticated principal, never the request body — a manager
 * cannot record a count "as" someone else.</p>
 */
@RestController
@RequestMapping("/api/stock-counts")
@RequiredArgsConstructor
public class StockCountController {

    private final StockCountService stockCountService;

    /** Open a new DRAFT stock count. ADMIN or WAREHOUSE_MANAGER. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER')")
    public ApiResponse<StockCountResponse> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateStockCountRequest request) {
        return ApiResponse.created(stockCountService.createDraft(principal.getUserId(), request));
    }

    /** Replace the lines of a DRAFT count. ADMIN or WAREHOUSE_MANAGER. */
    @PutMapping("/{id}/lines")
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER')")
    public ApiResponse<StockCountResponse> updateLines(
            @PathVariable Long id,
            @Valid @RequestBody UpdateStockCountLinesRequest request) {
        return ApiResponse.ok(stockCountService.updateLines(id, request), "Stock count updated");
    }

    /** Finalize a count — snapshots recorded stock and freezes it. ADMIN or WAREHOUSE_MANAGER. */
    @PostMapping("/{id}/finalize")
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER')")
    public ApiResponse<StockCountResponse> finalizeCount(@PathVariable Long id) {
        return ApiResponse.ok(stockCountService.finalizeCount(id), "Stock count finalized");
    }

    /** Read one stock count. ADMIN, WAREHOUSE_MANAGER, or SALES_MANAGER. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER', 'SALES_MANAGER')")
    public ApiResponse<StockCountResponse> getById(@PathVariable Long id) {
        return ApiResponse.ok(stockCountService.getById(id));
    }

    /** List all stock counts, newest first. ADMIN, WAREHOUSE_MANAGER, or SALES_MANAGER. */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER', 'SALES_MANAGER')")
    public ApiResponse<List<StockCountResponse>> list() {
        return ApiResponse.ok(stockCountService.listAll());
    }
}
