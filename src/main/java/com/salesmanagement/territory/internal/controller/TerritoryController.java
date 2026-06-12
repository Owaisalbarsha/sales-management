package com.salesmanagement.territory.internal.controller;

import com.salesmanagement.shared.api.ApiResponse;
import com.salesmanagement.shared.api.PageRequest;
import com.salesmanagement.shared.api.PageResponse;
import com.salesmanagement.territory.internal.dto.CreateTerritoryRequest;
import com.salesmanagement.territory.internal.dto.TerritoryResponse;
import com.salesmanagement.territory.internal.dto.UpdateTerritoryRequest;
import com.salesmanagement.territory.internal.service.TerritoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for territories.
 *
 * <p>Reference-data CRUD. Reads are open to any authenticated user (customer,
 * routing, and reporting screens all need to display territory names). Writes are
 * restricted to {@code ADMIN} — territories are administrative configuration, not
 * operational data anyone should change.</p>
 *
 * <p>Every method returns {@link ApiResponse} per the system-wide contract.
 * Role enforcement is declarative via {@code @PreAuthorize}; no role checks live
 * in the service.</p>
 */
@RestController
@RequestMapping("/api/territories")
@RequiredArgsConstructor
public class TerritoryController {

    private final TerritoryService territoryService;

    /**
     * Creates a territory. ADMIN only. Returns 201.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<TerritoryResponse> create(@Valid @RequestBody CreateTerritoryRequest request) {
        return ApiResponse.created(territoryService.create(request));
    }

    /**
     * Returns a single territory by id. Any authenticated user.
     */
    @GetMapping("/{id}")
    public ApiResponse<TerritoryResponse> getById(@PathVariable Long id) {
        return ApiResponse.ok(territoryService.getById(id));
    }

    /**
     * Returns a page of territories. Any authenticated user.
     * Accepts ?page, ?size, ?sortBy, ?sortDir as query params.
     */
    @GetMapping
    public ApiResponse<PageResponse<TerritoryResponse>> list(
            @Valid PageRequest pageRequest,
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "false") boolean all
    ) {
        return ApiResponse.ok(
                territoryService.list(search, all, pageRequest.toPageable())
        );
    }

    /**
     * Fully updates a territory. ADMIN only.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<TerritoryResponse> update(@PathVariable Long id,
                                                 @Valid @RequestBody UpdateTerritoryRequest request) {
        return ApiResponse.ok(territoryService.update(id, request), "Territory updated");
    }

    /**
     * Deletes a territory. ADMIN only. Fails with 409 if customers still reference it.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        territoryService.delete(id);
        return ApiResponse.noContent("Territory deleted");
    }
}