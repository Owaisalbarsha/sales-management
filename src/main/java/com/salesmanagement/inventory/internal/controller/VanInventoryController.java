package com.salesmanagement.inventory.internal.controller;

import com.salesmanagement.inventory.internal.dto.VanInventoryResponse;
import com.salesmanagement.inventory.internal.service.StockService;
import com.salesmanagement.shared.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST API for van inventory — <strong>read-only by design</strong>.
 *
 * <p><strong>Authorization (decision E):</strong> {@code ADMIN}, {@code WAREHOUSE_MANAGER},
 * and {@code SALES_MANAGER} may view any rep's van (oversight / reports); a
 * {@code SALES_REP} may view only their own. Van quantities are never mutated through a
 * REST endpoint — they change only via {@code InventoryFacade}
 * ({@code transferWarehouseToVan} on restock approval, {@code deductVanStock} on a sale),
 * which keeps the BR-4 invariant in one place.</p>
 *
 * <p><strong>Note — verify against your shared {@code UserPrincipal}:</strong> the
 * self-access clause {@code #representativeId == authentication.principal.id} assumes the
 * JWT principal exposes {@code getId()} returning the user's {@code Long} id. If your
 * {@code UserPrincipal} names that accessor differently, adjust the SpEL accordingly.</p>
 */
@RestController
@RequestMapping("/api/inventory/van")
@RequiredArgsConstructor
public class VanInventoryController {

    private final StockService stockService;

    /**
     * Van inventory for a representative.
     * Managers/admin/warehouse may view any rep; a SALES_REP may view only their own.
     */
    @GetMapping("/{representativeId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER', 'SALES_MANAGER') "
            + "or #representativeId == authentication.principal.id")
    public ApiResponse<List<VanInventoryResponse>> getByRepresentative(
            @PathVariable Long representativeId) {
        return ApiResponse.ok(stockService.getVanInventory(representativeId));
    }
}
