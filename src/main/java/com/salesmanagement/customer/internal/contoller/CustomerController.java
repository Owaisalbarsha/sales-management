package com.salesmanagement.customer.internal.contoller;

import com.salesmanagement.customer.internal.CustomerStatus;
import com.salesmanagement.customer.internal.dto.ChangeCustomerStatusRequest;
import com.salesmanagement.customer.internal.dto.CreateCustomerRequest;
import com.salesmanagement.customer.internal.dto.CustomerResponse;
import com.salesmanagement.customer.internal.dto.UpdateCustomerRequest;
import com.salesmanagement.customer.internal.service.CustomerService;
import com.salesmanagement.shared.api.ApiResponse;
import com.salesmanagement.shared.api.PageRequest;
import com.salesmanagement.shared.api.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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

/**
 * REST API for customers.
 *
 * <p><strong>Authorization (confirm against the SRS use cases):</strong> customers
 * are operational data managed by the field-sales organisation, so writes are open
 * to {@code ADMIN} and {@code SALES_MANAGER}. Reads are open to any authenticated
 * user — {@code SALES_REP} needs to see the customers on their route, and routing,
 * visit, and reporting screens all display customer data. This is deliberately
 * broader than the territory module, where writes are {@code ADMIN}-only because
 * territories are administrative reference data.</p>
 *
 * <p>Lifecycle changes use explicit {@code /activate} and {@code /deactivate}
 * endpoints rather than a status field on the update body, so the transition is
 * clear in logs and access rules. Every method returns {@link ApiResponse} per the
 * system-wide contract; role enforcement is declarative via {@code @PreAuthorize}.</p>
 */
@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    /** Creates a customer. ADMIN or SALES_MANAGER. Returns 201. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public ApiResponse<CustomerResponse> create(@Valid @RequestBody CreateCustomerRequest request) {
        return ApiResponse.created(customerService.create(request));
    }

    /** Returns a single customer by id. Any authenticated user. */
    @GetMapping("/{id}")
    public ApiResponse<CustomerResponse> getById(@PathVariable Long id) {
        return ApiResponse.ok(customerService.getById(id));
    }

    /**
     * Returns a page of customers. Any authenticated user.
     * Optional filters: {@code ?territoryId=}, {@code ?status=ACTIVE|INACTIVE}.
     * Pagination: {@code ?page, ?size, ?sortBy, ?sortDir}.
     */
    @GetMapping
    public ApiResponse<PageResponse<CustomerResponse>> list(
            @RequestParam(required = false) Long territoryId,
            @RequestParam(required = false) CustomerStatus status,
            @RequestParam(required = false) String search,
            @Valid PageRequest pageRequest) {
        return ApiResponse.ok(
                customerService.list(territoryId, status, search, pageRequest.toPageable()));
    }

    /** Partially updates a customer. ADMIN or SALES_MANAGER. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public ApiResponse<CustomerResponse> update(@PathVariable Long id,
                                                @Valid @RequestBody UpdateCustomerRequest request) {
        return ApiResponse.ok(customerService.update(id, request), "Customer updated");
    }

    /**
     * Changes a customer's lifecycle status (ACTIVE ↔ INACTIVE). ADMIN or SALES_MANAGER.
     * INACTIVE customers cannot be invoiced (FR-95).
     */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public ApiResponse<CustomerResponse> changeStatus(
            @PathVariable Long id,
            @Valid @RequestBody ChangeCustomerStatusRequest request) {
        return ApiResponse.ok(
                customerService.changeStatus(id, request.status()),
                "Customer status changed to " + request.status().name());
    }

    /**
     * Deletes a customer. ADMIN or SALES_MANAGER.
     * Fails with 409 if the customer is still referenced by visits, invoices,
     * or route assignments.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        customerService.delete(id);
        return ApiResponse.noContent("Customer deleted");
    }
}