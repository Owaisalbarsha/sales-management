package com.salesmanagement.inventory.api;

import com.salesmanagement.inventory.internal.entity.Product;
import com.salesmanagement.inventory.internal.enums.ProductStatus;
import com.salesmanagement.inventory.internal.repository.ProductRepository;
import com.salesmanagement.inventory.internal.service.StockCountService;
import com.salesmanagement.inventory.internal.service.StockService;
import com.salesmanagement.inventory.internal.repository.WarehouseStockItemRepository;
import com.salesmanagement.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

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
    private final StockCountService stockCountService;
    private final WarehouseStockItemRepository warehouseStockItemRepository;
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

    /**
     * Snapshot of everything currently loaded on a representative's van — used by
     * {@code vanops} to auto-generate end-of-day return sheets without typing line items.
     * Empty rows are not surfaced (the entity invariant: van rows are deleted when they hit
     * zero, so any returned line has {@code quantity > 0}).
     *
     * @return list of (productId, quantity); empty if the van is empty / not yet loaded
     */
    @Transactional(readOnly = true)
    public List<VanInventoryItemInfo> getVanInventoryInfo(Long representativeId) {
        return stockService.getVanInventoryAsInfo(representativeId);
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
     * Best-effort offline van deduction for a completed offline sale (Fork B/E). Records what it
     * can, never rejects on shortfall, and returns the un-deducted remainder. Called by
     * {@code invoicing} when replaying an offline invoice. See
     * {@link StockService#applyOfflineVanDeduction}.
     */
    @Transactional
    public int applyOfflineVanDeduction(Long representativeId, Long productId, int quantity) {
        return stockService.applyOfflineVanDeduction(representativeId, productId, quantity);
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

    /**
     * Every product's current warehouse stock against its minimum — backs FR-120 (warehouse stock
     * levels) and is the superset FR-121 filters. One query, one row per product; the
     * {@code belowMin} verdict is precomputed so reporting needs neither the threshold nor a second
     * pass. A product with no warehouse row yet appears at {@code onHand = 0} (LEFT JOIN), so
     * never-stocked products are not hidden from the stock report.
     *
     * @return warehouse stock lines for all products, ordered by name
     */
    @Transactional(readOnly = true)
    public List<WarehouseStockInfo> findAllWarehouseStock() {
        return stockService.findAllWarehouseStock();
    }

    /**
     * Only the products currently below their minimum — the direct feed for FR-121 (low-stock /
     * reorder report). Kept as its own query so the common "just the shortages" call doesn't transfer
     * every product.
     *
     * @return warehouse stock lines where {@code onHand < minStockLevel}, ordered by name
     */
    @Transactional(readOnly = true)
    public List<WarehouseStockInfo> findWarehouseStockBelowMinimum() {
        return stockService.findWarehouseStockBelowMinimum();
    }

    /**
     * Resolves many product ids to their names in ONE query — the batch reporting uses to label the
     * fast/slow-moving report (FR-123) and any product-grouped output without an N+1 loop. Ids with no
     * matching product are absent from the returned map.
     *
     * @param productIds the ids to resolve
     * @return id → name for every id that exists; empty map if {@code productIds} is empty
     */
    @Transactional(readOnly = true)
    public Map<Long, String> getNamesByIds(Collection<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Map.of();
        }
        return productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Product::getName));
    }

    /**
     * Per-product variance for a finalized stock count — the data the {@code reporting} module
     * renders as the Stock Variance Report (FR-124). {@code variance = counted - recorded}, where
     * {@code recorded} is the warehouse figure snapshotted at the instant the count was finalized.
     * Read-only: this never corrects stock.
     *
     * @throws BusinessException 404 if no such count; 409 if the count is still DRAFT
     *                           (variance is undefined until finalized)
     */
    @Transactional(readOnly = true)
    public List<StockVarianceInfo> getStockVariance(Long stockCountId) {
        return stockCountService.getVariance(stockCountId);
    }

    /**
     * Header summaries of every stock count (newest first) — lets {@code reporting} present a
     * picker and filter to FINALIZED counts before requesting variance.
     */
    @Transactional(readOnly = true)
    public List<StockCountSummaryInfo> getStockCounts() {
        return stockCountService.listSummaries();
    }

    /**
     * Total warehouse stock value: sum over all warehouse rows of (quantity * product price). Computed
     * in one query in the database. Used by the inventory dashboard's stock-value tile. Zero if the
     * warehouse is empty.
     */
    @Transactional(readOnly = true)
    public BigDecimal getTotalStockValue() {
        BigDecimal v = warehouseStockItemRepository.totalStockValue();
        return v == null ? BigDecimal.ZERO : v;
    }


}