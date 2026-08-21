package com.salesmanagement.vanops.internal.controller;

import com.salesmanagement.shared.api.ApiResponse;
import com.salesmanagement.shared.api.PageRequest;
import com.salesmanagement.shared.api.PageResponse;
import com.salesmanagement.vanops.internal.enums.ReturnSheetStatus;
import com.salesmanagement.vanops.internal.dto.CreateReturnSheetRequest;
import com.salesmanagement.vanops.internal.dto.ReturnSheetResponse;
import com.salesmanagement.vanops.internal.service.ReturnSheetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * REST API for return sheets — end-of-day reconciliation.
 *
 * <p><strong>Authorization:</strong> creating a draft is open to ADMIN, WAREHOUSE_MANAGER, and
 * SALES_REP (rep submits his own, or the WH manager records it on his behalf). Completing
 * is ADMIN/WAREHOUSE_MANAGER only — that's the step that actually moves stock back into
 * the warehouse.</p>
 */
@RestController
@RequestMapping("/api/return-sheets")
@RequiredArgsConstructor
public class ReturnSheetController {

    private final ReturnSheetService returnSheetService;

    /** Create a DRAFT return sheet. ADMIN, WAREHOUSE_MANAGER, or SALES_REP. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER', 'SALES_REP')")
    public ApiResponse<ReturnSheetResponse> create(@Valid @RequestBody CreateReturnSheetRequest request) {
        return ApiResponse.created(returnSheetService.create(request));
    }

    /** Complete a return sheet — physically moves stock van → warehouse. ADMIN or WAREHOUSE_MANAGER. */
    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER')")
    public ApiResponse<ReturnSheetResponse> complete(@PathVariable Long id) {
        return ApiResponse.ok(returnSheetService.complete(id), "Return sheet completed");
    }

    /**
     * Auto-generate a DRAFT return sheet from the rep's current van state.
     * End-of-day shortcut: no typing required, system reads the van and pre-fills the lines.
     * Warehouse manager then reviews and calls {@code /{id}/complete} to finalise.
     * ADMIN or WAREHOUSE_MANAGER.
     */
    @PostMapping("/auto-create")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER')")
    public ApiResponse<ReturnSheetResponse> autoCreate(@RequestParam Long representativeId) {
        return ApiResponse.created(returnSheetService.autoCreate(representativeId));
    }

    /** Read one return sheet. ADMIN, WAREHOUSE_MANAGER, or SALES_MANAGER. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER', 'SALES_MANAGER')")
    public ApiResponse<ReturnSheetResponse> getById(@PathVariable Long id) {
        return ApiResponse.ok(returnSheetService.getById(id));
    }

    /** Page of return sheets. ADMIN, WAREHOUSE_MANAGER, or SALES_MANAGER. */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER', 'SALES_MANAGER')")
    public ApiResponse<PageResponse<ReturnSheetResponse>> list(
            @RequestParam(required = false) Long representativeId,
            @RequestParam(required = false) ReturnSheetStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate returnDate,
            @Valid PageRequest pageRequest) {
        return ApiResponse.ok(
                returnSheetService.list(representativeId, status, returnDate, pageRequest.toPageable()));
    }
}