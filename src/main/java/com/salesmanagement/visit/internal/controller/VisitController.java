package com.salesmanagement.visit.internal.controller;

import com.salesmanagement.shared.api.ApiResponse;
import com.salesmanagement.shared.api.PageRequest;
import com.salesmanagement.shared.api.PageResponse;
import com.salesmanagement.shared.security.UserPrincipal;
import com.salesmanagement.visit.internal.dto.CheckInRequest;
import com.salesmanagement.visit.internal.dto.CheckOutRequest;
import com.salesmanagement.visit.internal.dto.EndDayRequest;
import com.salesmanagement.visit.internal.dto.VisitResponse;
import com.salesmanagement.visit.internal.enums.VisitStatus;
import com.salesmanagement.visit.internal.service.VisitService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for field visits.
 *
 * <p><strong>Authorization (confirmed matrix):</strong> {@code SALES_REP} performs check-in,
 * check-out and end-day, always for their own routes ({@code representativeId} comes from the
 * JWT, never the body). {@code ADMIN} and {@code SALES_MANAGER} have read-only oversight over
 * any visit; a {@code SALES_REP} may read only their own (enforced in the service).
 * {@code WAREHOUSE_MANAGER} has no access.</p>
 *
 * <p>Reads accept an {@link Authentication} to distinguish a privileged reader (admin/manager,
 * sees any) from a rep (self-scoped), without re-fetching the role from identity.</p>
 */
@RestController
@RequestMapping("/api/visits")
@RequiredArgsConstructor
public class VisitController {

    private final VisitService visitService;

    /** Check in at a stop. SALES_REP only, own route. */
    @PostMapping("/check-in")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SALES_REP')")
    public ApiResponse<VisitResponse> checkIn(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CheckInRequest request) {
        return ApiResponse.created(visitService.checkIn(principal.getUserId(), request));
    }

    /** Check out of a stop. SALES_REP only, own route. */
    @PostMapping("/check-out")
    @PreAuthorize("hasRole('SALES_REP')")
    public ApiResponse<VisitResponse> checkOut(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CheckOutRequest request) {
        return ApiResponse.ok(visitService.checkOut(principal.getUserId(), request), "Checked out");
    }

    /** End the day for a route: remaining stops become MISSED. SALES_REP only, own route. */
    @PostMapping("/end-day")
    @PreAuthorize("hasRole('SALES_REP')")
    public ApiResponse<Void> endDay(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody EndDayRequest request) {
        visitService.endDay(principal.getUserId(), request);
        return ApiResponse.noContent("Day ended; remaining stops marked missed");
    }

    /** Read one visit. ADMIN/SALES_MANAGER: any. SALES_REP: own only. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'SALES_REP')")
    public ApiResponse<VisitResponse> getById(
            @AuthenticationPrincipal UserPrincipal principal,
            Authentication authentication,
            @PathVariable Long id) {
        return ApiResponse.ok(
                visitService.getById(principal.getUserId(), isPrivileged(authentication), id));
    }

    /**
     * Page of visits. Optional filters: representativeId, routeId, customerId, status.
     * ADMIN/SALES_MANAGER see any; a SALES_REP is forced to their own visits.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER', 'SALES_REP')")
    public ApiResponse<PageResponse<VisitResponse>> list(
            @AuthenticationPrincipal UserPrincipal principal,
            Authentication authentication,
            @RequestParam(required = false) Long representativeId,
            @RequestParam(required = false) Long routeId,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) VisitStatus status,
            @Valid PageRequest pageRequest) {
        return ApiResponse.ok(visitService.list(
                principal.getUserId(),
                isPrivileged(authentication),
                representativeId,
                routeId,
                customerId,
                status,
                pageRequest.toPageable()));
    }

    /** True for ADMIN or SALES_MANAGER (oversight over any visit). */
    private boolean isPrivileged(Authentication authentication) {
        for (GrantedAuthority a : authentication.getAuthorities()) {
            String role = a.getAuthority();
            if ("ROLE_ADMIN".equals(role) || "ROLE_SALES_MANAGER".equals(role)) {
                return true;
            }
        }
        return false;
    }
}
