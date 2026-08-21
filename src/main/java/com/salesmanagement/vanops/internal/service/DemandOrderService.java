package com.salesmanagement.vanops.internal.service;

import com.salesmanagement.identity.api.UserFacade;
import com.salesmanagement.inventory.api.InventoryFacade;
import com.salesmanagement.inventory.api.ProductInfo;
import com.salesmanagement.shared.api.PageResponse;
import com.salesmanagement.shared.exception.BusinessException;
import com.salesmanagement.shared.security.UserRole;
import com.salesmanagement.vanops.internal.entity.DemandOrder;
import com.salesmanagement.vanops.internal.entity.DemandOrderLine;
import com.salesmanagement.vanops.internal.enums.DemandOrderStatus;
import com.salesmanagement.vanops.internal.dto.CreateDemandOrderRequest;
import com.salesmanagement.vanops.internal.dto.DemandOrderResponse;
import com.salesmanagement.vanops.internal.repository.DemandOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Business logic for demand orders — the morning fill workflow.
 *
 * <p><strong>Submit</strong> validates the request, checks warehouse stock per product via
 * {@code InventoryFacade}, trims line quantities down to availability when needed, and saves
 * the order with status {@code SUBMITTED} (no adjustments) or {@code ADJUSTED} (at least one
 * line trimmed). No stock moves at submit time — the order is paperwork until {@code load}.</p>
 *
 * <p><strong>Load</strong> moves stock warehouse → van by calling
 * {@code InventoryFacade.transferWarehouseToVan} once per line, then flips the status to
 * {@code LOADED}. Wrapped in a single transaction so a mid-loop failure rolls back the entire
 * load (inventory's atomic guards make each move safe; the transaction makes the multi-line
 * load all-or-nothing).</p>
 *
 * <p><strong>Cross-module dependencies:</strong> {@code UserFacade} (verify sales-manager and
 * sales-rep roles) and {@code InventoryFacade} (stock check + transfer). No reach into
 * inventory's or identity's tables — everything through facades.</p>
 *
 * <p><strong>Idempotency note (for invoicing later):</strong> a demand order is loaded
 * exactly once; once {@code LOADED} the load endpoint refuses to re-run. Stock movements
 * are never replayed.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DemandOrderService {

    private final DemandOrderRepository demandOrderRepository;
    private final InventoryFacade inventoryFacade;
    private final UserFacade userFacade;

    /**
     * Sales manager submits a demand order. The system validates everything and auto-adjusts
     * line quantities to whatever the warehouse actually has.
     *
     * @throws BusinessException 400 if duplicate products on the order; 404 if the rep,
     *                           sales manager, or any product is unknown;
     *                           422 if the rep is not a SALES_REP, the submitter is not a
     *                           SALES_MANAGER, or any product is not ACTIVE
     */
    @Transactional
    public DemandOrderResponse submit(Long salesManagerId, CreateDemandOrderRequest request) {
        requireSalesManager(salesManagerId);
        requireSalesRep(request.representativeId());
        rejectDuplicateProducts(request.lines());

        DemandOrder order = new DemandOrder(
                salesManagerId, request.representativeId(), LocalDate.now());

        boolean adjusted = false;
        for (CreateDemandOrderRequest.Line reqLine : request.lines()) {
            // Validate the product (existence + active) — clean domain error vs raw FK on save.
            requireActiveProduct(reqLine.productId());

            // Warehouse availability for this product.
            int available = warehouseAvailable(reqLine.productId());
            int fulfilled = Math.min(reqLine.requestedQty(), available);
            if (fulfilled < reqLine.requestedQty()) {
                adjusted = true;
            }

            DemandOrderLine line = new DemandOrderLine(reqLine.productId(), reqLine.requestedQty());
            line.setFulfilledQty(fulfilled);
            order.addLine(line);
        }

        order.setStatus(adjusted ? DemandOrderStatus.ADJUSTED : DemandOrderStatus.SUBMITTED);
        DemandOrder saved = demandOrderRepository.save(order);

        log.info("Submitted demand order id={} salesManagerId={} representativeId={} status={} lines={}",
                saved.getId(), salesManagerId, request.representativeId(),
                saved.getStatus(), saved.getLines().size());

        return toResponse(saved);
    }

    /**
     * Warehouse manager marks an order loaded: stock physically moves warehouse → van.
     *
     * <p>Lines with {@code fulfilledQty == 0} (fully short) are skipped — no stock to move.
     * All other lines call {@code InventoryFacade.transferWarehouseToVan}; the whole load is
     * one transaction.</p>
     *
     * @throws BusinessException 404 if no such order;
     *                           409 if the order is not in {@code SUBMITTED} or
     *                           {@code ADJUSTED} status (already loaded, or unknown state)
     */
    @Transactional
    public DemandOrderResponse load(Long orderId) {
        DemandOrder order = findWithLinesOrThrow(orderId);

        if (order.getStatus() == DemandOrderStatus.LOADED) {
            throw BusinessException.conflict(
                    "Demand order " + orderId + " is already loaded", "DEMAND_ORDER_ALREADY_LOADED");
        }
        if (order.getStatus() != DemandOrderStatus.SUBMITTED
                && order.getStatus() != DemandOrderStatus.ADJUSTED) {
            throw BusinessException.conflict(
                    "Demand order " + orderId + " cannot be loaded from status " + order.getStatus(),
                    "DEMAND_ORDER_NOT_LOADABLE");
        }

        for (DemandOrderLine line : order.getLines()) {
            if (line.getFulfilledQty() <= 0) continue; // fully short — nothing to move
            inventoryFacade.transferWarehouseToVan(
                    order.getRepresentativeId(), line.getProductId(), line.getFulfilledQty());
        }

        order.setStatus(DemandOrderStatus.LOADED);
        DemandOrder saved = demandOrderRepository.save(order);
        log.info("Loaded demand order id={} (representativeId={}, {} lines)",
                orderId, saved.getRepresentativeId(), saved.getLines().size());
        return toResponse(saved);
    }

    /**
     * @throws BusinessException 404 if no such order
     */
    public DemandOrderResponse getById(Long id) {
        return toResponse(findWithLinesOrThrow(id));
    }

    public PageResponse<DemandOrderResponse> list(Long representativeId,
                                                  DemandOrderStatus status,
                                                  LocalDate orderDate,
                                                  Pageable pageable) {
        // Single page query, then build a single product-info map across all returned lines.
        var page = demandOrderRepository.search(representativeId, status, orderDate, pageable);

        Map<Long, ProductInfo> productInfos = fetchProductInfos(
                page.getContent().stream()
                        .flatMap(o -> o.getLines().stream().map(DemandOrderLine::getProductId))
                        .collect(java.util.stream.Collectors.toSet()));

        // Resolve every user id (sales managers + reps) once across the page — avoids
        // re-fetching the same name multiple times when many orders share the same submitter.
        Set<Long> userIds = new HashSet<>();
        for (DemandOrder o : page.getContent()) {
            userIds.add(o.getSalesManagerId());
            userIds.add(o.getRepresentativeId());
        }
        Map<Long, String> userNames = fetchUserNames(userIds);

        return PageResponse.of(page.map(o -> DemandOrderResponse.from(
                o,
                productInfos,
                userNames.get(o.getSalesManagerId()),
                userNames.get(o.getRepresentativeId()))));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private DemandOrder findWithLinesOrThrow(Long id) {
        return demandOrderRepository.findWithLinesById(id)
                .orElseThrow(() -> BusinessException.notFound(
                        "Demand order not found: " + id, "DEMAND_ORDER_NOT_FOUND"));
    }

    private DemandOrderResponse toResponse(DemandOrder order) {
        Set<Long> productIds = new HashSet<>();
        for (DemandOrderLine l : order.getLines()) productIds.add(l.getProductId());
        String salesManagerName   = safeUserName(order.getSalesManagerId());
        String representativeName = safeUserName(order.getRepresentativeId());
        return DemandOrderResponse.from(order,
                fetchProductInfos(productIds),
                salesManagerName,
                representativeName);
    }

    /** Resolves product ids to {@link ProductInfo} via the inventory facade. */
    private Map<Long, ProductInfo> fetchProductInfos(Set<Long> productIds) {
        Map<Long, ProductInfo> out = new HashMap<>();
        for (Long id : productIds) {
            // getProductInfo throws 404 if missing; we've already validated existence at submit,
            // so a miss here would be a data-integrity bug — let it surface.
            out.put(id, inventoryFacade.getProductInfo(id));
        }
        return out;
    }

    /** Resolves user ids to display names via the identity facade. Failures yield null. */
    private Map<Long, String> fetchUserNames(Set<Long> userIds) {
        Map<Long, String> out = new HashMap<>();
        for (Long id : userIds) {
            out.put(id, safeUserName(id));
        }
        return out;
    }

    /** Returns the user's name, or {@code null} if the lookup fails (deleted user, etc.). */
    private String safeUserName(Long userId) {
        try {
            return userFacade.getNameById(userId);
        } catch (Exception ex) {
            return null;
        }
    }

    private int warehouseAvailable(Long productId) {
        return inventoryFacade.getWarehouseQuantity(productId);
    }

    private void requireSalesManager(Long userId) {
        UserRole role = userFacade.getRoleById(userId);
        if (role != UserRole.SALES_MANAGER && role != UserRole.ADMIN) {
            throw BusinessException.unprocessable(
                    "User " + userId + " is not a SALES_MANAGER", "NOT_A_SALES_MANAGER");
        }
    }

    private void requireSalesRep(Long userId) {
        UserRole role = userFacade.getRoleById(userId);
        if (role != UserRole.SALES_REP) {
            throw BusinessException.unprocessable(
                    "User " + userId + " is not a SALES_REP", "NOT_A_SALES_REP");
        }
    }

    private void requireActiveProduct(Long productId) {
        if (!inventoryFacade.productExists(productId)) {
            throw BusinessException.notFound(
                    "Product not found: " + productId, "PRODUCT_NOT_FOUND");
        }
        if (!inventoryFacade.isProductActive(productId)) {
            throw BusinessException.unprocessable(
                    "Product " + productId + " is not ACTIVE", "PRODUCT_NOT_ACTIVE");
        }
    }

    private void rejectDuplicateProducts(List<CreateDemandOrderRequest.Line> lines) {
        Set<Long> seen = new HashSet<>();
        for (var l : lines) {
            if (!seen.add(l.productId())) {
                throw BusinessException.badRequest(
                        "Duplicate product on demand order: " + l.productId(),
                        "DUPLICATE_PRODUCT_ON_ORDER");
            }
        }
    }
}