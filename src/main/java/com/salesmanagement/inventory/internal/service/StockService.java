package com.salesmanagement.inventory.internal.service;

import com.salesmanagement.identity.api.UserFacade;
import com.salesmanagement.inventory.api.LowStockDetectedEvent;
import com.salesmanagement.inventory.api.VanInventoryItemInfo;
import com.salesmanagement.inventory.internal.entity.Product;
import com.salesmanagement.inventory.internal.entity.VanInventoryItem;
import com.salesmanagement.inventory.internal.entity.WarehouseStockItem;
import com.salesmanagement.inventory.internal.dto.VanInventoryResponse;
import com.salesmanagement.inventory.internal.dto.WarehouseStockResponse;
import com.salesmanagement.inventory.internal.repository.ProductRepository;
import com.salesmanagement.inventory.internal.repository.VanInventoryItemRepository;
import com.salesmanagement.inventory.internal.repository.WarehouseStockItemRepository;
import com.salesmanagement.shared.api.PageResponse;
import com.salesmanagement.shared.exception.BusinessException;
import com.salesmanagement.shared.security.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.salesmanagement.inventory.api.WarehouseStockInfo;
import com.salesmanagement.inventory.api.VanStockDiscrepancyEvent;

import java.time.Instant;
import java.util.List;

