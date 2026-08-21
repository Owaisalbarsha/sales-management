package com.salesmanagement.inventory.internal.service;

import com.salesmanagement.inventory.api.StockCountSummaryInfo;
import com.salesmanagement.inventory.api.StockVarianceInfo;
import com.salesmanagement.inventory.internal.dto.CreateStockCountRequest;
import com.salesmanagement.inventory.internal.dto.StockCountResponse;
import com.salesmanagement.inventory.internal.dto.UpdateStockCountLinesRequest;
import com.salesmanagement.inventory.internal.entity.Product;
import com.salesmanagement.inventory.internal.entity.StockCount;
import com.salesmanagement.inventory.internal.entity.StockCountLine;
import com.salesmanagement.inventory.internal.enums.StockCountStatus;
import com.salesmanagement.inventory.internal.repository.ProductRepository;
import com.salesmanagement.inventory.internal.repository.StockCountRepository;
import com.salesmanagement.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Business logic for physical stock counts — the write half of FR-124 (Stock Variance Report).
 *
 * <p>Deliberately separate from {@link StockService}: that service owns atomic stock
 * <em>movements</em> and the BR-4 invariant, whereas a stock count moves no stock at all. This
 * service does exactly one write to inventory's own tables (the count session) and, at finalize,
 * <em>reads</em> warehouse stock through {@link StockService#warehouseQuantityOrZero(Long)} to
 * snapshot the recorded figure. It never writes {@code warehouse_stock_items} — the feature is
 * audit-only. (Reconciliation, if ever built, would layer on top via
 * {@link StockService#setWarehouseStock(Long, int)}, which already exists.)</p>
 *
 * <p><strong>Lifecycle.</strong> A count is opened DRAFT, its lines edited freely (mutate in
 * place), then FINALIZED. Finalization snapshots every line's recorded quantity against warehouse
 * stock at that single instant — the honest "as-of" moment for the variance — and freezes the
 * record. Variance is only defined for a FINALIZED count.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockCountService {

    private final StockCountRepository stockCountRepository;
    private final ProductRepository productRepository;
    private final StockService stockService;

    // ── Writes (ADMIN / WAREHOUSE_MANAGER) ────────────────────────────────────

    /**
     * Opens a new DRAFT count. {@code countDate} defaults to today when omitted; {@code lines}
     * may be empty (open the draft, fill it in later).
     *
     * @throws BusinessException 400 if the same product appears twice; 404 if any product is unknown
     */
    @Transactional
    public StockCountResponse createDraft(Long countedById, CreateStockCountRequest request) {
        List<CreateStockCountRequest.Line> reqLines =
                (request.lines() == null) ? List.of() : request.lines();
        rejectDuplicateProducts(reqLines.stream().map(CreateStockCountRequest.Line::productId).toList());

        LocalDate countDate = (request.countDate() != null) ? request.countDate() : LocalDate.now();
        StockCount count = new StockCount(countedById, countDate);

        for (CreateStockCountRequest.Line reqLine : reqLines) {
            count.addLine(new StockCountLine(
                    referenceExistingProduct(reqLine.productId()), reqLine.countedQuantity()));
        }

        StockCount saved = stockCountRepository.save(count);
        log.info("Opened stock count id={} countedById={} countDate={} lines={}",
                saved.getId(), countedById, countDate, saved.getLines().size());
        return StockCountResponse.from(saved);
    }

    /**
     * Replaces the lines of a DRAFT count with the supplied set, mutating existing rows in place
     * and inserting/deleting only the true delta (never clear-and-rebuild — that would trip the
     * unique-constraint flush-ordering trap). No product is both removed and re-added, because
     * a product present in the request is updated in place, not replaced.
     *
     * @throws BusinessException 400 if the same product appears twice; 404 if the count or any
     *                           product is unknown; 409 if the count is not DRAFT
     */
    @Transactional
    public StockCountResponse updateLines(Long countId, UpdateStockCountLinesRequest request) {
        StockCount count = findWithLinesOrThrow(countId);
        count.requireDraft();
        rejectDuplicateProducts(request.lines().stream()
                .map(UpdateStockCountLinesRequest.Line::productId).toList());

        Map<Long, StockCountLine> existingByProduct = new HashMap<>();
        for (StockCountLine l : count.getLines()) {
            existingByProduct.put(l.getProduct().getId(), l);
        }
        Set<Long> incoming = new HashSet<>();
        for (UpdateStockCountLinesRequest.Line l : request.lines()) {
            incoming.add(l.productId());
        }

        // 1. Remove lines whose product is no longer present (orphanRemoval deletes them).
        count.getLines().removeIf(l -> !incoming.contains(l.getProduct().getId()));

        // 2. Upsert: update existing lines in place, insert genuinely new products.
        for (UpdateStockCountLinesRequest.Line reqLine : request.lines()) {
            StockCountLine existing = existingByProduct.get(reqLine.productId());
            if (existing != null) {
                existing.setCountedQuantity(reqLine.countedQuantity()); // mutate in place
            } else {
                count.addLine(new StockCountLine(
                        referenceExistingProduct(reqLine.productId()), reqLine.countedQuantity()));
            }
        }

        StockCount saved = stockCountRepository.save(count);
        log.info("Updated draft stock count id={} to {} line(s)", countId, saved.getLines().size());
        return StockCountResponse.from(saved);
    }

    /**
     * Finalizes a DRAFT count: snapshots every line's recorded warehouse quantity at this single
     * instant, stamps {@code finalizedAt}, and freezes the record. This is the variance as-of
     * moment. Moves no stock.
     *
     * @throws BusinessException 404 if no such count; 409 if the count is not DRAFT or has no lines
     */
    @Transactional
    public StockCountResponse finalizeCount(Long countId) {
        StockCount count = findWithLinesOrThrow(countId);
        count.requireDraft();
        if (count.getLines().isEmpty()) {
            throw BusinessException.conflict(
                    "Stock count " + countId + " has no lines to finalize", "STOCK_COUNT_EMPTY");
        }

        Instant asOf = Instant.now();
        for (StockCountLine line : count.getLines()) {
            // Recorded = warehouse on-hand for this product (0 if never stocked), read at finalize.
            line.setRecordedQuantity(stockService.warehouseQuantityOrZero(line.getProduct().getId()));
        }
        count.markFinalized(asOf);

        StockCount saved = stockCountRepository.save(count);
        log.info("Finalized stock count id={} asOf={} lines={}", countId, asOf, saved.getLines().size());
        return StockCountResponse.from(saved);
    }

    // ── Reads ─────────────────────────────────────────────────────────────────

    /** @throws BusinessException 404 if no such count */
    public StockCountResponse getById(Long countId) {
        return StockCountResponse.from(findWithLinesOrThrow(countId));
    }

    /** All counts, newest first (internal REST view). */
    public List<StockCountResponse> listAll() {
        return stockCountRepository.findAllWithLines().stream()
                .map(StockCountResponse::from)
                .toList();
    }

    // ── Cross-module reads (reached via InventoryFacade) ──────────────────────

    /**
     * Per-product variance for a finalized count — the data the {@code reporting} module renders
     * as the Stock Variance Report. Variance = counted - recorded (snapshot taken at finalize).
     *
     * @throws BusinessException 404 if no such count; 409 if the count is still DRAFT (variance
     *                           is undefined until a recorded snapshot exists)
     */
    public List<StockVarianceInfo> getVariance(Long countId) {
        StockCount count = findWithLinesOrThrow(countId);
        if (count.getStatus() != StockCountStatus.FINALIZED) {
            throw BusinessException.conflict(
                    "Stock count " + countId + " is not finalized; variance is undefined",
                    "STOCK_COUNT_NOT_FINALIZED");
        }
        return count.getLines().stream()
                .map(line -> {
                    Product p = line.getProduct();
                    int recorded = line.getRecordedQuantity(); // non-null once finalized
                    int counted = line.getCountedQuantity();
                    return new StockVarianceInfo(
                            p.getId(), p.getName(), p.getSku(), recorded, counted, counted - recorded);
                })
                .toList();
    }

    /** Header summaries for every count (newest first) — the reporting picker. */
    public List<StockCountSummaryInfo> listSummaries() {
        return stockCountRepository.findAllWithLines().stream()
                .map(c -> new StockCountSummaryInfo(
                        c.getId(),
                        c.getCountedById(),
                        c.getCountDate(),
                        c.getStatus().name(),
                        c.getLines().size(),
                        c.getFinalizedAt()))
                .toList();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private StockCount findWithLinesOrThrow(Long id) {
        return stockCountRepository.findWithLinesById(id)
                .orElseThrow(() -> BusinessException.notFound(
                        "Stock count not found: " + id, "STOCK_COUNT_NOT_FOUND"));
    }

    /**
     * Returns a managed reference to an existing product, or 404 if it does not exist. Uses a
     * proxy ({@code getReferenceById}) to avoid a full load — the same pattern StockService uses
     * when creating warehouse/van rows. A product may be DISCONTINUED and still be counted
     * (discontinued stock can physically sit on a shelf), so status is intentionally not checked.
     */
    private Product referenceExistingProduct(Long productId) {
        if (!productRepository.existsById(productId)) {
            throw BusinessException.notFound("Product not found: " + productId, "PRODUCT_NOT_FOUND");
        }
        return productRepository.getReferenceById(productId);
    }

    private void rejectDuplicateProducts(List<Long> productIds) {
        Set<Long> seen = new HashSet<>();
        for (Long id : productIds) {
            if (!seen.add(id)) {
                throw BusinessException.badRequest(
                        "Duplicate product on stock count: " + id, "DUPLICATE_PRODUCT_ON_COUNT");
            }
        }
    }
}
