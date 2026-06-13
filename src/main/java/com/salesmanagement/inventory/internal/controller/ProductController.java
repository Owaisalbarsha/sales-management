package com.salesmanagement.inventory.internal.controller;

import com.salesmanagement.inventory.internal.ProductStatus;
import com.salesmanagement.inventory.internal.dto.ChangeProductStatusRequest;
import com.salesmanagement.inventory.internal.dto.CreateProductRequest;
import com.salesmanagement.inventory.internal.dto.ProductResponse;
import com.salesmanagement.inventory.internal.dto.UpdateProductRequest;
import com.salesmanagement.inventory.internal.service.ProductService;
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
 * REST API for the product catalog.
 *
 * <p><strong>Authorization (decision E):</strong> products are master data managed by
 * the warehouse organisation, so writes are open to {@code ADMIN} and
 * {@code WAREHOUSE_MANAGER} (FR-29). Reads are open to any authenticated user — sales
 * reps need the catalog on the mobile app to build invoices and restock requests, and
 * routing/visit/reporting screens display product data.</p>
 *
 * <p>Lifecycle changes use the dedicated {@code PATCH /{id}/status} endpoint, matching
 * the customer module. Every method returns {@link ApiResponse}; role enforcement is
 * declarative via {@code @PreAuthorize}.</p>
 */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    /** Creates a product. ADMIN or WAREHOUSE_MANAGER. Returns 201. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER')")
    public ApiResponse<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
        return ApiResponse.created(productService.create(request));
    }

    /** Returns a single product by id. Any authenticated user. */
    @GetMapping("/{id}")
    public ApiResponse<ProductResponse> getById(@PathVariable Long id) {
        return ApiResponse.ok(productService.getById(id));
    }

    /**
     * Returns a page of products. Any authenticated user.
     * Optional filters: {@code ?status=ACTIVE|DISCONTINUED}, {@code ?search=} (name or SKU).
     * Pagination: {@code ?page, ?size, ?sortBy, ?sortDir}.
     */
    @GetMapping
    public ApiResponse<PageResponse<ProductResponse>> list(
            @RequestParam(required = false) ProductStatus status,
            @RequestParam(required = false) String search,
            @Valid PageRequest pageRequest) {
        return ApiResponse.ok(productService.list(status, search, pageRequest.toPageable()));
    }

    /** Partially updates a product. ADMIN or WAREHOUSE_MANAGER. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER')")
    public ApiResponse<ProductResponse> update(@PathVariable Long id,
                                               @Valid @RequestBody UpdateProductRequest request) {
        return ApiResponse.ok(productService.update(id, request), "Product updated");
    }

    /**
     * Changes a product's lifecycle status (ACTIVE ↔ DISCONTINUED). ADMIN or WAREHOUSE_MANAGER.
     */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER')")
    public ApiResponse<ProductResponse> changeStatus(
            @PathVariable Long id,
            @Valid @RequestBody ChangeProductStatusRequest request) {
        return ApiResponse.ok(
                productService.changeStatus(id, request.status()),
                "Product status changed to " + request.status().name());
    }

    /**
     * Deletes a product. ADMIN or WAREHOUSE_MANAGER.
     * Fails with 409 if the product is still referenced by stock, invoices, or restock items.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return ApiResponse.noContent("Product deleted");
    }
}
