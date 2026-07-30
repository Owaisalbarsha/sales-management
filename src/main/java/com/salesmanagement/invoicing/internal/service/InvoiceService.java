package com.salesmanagement.invoicing.internal.service;

import com.salesmanagement.customer.api.CustomerFacade;
import com.salesmanagement.identity.api.UserFacade;
import com.salesmanagement.inventory.api.InventoryFacade;
import com.salesmanagement.inventory.api.ProductInfo;
import com.salesmanagement.invoicing.api.InvoiceApprovedEvent;
import com.salesmanagement.invoicing.api.InvoiceRejectedEvent;
import com.salesmanagement.invoicing.api.InvoiceSubmittedEvent;
import com.salesmanagement.invoicing.internal.dto.CreateInvoiceRequest;
import com.salesmanagement.invoicing.internal.dto.EpodUploadResponse;
import com.salesmanagement.invoicing.internal.dto.InvoiceResponse;
import com.salesmanagement.invoicing.internal.dto.SubmitInvoiceRequest;
import com.salesmanagement.invoicing.internal.dto.UpdateInvoiceRequest;
import com.salesmanagement.invoicing.internal.entity.EpodArtifact;
import com.salesmanagement.invoicing.internal.entity.Invoice;
import com.salesmanagement.invoicing.internal.entity.InvoiceLineItem;
import com.salesmanagement.invoicing.internal.enums.EpodArtifactType;
import com.salesmanagement.invoicing.internal.enums.InvoiceStatus;
import com.salesmanagement.invoicing.internal.repository.InvoiceRepository;
import com.salesmanagement.shared.api.PageResponse;
import com.salesmanagement.shared.exception.BusinessException;
import com.salesmanagement.visit.api.VisitFacade;
import com.salesmanagement.visit.api.VisitInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.math.RoundingMode;
import java.util.Optional;
import com.salesmanagement.invoicing.internal.dto.EpodFile;
import com.salesmanagement.invoicing.internal.entity.EpodArtifact;
import java.nio.file.Path;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Business logic for invoices — the field-sales invoicing workflow.
 *
 * <p><strong>Create (DRAFT).</strong> Validates the customer (exists + active), the optional
 * visit (exists and belongs to this customer + rep), and the line products; captures each
 * product's current price from inventory; saves a DRAFT. No stock moves. Idempotent on
 * {@code clientUuid} for offline retries (D22).</p>
 *
 * <p><strong>Edit (DRAFT).</strong> Replaces the whole line set, re-capturing current prices
 * (D5a). Light validation only (product exists, quantity &gt; 0); the hard gate is at submit.</p>
 *
 * <p><strong>Submit (DRAFT → SENT).</strong> The hard gate (D5b): customer still active, every
 * product still active is <em>not</em> required (discontinued stock may be sold, D12) — but the
 * product must exist and there must be sufficient van stock. Deducts stock atomically per line
 * via {@code InventoryFacade} in one transaction (D1); captures + hashes the mandatory ePOD
 * artifacts (D16); freezes prices; flips to SENT; publishes {@code InvoiceSubmittedEvent}.</p>
 *
 * <p><strong>Review (SENT → APPROVED | REJECTED).</strong> Informational (D-review): records the
 * reviewer, publishes the corresponding event. Rejection requires a non-blank reason (BR-3).</p>
 *
 * <p><strong>Cross-module dependencies:</strong> {@code CustomerFacade}, {@code VisitFacade},
 * {@code InventoryFacade}, {@code UserFacade}. No reach into another module's tables.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InvoiceService {

    /** Upload ceiling for a single ePOD image. A signature is a few KB; a photo, a few MB. */
    private static final long MAX_EPOD_FILE_BYTES = 5L * 1024 * 1024;

    private final InvoiceRepository invoiceRepository;
    private final CustomerFacade customerFacade;
    private final VisitFacade visitFacade;
    private final InventoryFacade inventoryFacade;
    private final UserFacade userFacade;
    private final EpodStorageService epodStorage;
    private final InvoicePdfService invoicePdfService;
    private final ApplicationEventPublisher events;

    // ═══════════════════════════════════════════════════════════════════════
    //  CREATE (DRAFT)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * A rep opens a new DRAFT invoice for a customer. Prices are captured now and re-captured on
     * each edit; nothing is deducted until submit.
     *
     * @param representativeId the creating rep (from the authenticated principal, D18)
     * @param request          customer, optional visit, optional clientUuid, and lines
     * @throws BusinessException 400 duplicate products; 404 unknown customer/visit/product;
     *                           422 inactive customer, or visit not matching customer + rep
     */
    @Transactional
    public InvoiceResponse createDraft(Long representativeId, CreateInvoiceRequest request) {
        // Idempotency: a retried offline submit carries the same clientUuid — return the existing row.
        if (request.clientUuid() != null && !request.clientUuid().isBlank()) {
            var existing = invoiceRepository.findByClientUuid(request.clientUuid());
            if (existing.isPresent()) {
                log.info("Idempotent create: clientUuid={} already maps to invoice id={}",
                        request.clientUuid(), existing.get().getId());
                return toResponse(reload(existing.get().getId()));
            }
        }

        requireActiveCustomer(request.customerId());
        validateVisitIfPresent(request.visitId(), request.customerId(), representativeId);
        rejectDuplicateProducts(request.lines().stream().map(CreateInvoiceRequest.Line::productId).toList());

        Invoice invoice = new Invoice(
                request.customerId(),
                representativeId,
                request.visitId(),
                LocalDate.now(),                 // D15: server-set online
                blankToNull(request.clientUuid()));

        for (CreateInvoiceRequest.Line reqLine : request.lines()) {
            invoice.addLine(buildLine(reqLine.productId(), reqLine.quantity(), reqLine.discount()));
        }

        Invoice saved = invoiceRepository.save(invoice);
        log.info("Created DRAFT invoice id={} representativeId={} customerId={} lines={}",
                saved.getId(), representativeId, request.customerId(), saved.getLines().size());
        return toResponse(saved);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  EDIT (DRAFT)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Replaces a DRAFT invoice's lines, re-capturing current prices (D5a). Ownership is enforced
     * by the caller (controller passes the principal; this method checks it).
     *
     * @throws BusinessException 403 if the caller does not own the invoice; 404 unknown
     *                           invoice/product; 409 if not DRAFT; 400 duplicate products
     */
    @Transactional
    public InvoiceResponse updateDraft(Long invoiceId, Long callerId, UpdateInvoiceRequest request) {
        Invoice invoice = reload(invoiceId);
        requireOwner(invoice, callerId);
        requireDraft(invoice);
        rejectDuplicateProducts(request.lines().stream().map(UpdateInvoiceRequest.Line::productId).toList());

        // Clear-and-rebuild would make Hibernate insert the new rows BEFORE deleting the old ones
        // (it orders INSERTs ahead of DELETEs on flush), colliding with
        // UNIQUE(invoice_id, product_id) whenever a product stays on the invoice. So: mutate the
        // surviving lines in place, add only the genuinely new ones, remove only the dropped ones.
        Set<Long> requestedProductIds = request.lines().stream()
                .map(UpdateInvoiceRequest.Line::productId)
                .collect(Collectors.toSet());

        // Drop lines whose product is no longer on the invoice.
        List<Long> removedProductIds = invoice.getLines().stream()
                .map(InvoiceLineItem::getProductId)
                .filter(pid -> !requestedProductIds.contains(pid))
                .toList();
        removedProductIds.forEach(invoice::removeLineByProduct);

        // Update survivors in place, re-capturing the current price (D5a); add new products.
        for (UpdateInvoiceRequest.Line reqLine : request.lines()) {
            Optional<InvoiceLineItem> existing = invoice.findLine(reqLine.productId());
            if (existing.isPresent()) {
                applyLineUpdate(existing.get(), reqLine.quantity(), reqLine.discount());
            } else {
                invoice.addLine(buildLine(reqLine.productId(), reqLine.quantity(), reqLine.discount()));
            }
        }
        invoice.recomputeTotal();

        Invoice saved = invoiceRepository.save(invoice);
        log.info("Updated DRAFT invoice id={} lines={}", invoiceId, saved.getLines().size());
        return toResponse(saved);
    }

    /** Re-captures the current price onto an existing line and applies the new quantity/discount. */
    private void applyLineUpdate(InvoiceLineItem line, int quantity, BigDecimal discount) {
        requireProductExists(line.getProductId());
        BigDecimal price = inventoryFacade.getProductPrice(line.getProductId());  // re-captured (D5a)
        BigDecimal disc  = discount == null ? BigDecimal.ZERO : discount;

        BigDecimal gross = price.multiply(BigDecimal.valueOf(quantity));
        if (disc.compareTo(gross) > 0) {
            throw BusinessException.unprocessable(
                    "Discount " + disc + " exceeds line total " + gross
                            + " for product " + line.getProductId(),
                    "INVOICE_DISCOUNT_EXCEEDS_LINE");
        }
        line.setQuantity(quantity);
        line.setPrice(price.setScale(2, RoundingMode.HALF_UP));
        line.setDiscount(disc.setScale(2, RoundingMode.HALF_UP));
        line.recompute();
    }

    /**
     * Deletes a DRAFT invoice. Own draft only; SENT/terminal invoices are never deletable.
     *
     * @throws BusinessException 403 not owner; 404 unknown invoice; 409 if not DRAFT
     */
    @Transactional
    public void deleteDraft(Long invoiceId, Long callerId) {
        Invoice invoice = reload(invoiceId);
        requireOwner(invoice, callerId);
        requireDraft(invoice);
        invoiceRepository.delete(invoice);
        log.info("Deleted DRAFT invoice id={}", invoiceId);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  SUBMIT (DRAFT → SENT)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Submits a DRAFT: hard-gates validity, deducts van stock atomically, captures + hashes the
     * mandatory ePOD artifacts, freezes the invoice as SENT, and publishes the submit event.
     *
     * <p>All within one transaction: if any line's stock deduction fails (BR-4, 422 from
     * inventory), the whole submit rolls back and no stock moves. The ePOD file writes happen
     * after the stock deductions succeed and before the status flip; the hash is computed over
     * the frozen total.</p>
     *
     * @param invoiceId the DRAFT to submit
     * @param callerId  the rep submitting (must own it, BR-1)
     * @param request   the ePOD artifacts captured in the field (both mandatory types)
     * @throws BusinessException 403 not owner; 404 unknown invoice/product; 409 not DRAFT;
     *                           422 inactive customer, insufficient stock, or missing ePOD type
     */
    @Transactional
    public InvoiceResponse submit(Long invoiceId, Long callerId, SubmitInvoiceRequest request) {
        Invoice invoice = reload(invoiceId);
        requireOwner(invoice, callerId);
        requireDraft(invoice);

        if (invoice.getLines().isEmpty()) {
            throw BusinessException.unprocessable(
                    "Cannot submit an invoice with no lines", "INVOICE_NO_LINES");
        }

        // ── Hard gate (D5b) ──────────────────────────────────────────────────
        // Customer must still be active. Product-active is deliberately NOT required (D12).
        requireActiveCustomer(invoice.getCustomerId());
        for (InvoiceLineItem line : invoice.getLines()) {
            requireProductExists(line.getProductId());   // exists; may be discontinued (D12)
        }
        requireBothEpodTypes(request);

        // ── Atomic stock deduction (D1, BR-4) ────────────────────────────────
        // One deduction per line; inventory enforces the never-negative guard atomically.
        // A failure here throws 422 and rolls the whole transaction back.
        for (InvoiceLineItem line : invoice.getLines()) {
            inventoryFacade.deductVanStock(
                    invoice.getRepresentativeId(), line.getProductId(), line.getQuantity());
        }

        // ── Freeze total, then capture + hash ePOD over the frozen total (D16) ─
        invoice.recomputeTotal();
        BigDecimal frozenTotal = invoice.getTotalAmount();
        for (SubmitInvoiceRequest.Artifact art : request.artifacts()) {
            byte[] bytes = epodStorage.readStaged(art.fileToken());
            // Hash binds the file to the invoice AND to when/where it was captured, so editing
            // the proof metadata later breaks the fingerprint too.
            String hash  = epodStorage.hash(bytes, invoiceId, invoice.getCustomerId(), frozenTotal,
                    art.capturedAt(), art.latitude(), art.longitude());
            String url   = epodStorage.store(art.fileToken(), invoiceId, art.type());
            invoice.addEpodArtifact(new EpodArtifact(
                    art.type(), url, hash, art.latitude(), art.longitude(), art.capturedAt()));
        }

        // ── Flip to SENT (immutable thereafter) ──────────────────────────────
        invoice.markSent();
        Invoice saved = invoiceRepository.save(invoice);

        events.publishEvent(new InvoiceSubmittedEvent(
                saved.getId(), saved.getRepresentativeId(), Instant.now()));

        log.info("Submitted invoice id={} representativeId={} total={} lines={} epod={}",
                saved.getId(), saved.getRepresentativeId(), saved.getTotalAmount(),
                saved.getLines().size(), saved.getEpodArtifacts().size());
        return toResponse(saved);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ePOD UPLOAD (pre-submit)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Stages an uploaded ePOD file and returns its token. Validates that the upload is a non-empty
     * image within the size limit before writing anything to disk.
     *
     * @throws BusinessException 400 empty file or non-image content type; 422 file too large or
     *                           unreadable
     */
    public EpodUploadResponse stageEpodUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BusinessException.badRequest("Uploaded file is empty", "EPOD_UPLOAD_EMPTY");
        }
        if (file.getSize() > MAX_EPOD_FILE_BYTES) {
            throw BusinessException.unprocessable(
                    "Uploaded file exceeds " + (MAX_EPOD_FILE_BYTES / (1024 * 1024)) + " MB",
                    "EPOD_UPLOAD_TOO_LARGE");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw BusinessException.badRequest(
                    "Uploaded file must be an image", "EPOD_UPLOAD_NOT_IMAGE");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.error("Failed to read multipart ePOD upload", e);
            throw BusinessException.unprocessable(
                    "Could not read uploaded file", "EPOD_UPLOAD_READ_FAILED");
        }
        String token = epodStorage.stage(bytes, file.getOriginalFilename());
        log.info("Staged ePOD upload token={} bytes={}", token, bytes.length);
        return new EpodUploadResponse(token, bytes.length);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  REVIEW (SENT → APPROVED | REJECTED)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * A manager/admin approves a SENT invoice. Informational — no stock effect.
     *
     * @throws BusinessException 404 unknown invoice; 409 if not SENT
     */
    @Transactional
    public InvoiceResponse approve(Long invoiceId, Long reviewerId) {
        Invoice invoice = reload(invoiceId);
        invoice.approve(reviewerId);
        Invoice saved = invoiceRepository.save(invoice);

        events.publishEvent(new InvoiceApprovedEvent(
                saved.getId(), saved.getRepresentativeId(), Instant.now()));

        log.info("Approved invoice id={} by reviewerId={}", invoiceId, reviewerId);
        return toResponse(saved);
    }

    /**
     * A manager/admin rejects a SENT invoice with a mandatory reason (BR-3). Informational —
     * stock stays deducted (D1).
     *
     * @throws BusinessException 404 unknown invoice; 409 if not SENT; 400 blank reason
     */
    @Transactional
    public InvoiceResponse reject(Long invoiceId, Long reviewerId, String reason) {
        Invoice invoice = reload(invoiceId);
        invoice.reject(reviewerId, reason);   // entity re-checks non-blank (defence in depth)
        Invoice saved = invoiceRepository.save(invoice);

        events.publishEvent(new InvoiceRejectedEvent(
                saved.getId(), saved.getRepresentativeId(), saved.getRejectionReason(), Instant.now()));

        log.info("Rejected invoice id={} by reviewerId={}", invoiceId, reviewerId);
        return toResponse(saved);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  READS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Reads one invoice, scoped to the caller. A rep may only read their own invoices; managers
     * and admin read any invoice for oversight.
     *
     * @param id           the invoice to read
     * @param callerId     the authenticated user
     * @param oversight    {@code true} when the caller holds SALES_MANAGER or ADMIN
     * @throws BusinessException 404 if no such invoice; 403 if a rep reads someone else's invoice
     */
    public InvoiceResponse getById(Long id, Long callerId, boolean oversight) {
        Invoice invoice = reload(id);
        if (!oversight) {
            requireOwner(invoice, callerId);
        }
        return toResponse(invoice);
    }

    /**
     * Resolves one ePOD artifact's file for download, applying the same read scope as
     * {@link #getById}: a rep may only open their own invoice's proof; managers and admin may
     * open any.
     *
     * @throws BusinessException 404 unknown invoice or no such artifact; 403 if a rep requests
     *                           another rep's proof
     */
    public EpodFile getEpodFile(Long invoiceId, EpodArtifactType type, Long callerId, boolean oversight) {
        Invoice invoice = reload(invoiceId);
        if (!oversight) {
            requireOwner(invoice, callerId);
        }
        EpodArtifact artifact = invoice.getEpodArtifacts().stream()
                .filter(a -> a.getType() == type)
                .findFirst()
                .orElseThrow(() -> BusinessException.notFound(
                        "No " + type + " artifact on invoice " + invoiceId, "EPOD_ARTIFACT_NOT_FOUND"));

        return new EpodFile(
                epodStorage.load(artifact.getUrl()),
                epodStorage.contentTypeOf(artifact.getUrl()),
                Path.of(artifact.getUrl()).getFileName().toString());
    }

    /** A rep's own invoice history (FR-81), newest first. */
    public PageResponse<InvoiceResponse> listOwn(Long representativeId, Pageable pageable) {
        Page<Invoice> page = invoiceRepository.findByRepresentativeId(representativeId, pageable);
        return toPageResponse(page);
    }

    /** Manager search across all invoices (FR-82), optional filters. */
    public PageResponse<InvoiceResponse> search(Long representativeId,
                                                Long customerId,
                                                InvoiceStatus status,
                                                LocalDate invoiceDate,
                                                Pageable pageable) {
        Page<Invoice> page = invoiceRepository.search(
                representativeId, customerId, status, invoiceDate, pageable);
        return toPageResponse(page);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Line building & validation helpers
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Builds a line, capturing the product's current price (BR-9, D10) and validating the
     * discount ceiling (D7). Product existence is validated here (404 vs raw FK on save).
     */
    private InvoiceLineItem buildLine(Long productId, int quantity, BigDecimal discount) {
        requireProductExists(productId);
        BigDecimal price = inventoryFacade.getProductPrice(productId);  // captured now
        BigDecimal disc  = discount == null ? BigDecimal.ZERO : discount;

        BigDecimal gross = price.multiply(BigDecimal.valueOf(quantity));
        if (disc.compareTo(gross) > 0) {
            throw BusinessException.unprocessable(
                    "Discount " + disc + " exceeds line total " + gross + " for product " + productId,
                    "INVOICE_DISCOUNT_EXCEEDS_LINE");
        }
        return new InvoiceLineItem(productId, quantity, price, disc);
    }

    private void requireActiveCustomer(Long customerId) {
        if (!customerFacade.exists(customerId)) {
            throw BusinessException.notFound(
                    "Customer not found: " + customerId, "CUSTOMER_NOT_FOUND");
        }
        if (!customerFacade.isActive(customerId)) {
            throw BusinessException.unprocessable(
                    "Customer is not active: " + customerId, "CUSTOMER_NOT_ACTIVE");
        }
    }

    private void requireProductExists(Long productId) {
        if (!inventoryFacade.productExists(productId)) {
            throw BusinessException.notFound(
                    "Product not found: " + productId, "PRODUCT_NOT_FOUND");
        }
    }

    /**
     * When a visit is supplied it must exist and belong to this customer and this rep (D13) —
     * a rep cannot staple someone else's visit to their invoice.
     */
    private void validateVisitIfPresent(Long visitId, Long customerId, Long representativeId) {
        if (visitId == null) {
            return;
        }
        if (!visitFacade.existsById(visitId)) {
            throw BusinessException.notFound("Visit not found: " + visitId, "VISIT_NOT_FOUND");
        }
        VisitInfo visit = visitFacade.getVisitInfo(visitId);
        if (!visit.customerId().equals(customerId) || !visit.representativeId().equals(representativeId)) {
            throw BusinessException.unprocessable(
                    "Visit " + visitId + " does not match this customer and representative",
                    "VISIT_MISMATCH");
        }
    }

    private void rejectDuplicateProducts(List<Long> productIds) {
        Set<Long> seen = new HashSet<>();
        for (Long id : productIds) {
            if (!seen.add(id)) {
                throw BusinessException.badRequest(
                        "Duplicate product on invoice: " + id, "INVOICE_DUPLICATE_PRODUCT");
            }
        }
    }

    private void requireBothEpodTypes(SubmitInvoiceRequest request) {
        Set<EpodArtifactType> present = request.artifacts().stream()
                .map(SubmitInvoiceRequest.Artifact::type)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(EpodArtifactType.class)));
        for (EpodArtifactType required : EpodArtifactType.values()) {
            if (!present.contains(required)) {
                throw BusinessException.unprocessable(
                        "Missing mandatory ePOD artifact: " + required, "EPOD_ARTIFACT_MISSING");
            }
        }
    }

    private void requireOwner(Invoice invoice, Long callerId) {
        if (!invoice.getRepresentativeId().equals(callerId)) {
            throw BusinessException.forbidden(
                    "Invoice " + invoice.getId() + " does not belong to you", "INVOICE_NOT_OWNED");
        }
    }

    private void requireDraft(Invoice invoice) {
        if (!invoice.isDraft()) {
            throw BusinessException.conflict(
                    "Invoice " + invoice.getId() + " is not editable in status " + invoice.getStatus(),
                    "INVOICE_NOT_DRAFT");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  PDF EXPORT (customer copy)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Renders a single invoice to a customer-facing PDF, applying the same read scope as
     * {@link #getById}: a rep may export only their own invoices; managers and admin, any.
     *
     * <p>DRAFT invoices cannot be exported — a customer copy of an editable, unsubmitted invoice
     * with no proof-of-delivery is a contradiction. Only SENT/APPROVED/REJECTED render.</p>
     *
     * @param id        the invoice to export
     * @param callerId  the authenticated user
     * @param oversight {@code true} when the caller is SALES_MANAGER or ADMIN
     * @return the PDF bytes
     * @throws BusinessException 404 unknown invoice; 403 a rep exporting another rep's invoice;
     *                           409 attempting to export a DRAFT
     */
    public byte[] exportPdf(Long id, Long callerId, boolean oversight) {
        Invoice invoice = reload(id);
        if (!oversight) {
            requireOwner(invoice, callerId);
        }
        if (invoice.isDraft()) {
            throw BusinessException.conflict(
                    "A DRAFT invoice cannot be exported to PDF", "INVOICE_PDF_DRAFT_NOT_ALLOWED");
        }

        // Resolve line product names/SKUs and party names once, reusing the same safe lookups
        // as the JSON responses (null-tolerant, never fails the export on an orphaned reference).
        Map<Long, ProductInfo> productInfos = fetchProductInfos(
                invoice.getLines().stream().map(InvoiceLineItem::getProductId).collect(Collectors.toSet()));
        Map<Long, String> productNames = new HashMap<>();
        Map<Long, String> productSkus = new HashMap<>();
        productInfos.forEach((pid, info) -> {
            productNames.put(pid, info != null ? info.name() : null);
            productSkus.put(pid, info != null ? info.sku() : null);
        });

        String customerName = safeCustomerName(invoice.getCustomerId());
        String repName      = safeUserName(invoice.getRepresentativeId());

        return invoicePdfService.render(invoice, productNames, productSkus, customerName, repName);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Loading & response mapping
    // ═══════════════════════════════════════════════════════════════════════

    private Invoice reload(Long id) {
        Invoice invoice = invoiceRepository.findWithLinesById(id)
                .orElseThrow(() -> BusinessException.notFound(
                        "Invoice not found: " + id, "INVOICE_NOT_FOUND"));
        // Second pass populates epodArtifacts on this same managed instance.
        invoiceRepository.findWithEpodById(id);
        return invoice;
    }

    /** Maps a single invoice, resolving product infos and user/customer names once. */
    private InvoiceResponse toResponse(Invoice invoice) {
        Map<Long, ProductInfo> productInfos = fetchProductInfos(
                invoice.getLines().stream().map(InvoiceLineItem::getProductId).collect(Collectors.toSet()));

        String customerName = safeCustomerName(invoice.getCustomerId());
        String repName      = safeUserName(invoice.getRepresentativeId());
        String reviewerName = invoice.getReviewedById() == null
                ? null : safeUserName(invoice.getReviewedById());

        return InvoiceResponse.from(invoice, productInfos, customerName, repName, reviewerName);
    }

    /** Maps a page of invoices, batching product-info and user/customer-name lookups across rows. */
    private PageResponse<InvoiceResponse> toPageResponse(Page<Invoice> page) {
        List<Invoice> content = page.getContent();

        // One product-info map across every line on the page.
        Set<Long> productIds = content.stream()
                .flatMap(i -> i.getLines().stream().map(InvoiceLineItem::getProductId))
                .collect(Collectors.toSet());
        Map<Long, ProductInfo> productInfos = fetchProductInfos(productIds);

        // One name cache across every customer + rep + reviewer on the page.
        Map<Long, String> userNames = new HashMap<>();
        Map<Long, String> customerNames = new HashMap<>();
        for (Invoice inv : content) {
            customerNames.computeIfAbsent(inv.getCustomerId(), this::safeCustomerName);
            userNames.computeIfAbsent(inv.getRepresentativeId(), this::safeUserName);
            if (inv.getReviewedById() != null) {
                userNames.computeIfAbsent(inv.getReviewedById(), this::safeUserName);
            }
        }

        Page<InvoiceResponse> mapped = page.map(inv -> InvoiceResponse.from(
                inv,
                productInfos,
                customerNames.get(inv.getCustomerId()),
                userNames.get(inv.getRepresentativeId()),
                inv.getReviewedById() == null ? null : userNames.get(inv.getReviewedById())));

        return PageResponse.of(mapped);
    }

    private Map<Long, ProductInfo> fetchProductInfos(Set<Long> productIds) {
        Map<Long, ProductInfo> map = new HashMap<>();
        for (Long pid : productIds) {
            try {
                map.put(pid, inventoryFacade.getProductInfo(pid));
            } catch (BusinessException e) {
                // Product removed since the invoice was raised — enrich with null, never fail a read.
                map.put(pid, null);
            }
        }
        return map;
    }

    private String safeUserName(Long userId) {
        try {
            return userFacade.getNameById(userId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String safeCustomerName(Long customerId) {
        try {
            return customerFacade.getCustomerInfo(customerId).name();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }
}
