package com.salesmanagement.inventory.internal.service;

import com.salesmanagement.inventory.internal.Product;
import com.salesmanagement.inventory.internal.ProductStatus;
import com.salesmanagement.inventory.internal.dto.CreateProductRequest;
import com.salesmanagement.inventory.internal.dto.ProductResponse;
import com.salesmanagement.inventory.internal.dto.UpdateProductRequest;
import com.salesmanagement.inventory.internal.repository.ProductRepository;
import com.salesmanagement.shared.api.PageResponse;
import com.salesmanagement.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for the product catalog.
 *
 * <p>Owns all product operations and is the only writer of the {@link Product}
 * entity. Mutating methods are transactional; reads are {@code readOnly}. Mirrors
 * {@code CustomerService} in structure.</p>
 *
 * <p>Error contract (all via {@link BusinessException}, mapped to HTTP by
 * {@code GlobalExceptionHandler}):</p>
 * <ul>
 *   <li>400 BAD_REQUEST — empty update</li>
 *   <li>404 NOT_FOUND — product id does not exist</li>
 *   <li>409 CONFLICT — duplicate SKU/barcode, redundant status change, or delete of a
 *       product still referenced by stock, invoices, or restock items</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;

    /**
     * Creates a product after verifying SKU (and barcode, if given) are not taken.
     *
     * @throws BusinessException 409 if the SKU or barcode already exists
     */
    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        requireSkuAvailable(request.sku());
        if (request.barcode() != null) {
            requireBarcodeAvailable(request.barcode());
        }
        int minStockLevel = request.minStockLevel() != null ? request.minStockLevel() : 0;

        Product saved = productRepository.save(new Product(
                request.name(),
                request.sku(),
                request.barcode(),
                request.price(),
                request.unitOfMeasure(),
                minStockLevel));

        log.info("Created product id={} sku='{}'", saved.getId(), saved.getSku());
        return ProductResponse.from(saved);
    }

    /**
     * @throws BusinessException 404 if no product has this id
     */
    public ProductResponse getById(Long id) {
        return ProductResponse.from(findOrThrow(id));
    }

    /**
     * Page of products, optionally filtered by status and/or a name/SKU search term.
     */
    public PageResponse<ProductResponse> list(ProductStatus status, String q, Pageable pageable) {
        String normalized = (q == null || q.isBlank()) ? null : q.trim();
        return PageResponse.of(
                productRepository.search(status, normalized, pageable).map(ProductResponse::from));
    }

    /**
     * Partially updates a product. Only non-null fields are applied. SKU/barcode
     * uniqueness is re-checked only when the value actually changes.
     *
     * @throws BusinessException 400 if the update is empty; 404 if the id does not
     *                           exist; 409 if a new SKU/barcode is already taken
     */
    @Transactional
    public ProductResponse update(Long id, UpdateProductRequest request) {
        if (request.isEmpty()) {
            throw BusinessException.badRequest(
                    "At least one field must be provided", "EMPTY_UPDATE");
        }

        Product product = findOrThrow(id);

        if (request.sku() != null && !request.sku().equals(product.getSku())) {
            requireSkuAvailable(request.sku());
            product.setSku(request.sku());
        }
        if (request.barcode() != null && !request.barcode().equals(product.getBarcode())) {
            requireBarcodeAvailable(request.barcode());
            product.setBarcode(request.barcode());
        }
        if (request.name() != null)          product.setName(request.name());
        if (request.price() != null)         product.setPrice(request.price());
        if (request.unitOfMeasure() != null) product.setUnitOfMeasure(request.unitOfMeasure());
        if (request.minStockLevel() != null) product.setMinStockLevel(request.minStockLevel());

        log.info("Updated product id={}", id);
        return ProductResponse.from(product);
    }

    /**
     * Changes a product's lifecycle status.
     *
     * @throws BusinessException 404 if the id does not exist;
     *                           409 if the product is already in the target status
     */
    @Transactional
    public ProductResponse changeStatus(Long id, ProductStatus targetStatus) {
        Product product = findOrThrow(id);
        if (product.getStatus() == targetStatus) {
            throw BusinessException.conflict(
                    "Product is already " + targetStatus.name().toLowerCase(),
                    "PRODUCT_STATUS_UNCHANGED");
        }
        product.setStatus(targetStatus);
        log.info("Changed product id={} status to {}", id, targetStatus);
        return ProductResponse.from(product);
    }

    /**
     * Hard-deletes a product.
     *
     * <p>A product referenced by warehouse stock, van inventory, invoice line items, or
     * restock items cannot be deleted — those foreign keys reject it at the database
     * level. We let the DB enforce integrity and translate the violation into a clean
     * 409, exactly as {@code CustomerService} does. In normal operation you discontinue
     * a product with history rather than delete it; delete is for products created in
     * error.</p>
     *
     * @throws BusinessException 404 if the id does not exist; 409 if still referenced
     */
    @Transactional
    public void delete(Long id) {
        Product product = findOrThrow(id);
        try {
            productRepository.delete(product);
            productRepository.flush(); // force the FK check now, inside this try
        } catch (DataIntegrityViolationException ex) {
            log.warn("Refused to delete product id={} — still referenced by stock, invoices, or restock items", id);
            throw BusinessException.conflict(
                    "Cannot delete a product that is referenced by stock, invoices, or restock requests. Discontinue it instead.",
                    "PRODUCT_IN_USE");
        }
        log.info("Deleted product id={}", id);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Loads a product or throws a 404 — single source of the not-found message. */
    private Product findOrThrow(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound(
                        "Product not found: " + id, "PRODUCT_NOT_FOUND"));
    }

    private void requireSkuAvailable(String sku) {
        if (productRepository.existsBySku(sku)) {
            throw BusinessException.conflict(
                    "A product with SKU '" + sku + "' already exists", "SKU_EXISTS");
        }
    }

    private void requireBarcodeAvailable(String barcode) {
        if (productRepository.existsByBarcode(barcode)) {
            throw BusinessException.conflict(
                    "A product with barcode '" + barcode + "' already exists", "BARCODE_EXISTS");
        }
    }
}
