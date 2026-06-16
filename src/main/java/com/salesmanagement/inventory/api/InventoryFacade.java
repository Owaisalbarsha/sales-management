package com.salesmanagement.inventory.api;

import com.salesmanagement.inventory.internal.Product;
import com.salesmanagement.inventory.internal.ProductStatus;
import com.salesmanagement.inventory.internal.repository.ProductRepository;
import com.salesmanagement.inventory.internal.service.StockService;
import com.salesmanagement.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Public API surface of the inventory module — the only type other modules may import
 * from inventory. Mirrors {@code CustomerFacade}/{@code TerritoryFacade}/{@code UserFacade}.
 *
 * <p>Unlike the customer facade (read-only), this facade also exposes <em>write</em>
 * operations. That is deliberate: {@code invoicing} and {@code vanops} must change stock,
 * but they may not touch this module's tables — so the stock-movement logic that owns the
 * BR-4 invariant lives here and is reached through the facade. Product reads go straight to
 * the repository (no business logic); the stock writes delegate to {@code StockService},
 * which owns the transactions and the atomic guards (business logic never lives in the
 * facade itself, matching {@code UserFacade}).</p>
 */
@Service
@RequiredArgsConstructor
public class InventoryFacade {

    private final ProductRepository productRepository;
    private final StockService stockService;

    // ── Product reads (used by invoicing, vanops) ────────────────────────────

    /**
     * Public projection of a product.
     *
     * @throws BusinessException 404 if no product has this id
     */
    @Transactional(readOnly = true)
    public ProductInfo getProductInfo(Long productId) {
        Product p = findOrThrow(productId);
        return new ProductInfo(
                p.getId(),
                p.getName(),
                p.getSku(),
                p.getPrice(),
                p.getUnitOfMeasure(),
                p.getStatus() == ProductStatus.ACTIVE);
    }

    /**
     * Whether a product exists. Lets {@code invoicing}/{@code vanops} reject a bad
     * {@code productId} with their own domain error rather than a foreign-key violation.
     */
    @Transactional(readOnly = true)
    public boolean productExists(Long productId) {
        return productRepository.existsById(productId);
    }

    /**
     * Whether the product is currently ACTIVE (sellable).
     *
     * @throws BusinessException 404 if no product has this id
     */
    @Transactional(readOnly = true)
    public boolean isProductActive(Long productId) {
        return findOrThrow(productId).getStatus() == ProductStatus.ACTIVE;
    }

    /**
     * Current unit price — {@code invoicing} captures this on the line item (BR-9).
     *
     * @throws BusinessException 404 if no product has this id
     */
    @Transactional(readOnly = true)
    public BigDecimal getProductPrice(Long productId) {
        return findOrThrow(productId).getPrice();
    }

    /**
     * Current on-hand warehouse quantity of a product, or {@code 0} if no warehouse row
     * exists yet (a product the warehouse has never received). Used by {@code vanops} on
     * demand-order submit to auto-adjust line quantities down to availability without
     * needing to reach into inventory's tables.
     *
     * @return on-hand quantity (≥ 0); {@code 0} if no warehouse stock row yet
     * @throws BusinessException 404 if the product itself does not exist
     */
    @Transactional(readOnly = true)
    public int getWarehouseQuantity(Long productId) {
        // Guard: product existence — a missing product is a 404, not silently zero.
        findOrThrow(productId);
        return stockService.warehouseQuantityOrZero(productId);
    }

    // ── Stock movements (delegated to StockService) ───────────────────────────

    /**
     * BR-4: atomically deduct sold quantity from a rep's van. Called by {@code invoicing}.
     * Throws 422 if the van does not hold enough (see {@link StockService#deductVanStock}).
     */
    @Transactional
    public void deductVanStock(Long representativeId, Long productId, int quantity) {
        stockService.deductVanStock(representativeId, productId, quantity);
    }

    /**
     * Morning van load: atomically move stock from the warehouse to a rep's van. Called by
     * {@code vanops} when a demand order is loaded. Enforces the warehouse floor and the
     * SALES_REP rule (see {@link StockService#transferWarehouseToVan}).
     */
    @Transactional
    public void transferWarehouseToVan(Long representativeId, Long productId, int quantity) {
        stockService.transferWarehouseToVan(representativeId, productId, quantity);
    }

    /**
     * End-of-day return: atomically move stock from a rep's van back to the warehouse.
     * Called by {@code vanops} when a return sheet is completed. Deletes the van row when
     * it reaches zero so tomorrow's load can re-insert it (see
     * {@link StockService#returnVanToWarehouse}).
     */
    @Transactional
    public void returnVanToWarehouse(Long representativeId, Long productId, int quantity) {
        stockService.returnVanToWarehouse(representativeId, productId, quantity);
    }

    private Product findOrThrow(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> BusinessException.notFound(
                        "Product not found: " + productId, "PRODUCT_NOT_FOUND"));
    }
}