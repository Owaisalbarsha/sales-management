package com.salesmanagement.routing.internal.controller;

import com.salesmanagement.routing.internal.dto.AssignCustomersRequest;
import com.salesmanagement.routing.internal.dto.CreateRouteRequest;
import com.salesmanagement.routing.internal.dto.ReorderRouteRequest;
import com.salesmanagement.routing.internal.dto.RouteResponse;
import com.salesmanagement.routing.internal.dto.UpdateRouteRequest;
import com.salesmanagement.routing.internal.dto.UpdateRouteStatusRequest;
import com.salesmanagement.routing.internal.enums.RouteStatus;
import com.salesmanagement.routing.internal.service.RouteService;
import com.salesmanagement.shared.api.ApiResponse;
import com.salesmanagement.shared.api.PageRequest;
import com.salesmanagement.shared.api.PageResponse;
import com.salesmanagement.shared.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * REST API for routes.
 *
 * <p><strong>Authorization:</strong> managers (and admin) own route management — create,
 * edit, assign/remove/reorder stops, optimise, advance status, list, read, delete. A
 * {@code SALES_REP} can only read their <em>own</em> route via {@code GET /api/routes/me};
 * they never see another rep's route and never mutate one. {@code WAREHOUSE_MANAGER} has no
 * access. There is no manager→rep scoping in the schema, so any manager may manage any
 * rep's route (consistent with vanops demand orders).</p>
 */
@RestController
@RequestMapping("/api/routes")
@RequiredArgsConstructor
public class RouteController {

    private final RouteService routeService;

    /** Create a route (optionally with initial stops). ADMIN or SALES_MANAGER. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public ApiResponse<RouteResponse> create(@Valid @RequestBody CreateRouteRequest request) {
        return ApiResponse.created(routeService.create(request));
    }

    /** Edit the route header (name/date). ADMIN or SALES_MANAGER. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public ApiResponse<RouteResponse> update(@PathVariable Long id,
                                             @Valid @RequestBody UpdateRouteRequest request) {
        return ApiResponse.ok(routeService.update(id, request), "Route updated");
    }

    /** Append customers to the route. ADMIN or SALES_MANAGER. */
    @PostMapping("/{id}/customers")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public ApiResponse<RouteResponse> assignCustomers(@PathVariable Long id,
                                                      @Valid @RequestBody AssignCustomersRequest request) {
        return ApiResponse.ok(routeService.assignCustomers(id, request), "Customers assigned");
    }

    /** Remove one customer from the route. ADMIN or SALES_MANAGER. */
    @DeleteMapping("/{id}/customers/{customerId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public ApiResponse<RouteResponse> removeCustomer(@PathVariable Long id,
                                                     @PathVariable Long customerId) {
        return ApiResponse.ok(routeService.removeCustomer(id, customerId), "Customer removed");
    }

    /** Manually set the visit order. ADMIN or SALES_MANAGER. */
    @PutMapping("/{id}/sequence")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public ApiResponse<RouteResponse> reorder(@PathVariable Long id,
                                              @Valid @RequestBody ReorderRouteRequest request) {
        return ApiResponse.ok(routeService.reorder(id, request), "Route reordered");
    }

    /** Auto-optimise the visit order. ADMIN or SALES_MANAGER. */
    @PostMapping("/{id}/optimize")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public ApiResponse<RouteResponse> optimize(@PathVariable Long id) {
        return ApiResponse.ok(routeService.optimize(id), "Route optimised");
    }

    /** Advance the route status (PLANNED -> ACTIVE -> COMPLETED). ADMIN or SALES_MANAGER. */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public ApiResponse<RouteResponse> updateStatus(@PathVariable Long id,
                                                   @Valid @RequestBody UpdateRouteStatusRequest request) {
        return ApiResponse.ok(routeService.updateStatus(id, request.status()), "Route status updated");
    }

    /** Read one route. ADMIN or SALES_MANAGER. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public ApiResponse<RouteResponse> getById(@PathVariable Long id) {
        return ApiResponse.ok(routeService.getById(id));
    }

    /** Page of routes. Optional filters: representativeId, status, routeDate. ADMIN or SALES_MANAGER. */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public ApiResponse<PageResponse<RouteResponse>> list(
            @RequestParam(required = false) Long representativeId,
            @RequestParam(required = false) RouteStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate routeDate,
            @Valid PageRequest pageRequest) {
        return ApiResponse.ok(
                routeService.list(representativeId, status, routeDate, pageRequest.toPageable()));
    }

    /** Delete a PLANNED route. ADMIN or SALES_MANAGER. */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        routeService.delete(id);
        return ApiResponse.noContent("Route deleted");
    }

    /**
     * A sales rep reads their own route for a day (defaults to today). The rep id comes from
     * the authenticated principal, so a rep can only ever see their own route. ADMIN or SALES_REP.
     */
    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_REP')")
    public ApiResponse<RouteResponse> myRoute(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate target = date != null ? date : LocalDate.now();
        return ApiResponse.ok(routeService.getRouteForRepOnDate(principal.getUserId(), target));
    }
}