/**
 * Business logic for stock movement — warehouse stock, van inventory, and the
 * transfers between them. The only writer of {@link WarehouseStockItem} and
 * {@link VanInventoryItem}.
 *
 * <p><strong>Why the stock-movement methods live here and are exposed on the facade:</strong>
 * other modules ({@code invoicing}, {@code vanops}) must change stock, but they may not
 * touch this module's tables directly. So the logic that owns the BR-4 invariant lives in
 * this module and is reached through {@code InventoryFacade}:</p>
 * <ul>
 *   <li>{@link #deductVanStock} — called by {@code invoicing} when a rep sells off the van (BR-4);</li>
 *   <li>{@link #transferWarehouseToVan} — called by {@code vanops} when a demand order is loaded;</li>
 *   <li>{@link #returnVanToWarehouse} — called by {@code vanops} on end-of-day return.</li>
 * </ul>
 *
 * <p><strong>Concurrency (BR-4):</strong> every stock change is an atomic guarded SQL
 * statement (see the repositories). The check ("is there enough?") and the write
 * ("subtract") happen in one indivisible operation, so two concurrent deductions cannot
 * both pass. A guarded statement that changes 0 rows is treated as a refusal. The DB
 * {@code CHECK (quantity >= 0)} constraints are the final backstop.</p>
 *
 * <p><strong>Cross-module dependency:</strong> {@link UserFacade} is the only thing this
 * module imports from {@code identity}, used to enforce the ERD rule that a van's owner
 * must be a {@code SALES_REP} (a DB FK cannot express the role condition).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockService {

    private final WarehouseStockItemRepository warehouseRepo;
    private final VanInventoryItemRepository vanRepo;
    private final ProductRepository productRepository;
    private final UserFacade userFacade;
    /** Publishes LowStockDetectedEvent so the notification module can alert stock managers (FR-106). */
    private final ApplicationEventPublisher events;

    // ── Warehouse: reads ──────────────────────────────────────────────────────

    /**
     * @throws BusinessException 404 if no warehouse stock row exists for the product
     */
    public WarehouseStockResponse getWarehouseStock(Long productId) {
        return WarehouseStockResponse.from(findWarehouseRowOrThrow(productId));
    }

    /**
     * Page of warehouse stock, optionally filtered to one product and/or to low-stock
     * rows ({@code quantity < minStockLevel}, FR-32).
     */
    public PageResponse<WarehouseStockResponse> listWarehouseStock(Long productId,
                                                                   boolean lowStock,
                                                                   Pageable pageable) {
        return PageResponse.of(
                warehouseRepo.search(productId, lowStock, pageable).map(WarehouseStockResponse::from));
    }

    /**
     * On-hand warehouse quantity of a product, or 0 if no row exists yet. Used by
     * {@code InventoryFacade.getWarehouseQuantity} — vanops needs a numeric read to
     * auto-adjust demand-order lines on submit.
     */
    public int warehouseQuantityOrZero(Long productId) {
        return warehouseRepo.findByProductId(productId)
                .map(WarehouseStockItem::getQuantity)
                .orElse(0);
    }

    // ── Warehouse: writes (ADMIN / WAREHOUSE_MANAGER) ─────────────────────────

    /**
     * Sets the absolute on-hand quantity for a product (initial count or stock-take
     * correction). Creates the warehouse row if it does not exist yet.
     *
     * @throws BusinessException 404 if the product does not exist
     */
    @Transactional
    public WarehouseStockResponse setWarehouseStock(Long productId, int quantity) {
        requireProductExists(productId);
        WarehouseStockItem item = warehouseRepo.findByProductId(productId).orElse(null);
        if (item == null) {
            item = warehouseRepo.save(
                    new WarehouseStockItem(productRepository.getReferenceById(productId), quantity));
            log.info("Created warehouse stock for productId={} at quantity={}", productId, quantity);
        } else {
            item.setQuantity(quantity);
            log.info("Set warehouse stock for productId={} to quantity={}", productId, quantity);
        }
        return WarehouseStockResponse.from(item);
    }

    /**
     * Receives an incoming shipment — adds {@code quantity} to the product's warehouse
     * row atomically, creating the row if it does not exist yet.
     *
     * @throws BusinessException 400 if quantity is not positive; 404 if product is unknown
     */
    @Transactional
    public WarehouseStockResponse receiveWarehouseStock(Long productId, int quantity) {
        requirePositive(quantity);
        requireProductExists(productId);
        int updated = warehouseRepo.increment(productId, quantity);
        if (updated == 0) {
            // No row yet — first receipt for this product.
            warehouseRepo.save(new WarehouseStockItem(productRepository.getReferenceById(productId), quantity));
            log.info("Created warehouse stock for productId={} on receipt of quantity={}", productId, quantity);
        } else {
            log.info("Received quantity={} into warehouse stock for productId={}", quantity, productId);
        }
        return getWarehouseStock(productId);
    }

    // ── Van: reads ────────────────────────────────────────────────────────────

    /**
     * The products currently loaded on a representative's van (empty if none).
     */
    public List<VanInventoryResponse> getVanInventory(Long representativeId) {
        // Resolve the rep's name once — every row shares the same rep, so we don't refetch per line.
        String representativeName = safeUserName(representativeId);
        return vanRepo.findByRepresentative(representativeId).stream()
                .map(v -> VanInventoryResponse.from(v, representativeName))
                .toList();
    }

    /**
     * Van inventory as cross-module {@link VanInventoryItemInfo} projections — used by
     * {@code InventoryFacade.getVanInventoryInfo}. Distinct from {@link #getVanInventory}
     * which returns the internal response DTO for the REST controller.
     */
    public List<VanInventoryItemInfo> getVanInventoryAsInfo(Long representativeId) {
        return vanRepo.findByRepresentative(representativeId).stream()
                .map(v -> new VanInventoryItemInfo(v.getProduct().getId(), v.getQuantity()))
                .toList();
    }

    // ── Cross-module stock movements (reached via InventoryFacade) ────────────

    /**
     * BR-4: atomically deduct sold quantity from a rep's van. Used by {@code invoicing}.
     *
     * <p>The guarded UPDATE subtracts only if enough is present; if it changes 0 rows the
     * deduction is refused. We then read the row (failure path only — not hot) to return a
     * precise 422: either the product was never loaded, or there was not enough.</p>
     *
     * @throws BusinessException 400 if quantity is not positive;
     *                           422 {@code VAN_PRODUCT_NOT_LOADED} if no such van row;
     *                           422 {@code INSUFFICIENT_STOCK} if quantity is too low
     */
    @Transactional
    public void deductVanStock(Long representativeId, Long productId, int quantity) {
        requirePositive(quantity);

        int updated = vanRepo.deductIfSufficient(representativeId, productId, quantity);
        if (updated == 0) {
            VanInventoryItem existing =
                    vanRepo.findByRepresentativeIdAndProductId(representativeId, productId).orElse(null);
            if (existing == null) {
                throw BusinessException.unprocessable(
                        "Representative " + representativeId + " has no stock of product " + productId
                                + " loaded on the van",
                        "VAN_PRODUCT_NOT_LOADED");
            }
            throw BusinessException.unprocessable(
                    "Insufficient van stock for product " + productId + ": requested " + quantity
                            + ", available " + existing.getQuantity(),
                    "INSUFFICIENT_STOCK");
        }
        log.info("Deducted quantity={} from van of representativeId={} for productId={}",
                quantity, representativeId, productId);
    }

    /**
     * Authoritative offline van deduction (Fork B/E). Unlike {@link #deductVanStock}, this never
     * rejects on shortfall — the goods already left the van in the field, and the mobile app is
     * the primary BR-4 guard (FR-89). It deducts down to what is present (keeping the
     * non-negative CHECK intact) and publishes {@link VanStockDiscrepancyEvent} for any
     * un-deductible remainder, returning that shortfall.
     *
     * <p>ASSUMPTION (same as V11 tracking): one active device per rep, and no concurrent server
     * deduction of a rep's van mid-day. Under that assumption a shortfall is exceptional. The
     * single retry below covers the rare lost-guard race without looping.</p>
     *
     * @return the quantity that could NOT be deducted (0 in the normal case)
     * @throws BusinessException 400 if quantity is not positive
     */
    @Transactional
    public int applyOfflineVanDeduction(Long representativeId, Long productId, int quantity) {
        requirePositive(quantity);

        int deductedTotal = deductUpTo(representativeId, productId, quantity);
        if (deductedTotal < quantity) {
            // One retry to absorb a lost atomic-guard race; then accept the shortfall.
            deductedTotal += deductUpTo(representativeId, productId, quantity - deductedTotal);
        }

        int shortfall = quantity - deductedTotal;
        if (shortfall > 0) {
            events.publishEvent(new VanStockDiscrepancyEvent(
                    representativeId, productId, quantity, deductedTotal, shortfall, Instant.now()));
            log.warn("Offline van deduction shortfall rep={} product={} requested={} deducted={} shortfall={}",
                    representativeId, productId, quantity, deductedTotal, shortfall);
        } else {
            log.info("Offline van deduction rep={} product={} quantity={}",
                    representativeId, productId, quantity);
        }
        return shortfall;
    }

    /** Deducts min(want, available) atomically; returns how many were actually taken. */
    private int deductUpTo(Long representativeId, Long productId, int want) {
        int available = vanRepo.findByRepresentativeIdAndProductId(representativeId, productId)
                .map(VanInventoryItem::getQuantity)
                .orElse(0);
        int toDeduct = Math.min(want, available);
        if (toDeduct > 0 && vanRepo.deductIfSufficient(representativeId, productId, toDeduct) == 1) {
            return toDeduct;
        }
        return 0;
    }

    /**
     * Morning van load: atomically move {@code quantity} of a product from the warehouse to
     * a rep's van. Called by {@code vanops} when a demand order is loaded. The warehouse
     * cannot go below zero; the rep must be a {@code SALES_REP} (ERD rule).
     *
     * <p>Loading a multi-line demand order means the caller invokes this once per line inside
     * its own transaction, so the whole load is all-or-nothing.</p>
     *
     * @throws BusinessException 400 if quantity is not positive;
     *                           404 if the rep or product does not exist;
     *                           422 {@code NOT_A_SALES_REP} if the user is not a sales rep;
     *                           422 {@code INSUFFICIENT_WAREHOUSE_STOCK} if the warehouse lacks stock
     */
    @Transactional
    public void transferWarehouseToVan(Long representativeId, Long productId, int quantity) {
        requirePositive(quantity);
        requireSalesRep(representativeId);
        requireProductExists(productId);

        // Capture the warehouse quantity BEFORE the deduction, so we can detect a
        // threshold crossing after it (FR-106). One cheap read; the row exists in
        // practice because the product had to be stocked to be loadable.
        int before = warehouseQuantityOrZero(productId);

        // 1. Take from the warehouse — atomic, cannot go below zero.
        int deducted = warehouseRepo.deductIfSufficient(productId, quantity);
        if (deducted == 0) {
            boolean hasRow = warehouseRepo.existsByProductId(productId);
            throw BusinessException.unprocessable(
                    hasRow
                            ? "Insufficient warehouse stock for product " + productId + " to transfer " + quantity
                            : "No warehouse stock record for product " + productId,
                    "INSUFFICIENT_WAREHOUSE_STOCK");
        }

        // 2. Add to the rep's van — atomic increment if the row exists, else insert.
        int incremented = vanRepo.increment(representativeId, productId, quantity);
        if (incremented == 0) {
            vanRepo.save(new VanInventoryItem(
                    representativeId, productRepository.getReferenceById(productId), quantity));
        }

        log.info("Transferred quantity={} of productId={} from warehouse to van of representativeId={}",
                quantity, productId, representativeId);

        // 3. FR-106: if this deduction took the warehouse across its minimum, alert.
        //    Published AFTER the atomic movement, on the crossing only (was >= min,
        //    now < min), so a product that was already low does not re-alert on every
        //    load. The consumer is an @ApplicationModuleListener (post-commit, own
        //    transaction), so a failed notification can never roll back the transfer.
        publishIfCrossedMinimum(productId, before, before - quantity);
    }

    /**
     * End-of-day return: atomically move {@code quantity} of a product from a rep's van back
     * to the warehouse. Called by {@code vanops} when a return sheet is completed. The mirror
     * of {@link #transferWarehouseToVan}.
     *
     * <p><strong>Van-row cleanup:</strong> if the return empties the van line (quantity hits
     * zero), the row is <em>deleted</em>, not left at zero. The rationale is the
     * {@code UNIQUE(representative_id, product_id)} constraint: a leftover zero row would
     * block tomorrow's load from re-inserting the same (rep, product). Historical record of
     * what came back lives on the return sheet document in {@code vanops}, not on the van
     * ledger itself — the ledger is "now", documents are "history".</p>
     *
     * <p>Completing a multi-line return means the caller invokes this once per line inside
     * its own transaction, so the whole return is all-or-nothing.</p>
     *
     * @throws BusinessException 400 if quantity is not positive;
     *                           422 {@code VAN_PRODUCT_NOT_LOADED} if no such van row;
     *                           422 {@code INSUFFICIENT_VAN_STOCK} if the van holds less than requested
     */
    @Transactional
    public void returnVanToWarehouse(Long representativeId, Long productId, int quantity) {
        requirePositive(quantity);

        // 1. Take from the van — atomic, cannot go below zero. Same guard as a sale.
        int deducted = vanRepo.deductIfSufficient(representativeId, productId, quantity);
        if (deducted == 0) {
            VanInventoryItem existing =
                    vanRepo.findByRepresentativeIdAndProductId(representativeId, productId).orElse(null);
            if (existing == null) {
                throw BusinessException.unprocessable(
                        "Representative " + representativeId + " has no stock of product " + productId
                                + " loaded on the van",
                        "VAN_PRODUCT_NOT_LOADED");
            }
            throw BusinessException.unprocessable(
                    "Insufficient van stock for product " + productId + " to return: requested " + quantity
                            + ", available " + existing.getQuantity(),
                    "INSUFFICIENT_VAN_STOCK");
        }

        // 2. Add back to the warehouse — atomic increment if the row exists, else insert.
        //    (Insert path is defensive: in practice the warehouse row will exist, since the
        //    product had to be in the warehouse this morning to load the van in the first place.)
        int incremented = warehouseRepo.increment(productId, quantity);
        if (incremented == 0) {
            warehouseRepo.save(new WarehouseStockItem(
                    productRepository.getReferenceById(productId), quantity));
        }

        // 3. If the van row is now empty, delete it so tomorrow's load can re-insert (the
        //    UNIQUE(rep, product) constraint would otherwise reject the new row).
        vanRepo.findByRepresentativeIdAndProductId(representativeId, productId)
                .filter(v -> v.getQuantity() == 0)
                .ifPresent(vanRepo::delete);

        log.info("Returned quantity={} of productId={} from van of representativeId={} to warehouse",
                quantity, productId, representativeId);
    }

    @Transactional(readOnly = true)
    public List<WarehouseStockInfo> findAllWarehouseStock() {
        return warehouseRepo.findAllWarehouseStock();
    }

    @Transactional(readOnly = true)
    public List<WarehouseStockInfo> findWarehouseStockBelowMinimum() {
        return warehouseRepo.findWarehouseStockBelowMinimum();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private WarehouseStockItem findWarehouseRowOrThrow(Long productId) {
        return warehouseRepo.findByProductId(productId)
                .orElseThrow(() -> BusinessException.notFound(
                        "No warehouse stock record for product " + productId, "WAREHOUSE_STOCK_NOT_FOUND"));
    }

    private void requireProductExists(Long productId) {
        if (!productRepository.existsById(productId)) {
            throw BusinessException.notFound("Product not found: " + productId, "PRODUCT_NOT_FOUND");
        }
    }

    /**
     * FR-106: publishes {@link LowStockDetectedEvent} iff this movement is the one that took
     * the warehouse quantity from at/above the product's minimum to below it. Fires once, on
     * the crossing — not while already low. A non-positive minimum (unset) never alerts,
     * because {@code before >= min && after < min} then requires {@code after < 0}, which the
     * BR-4 guard already prevents.
     */
    private void publishIfCrossedMinimum(Long productId, int before, int after) {
        Product product = productRepository.findById(productId).orElse(null);
        if (product == null) {
            return; // product vanished mid-transaction; nothing sensible to alert on
        }
        int min = product.getMinStockLevel();
        if (before >= min && after < min) {
            events.publishEvent(new LowStockDetectedEvent(
                    product.getId(),
                    product.getName(),
                    after,
                    min,
                    Instant.now()));
            log.info("Low stock crossed for productId={} name='{}': {} -> {} (min {})",
                    product.getId(), product.getName(), before, after, min);
        }
    }

    /** Returns the user's name, or {@code null} if the lookup fails (deleted user, etc.). */
    private String safeUserName(Long userId) {
        try {
            return userFacade.getNameById(userId);
        } catch (Exception ex) {
            return null;
        }
    }

    /** Enforces the ERD rule that only a {@code SALES_REP} may own van inventory. */
    private void requireSalesRep(Long representativeId) {
        UserRole role = userFacade.getRoleById(representativeId); // 404 if the user does not exist
        if (role != UserRole.SALES_REP) {
            throw BusinessException.unprocessable(
                    "User " + representativeId + " is not a SALES_REP and cannot own van inventory",
                    "NOT_A_SALES_REP");
        }
    }

    private void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw BusinessException.badRequest("Quantity must be greater than zero", "INVALID_QUANTITY");
        }
    }
}
