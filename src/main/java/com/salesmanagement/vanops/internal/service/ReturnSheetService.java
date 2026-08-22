package com.salesmanagement.vanops.internal.service;

import com.salesmanagement.identity.api.UserFacade;
import com.salesmanagement.inventory.api.InventoryFacade;
import com.salesmanagement.inventory.api.ProductInfo;
import com.salesmanagement.inventory.api.VanInventoryItemInfo;
import com.salesmanagement.shared.api.PageResponse;
import com.salesmanagement.shared.exception.BusinessException;
import com.salesmanagement.shared.security.UserRole;
import com.salesmanagement.vanops.internal.entity.ReturnSheet;
import com.salesmanagement.vanops.internal.entity.ReturnSheetLine;
import com.salesmanagement.vanops.internal.enums.ReturnSheetStatus;
import com.salesmanagement.vanops.internal.dto.CreateReturnSheetRequest;
import com.salesmanagement.vanops.internal.dto.ReturnSheetResponse;
import com.salesmanagement.vanops.internal.repository.ReturnSheetRepository;
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
 * Business logic for return sheets — the end-of-day reconciliation workflow.
 *
 * <p><strong>Create</strong> validates and saves a {@code DRAFT} sheet. No stock moves.</p>
 *
 * <p><strong>Complete</strong> calls {@code InventoryFacade.returnVanToWarehouse} once per
 * line, then flips status to {@code COMPLETED}. Single transaction so a mid-loop failure
 * (e.g., a rep reporting more returned than the van actually holds) rolls back the whole
 * thing — partial returns do not leave the system in a half-state.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReturnSheetService {

    private final ReturnSheetRepository returnSheetRepository;
    private final InventoryFacade inventoryFacade;
    private final UserFacade userFacade;

    /**
     * Create a DRAFT return sheet.
     *
     * @throws BusinessException 400 if duplicate products on the sheet; 404 if the rep or
     *                           any product is unknown; 422 if the user is not a SALES_REP
     */
    @Transactional
    public ReturnSheetResponse create(CreateReturnSheetRequest request) {
        requireSalesRep(request.representativeId());
        rejectDuplicateProducts(request.lines());

        ReturnSheet sheet = new ReturnSheet(request.representativeId(), LocalDate.now());

        for (CreateReturnSheetRequest.Line reqLine : request.lines()) {
            if (!inventoryFacade.productExists(reqLine.productId())) {
                throw BusinessException.notFound(
                        "Product not found: " + reqLine.productId(), "PRODUCT_NOT_FOUND");
            }
            sheet.addLine(new ReturnSheetLine(reqLine.productId(), reqLine.quantity()));
        }

        ReturnSheet saved = returnSheetRepository.save(sheet);
        log.info("Created return sheet id={} representativeId={} lines={}",
                saved.getId(), request.representativeId(), saved.getLines().size());
        return toResponse(saved);
    }

    /**
     * Warehouse manager confirms the return: stock moves van → warehouse.
     *
     * @throws BusinessException 404 if no such sheet;
     *                           409 if the sheet is already COMPLETED;
     *                           422 (from inventory) if the van doesn't hold what the sheet claims
     */
    @Transactional
    public ReturnSheetResponse complete(Long sheetId) {
        ReturnSheet sheet = findWithLinesOrThrow(sheetId);

        if (sheet.getStatus() == ReturnSheetStatus.COMPLETED) {
            throw BusinessException.conflict(
                    "Return sheet " + sheetId + " is already completed",
                    "RETURN_SHEET_ALREADY_COMPLETED");
        }

        for (ReturnSheetLine line : sheet.getLines()) {
            inventoryFacade.returnVanToWarehouse(
                    sheet.getRepresentativeId(), line.getProductId(), line.getQuantity());
        }

        sheet.setStatus(ReturnSheetStatus.COMPLETED);
        ReturnSheet saved = returnSheetRepository.save(sheet);
        log.info("Completed return sheet id={} (representativeId={}, {} lines)",
                sheetId, saved.getRepresentativeId(), saved.getLines().size());
        return toResponse(saved);
    }

    /**
     * Auto-generate a DRAFT return sheet from current van state.
     *
     * <p>End-of-day convenience: instead of typing every line, the system reads whatever is
     * currently loaded on the rep's van via {@code InventoryFacade} and creates a
     * {@link ReturnSheet} with one line per loaded product. <strong>No stock moves yet</strong>
     * — the sheet is saved as {@code DRAFT}. A warehouse manager then reviews it, edits any
     * lines that don't match physical reality, and calls {@code POST /{id}/complete} to
     * actually move stock back and finalise the day.</p>
     *
     * <p>This split exists so the end-of-day reconciliation has a human verification step.
     * If the database thinks the van holds 30 Pepsi but the actual count is 25 (shrinkage,
     * a missed invoice, a miscount), the warehouse manager catches it during review — the
     * whole point of reconciliation is to surface discrepancies, not paper over them.</p>
     *
     * <p>If the van is empty (everything was sold during the day), an empty DRAFT sheet is
     * still created. That's deliberate: the document is the audit record that this rep ended
     * their day, and a missing record would be indistinguishable from "the day was never closed".</p>
     *
     * @throws BusinessException 404 if the rep does not exist;
     *                           422 if the user is not a SALES_REP
     */
    @Transactional
    public ReturnSheetResponse autoCreate(Long representativeId) {
        requireSalesRep(representativeId);

        ReturnSheet sheet = new ReturnSheet(representativeId, LocalDate.now());
        List<VanInventoryItemInfo> vanItems = inventoryFacade.getVanInventoryInfo(representativeId);

        for (VanInventoryItemInfo item : vanItems) {
            sheet.addLine(new ReturnSheetLine(item.productId(), item.quantity()));
        }

        ReturnSheet saved = returnSheetRepository.save(sheet);
        log.info("Auto-created DRAFT return sheet id={} representativeId={} lines={}",
                saved.getId(), representativeId, saved.getLines().size());
        return toResponse(saved);
    }

    @Transactional
    public ReturnSheetResponse autoComplete(Long representativeId) {
        ReturnSheetResponse draft = autoCreate(representativeId); // validates rep + builds lines
        return complete(draft.id());                              // moves stock, flips to COMPLETED
    }

    public ReturnSheetResponse getById(Long id) {
        return toResponse(findWithLinesOrThrow(id));
    }

    public PageResponse<ReturnSheetResponse> list(Long representativeId,
                                                  ReturnSheetStatus status,
                                                  LocalDate returnDate,
                                                  Pageable pageable) {
        var page = returnSheetRepository.search(representativeId, status, returnDate, pageable);

        Map<Long, ProductInfo> productInfos = fetchProductInfos(
                page.getContent().stream()
                        .flatMap(s -> s.getLines().stream().map(ReturnSheetLine::getProductId))
                        .collect(java.util.stream.Collectors.toSet()));

        // Resolve every rep id once across the page (one fetch per distinct rep, not per sheet).
        Set<Long> repIds = new HashSet<>();
        for (ReturnSheet s : page.getContent()) repIds.add(s.getRepresentativeId());
        Map<Long, String> repNames = new HashMap<>();
        for (Long id : repIds) repNames.put(id, safeUserName(id));

        return PageResponse.of(page.map(s -> ReturnSheetResponse.from(
                s, productInfos, repNames.get(s.getRepresentativeId()))));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ReturnSheet findWithLinesOrThrow(Long id) {
        return returnSheetRepository.findWithLinesById(id)
                .orElseThrow(() -> BusinessException.notFound(
                        "Return sheet not found: " + id, "RETURN_SHEET_NOT_FOUND"));
    }

    private ReturnSheetResponse toResponse(ReturnSheet sheet) {
        Set<Long> productIds = new HashSet<>();
        for (ReturnSheetLine l : sheet.getLines()) productIds.add(l.getProductId());
        return ReturnSheetResponse.from(sheet,
                fetchProductInfos(productIds),
                safeUserName(sheet.getRepresentativeId()));
    }

    private Map<Long, ProductInfo> fetchProductInfos(Set<Long> productIds) {
        Map<Long, ProductInfo> out = new HashMap<>();
        for (Long id : productIds) {
            out.put(id, inventoryFacade.getProductInfo(id));
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

    private void requireSalesRep(Long userId) {
        UserRole role = userFacade.getRoleById(userId);
        if (role != UserRole.SALES_REP) {
            throw BusinessException.unprocessable(
                    "User " + userId + " is not a SALES_REP", "NOT_A_SALES_REP");
        }
    }

    private void rejectDuplicateProducts(List<CreateReturnSheetRequest.Line> lines) {
        Set<Long> seen = new HashSet<>();
        for (var l : lines) {
            if (!seen.add(l.productId())) {
                throw BusinessException.badRequest(
                        "Duplicate product on return sheet: " + l.productId(),
                        "DUPLICATE_PRODUCT_ON_SHEET");
            }
        }
    }
}