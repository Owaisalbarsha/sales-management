package com.salesmanagement.invoicing.internal.controller;

import com.salesmanagement.invoicing.internal.dto.CreateInvoiceRequest;
import com.salesmanagement.invoicing.internal.dto.EpodUploadResponse;
import com.salesmanagement.invoicing.internal.dto.InvoiceResponse;
import com.salesmanagement.invoicing.internal.dto.RejectInvoiceRequest;
import com.salesmanagement.invoicing.internal.dto.SubmitInvoiceRequest;
import com.salesmanagement.invoicing.internal.dto.UpdateInvoiceRequest;
import com.salesmanagement.invoicing.internal.enums.InvoiceStatus;
import com.salesmanagement.invoicing.internal.service.InvoiceService;
import com.salesmanagement.shared.api.ApiResponse;
import com.salesmanagement.shared.api.PageRequest;
import com.salesmanagement.shared.api.PageResponse;
import com.salesmanagement.shared.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.salesmanagement.invoicing.internal.dto.EpodFile;
import com.salesmanagement.invoicing.internal.enums.EpodArtifactType;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import java.time.LocalDate;

/**
 * REST API for invoices — the field-sales invoicing workflow.
 *
 * <p><strong>Authorization (D17).</strong> Reps create/edit/delete/submit their own invoices and
 * read their own history; managers and admin search all invoices and approve/reject.
 * {@code @PreAuthorize} guards the <em>role</em>; the service guards <em>ownership</em>
 * (BR-1) — a role check alone cannot express "your own draft". Warehouse managers have no access.</p>
 *
 * <p>The rep id and reviewer id are taken from the authenticated principal (D18), never from the
 * request body — a rep cannot create "as" another rep, and the review audit records the real
 * reviewer.</p>
 */
@RestController
@RequestMapping("/api/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService invoiceService;

    // ── Rep: create / edit / delete / submit own draft ───────────────────────

    /** Create a DRAFT invoice. SALES_REP; the rep is the authenticated principal. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SALES_REP')")
    public ApiResponse<InvoiceResponse> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateInvoiceRequest request) {
        return ApiResponse.created(invoiceService.createDraft(principal.getUserId(), request));
    }

    /** Replace a DRAFT invoice's lines. SALES_REP; must own it (checked in service). */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SALES_REP')")
    public ApiResponse<InvoiceResponse> update(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody UpdateInvoiceRequest request) {
        return ApiResponse.ok(
                invoiceService.updateDraft(id, principal.getUserId(), request), "Draft updated");
    }

    /** Delete a DRAFT invoice. SALES_REP; must own it (checked in service). */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SALES_REP')")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        invoiceService.deleteDraft(id, principal.getUserId());
        return ApiResponse.noContent("Draft deleted");
    }

    /** Submit a DRAFT (→ SENT): deducts stock, captures ePOD, freezes the invoice. SALES_REP; must own it. */
    @PostMapping("/{id}/submit")
    @PreAuthorize("hasRole('SALES_REP')")
    public ApiResponse<InvoiceResponse> submit(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody SubmitInvoiceRequest request) {
        return ApiResponse.ok(
                invoiceService.submit(id, principal.getUserId(), request), "Invoice submitted");
    }

    /**
     * Uploads one ePOD file (signature or delivery photo) ahead of submit, returning an opaque
     * {@code fileToken} the client echoes in the submit request. SALES_REP.
     *
     * <p>Two-step by design: the bytes travel once, here, and the submit payload stays small so a
     * retried submit on a weak field connection does not re-send the images. The token carries no
     * meaning and is not tied to an invoice until submit.</p>
     *
     * @param file the image file (multipart)
     * @return the file token to send in {@link SubmitInvoiceRequest}
     */
    @PostMapping(value = "/epod-uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SALES_REP')")
    public ApiResponse<EpodUploadResponse> uploadEpodFile(@RequestPart("file") MultipartFile file) {
        return ApiResponse.created(invoiceService.stageEpodUpload(file));
    }

    /** A rep's own invoice history (FR-81). SALES_REP. */
    @GetMapping("/me")
    @PreAuthorize("hasRole('SALES_REP')")
    public ApiResponse<PageResponse<InvoiceResponse>> listOwn(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid PageRequest pageRequest) {
        return ApiResponse.ok(invoiceService.listOwn(principal.getUserId(), pageRequest.toPageable()));
    }

    // ── Manager / admin: review + search ─────────────────────────────────────

    /** Approve a SENT invoice. SALES_MANAGER or ADMIN; reviewer is the principal. */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('SALES_MANAGER', 'ADMIN')")
    public ApiResponse<InvoiceResponse> approve(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        return ApiResponse.ok(invoiceService.approve(id, principal.getUserId()), "Invoice approved");
    }

    /** Reject a SENT invoice with a mandatory reason (BR-3). SALES_MANAGER or ADMIN; reviewer is the principal. */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('SALES_MANAGER', 'ADMIN')")
    public ApiResponse<InvoiceResponse> reject(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody RejectInvoiceRequest request) {
        return ApiResponse.ok(
                invoiceService.reject(id, principal.getUserId(), request.reason()), "Invoice rejected");
    }

    /**
     * Search/filter all invoices (FR-82) by rep, customer, status, business date. Any unset
     * filter is ignored. SALES_MANAGER or ADMIN.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('SALES_MANAGER', 'ADMIN')")
    public ApiResponse<PageResponse<InvoiceResponse>> search(
            @RequestParam(required = false) Long representativeId,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) InvoiceStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate invoiceDate,
            @Valid PageRequest pageRequest) {
        return ApiResponse.ok(invoiceService.search(
                representativeId, customerId, status, invoiceDate, pageRequest.toPageable()));
    }

    // ── Shared read (owner or oversight) ─────────────────────────────────────

    /**
     * Read one invoice, scoped to the caller: a rep may only read their own invoices, while
     * managers and admin read any invoice for oversight. The role check here admits all three
     * roles; the service applies the ownership scope based on the {@code oversight} flag.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SALES_REP', 'SALES_MANAGER', 'ADMIN')")
    public ApiResponse<InvoiceResponse> getById(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        boolean oversight = hasOversight(principal);
        return ApiResponse.ok(invoiceService.getById(id, principal.getUserId(), oversight));
    }

    /**
     * Downloads one ePOD artifact (signature or delivery photo). Scoped like {@code getById}: a
     * rep may only open their own invoice's proof, managers and admin may open any.
     *
     * <p>Returns the raw image, so it can be used directly as an {@code <img>} source or rendered
     * in the mobile app. The storage folder is not served statically — every read goes through
     * this endpoint so the scope check applies.</p>
     */
    @GetMapping("/{id}/epod/{type}")
    @PreAuthorize("hasAnyRole('SALES_REP', 'SALES_MANAGER', 'ADMIN')")
    public ResponseEntity<Resource> getEpodFile(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @PathVariable EpodArtifactType type) {
        EpodFile file = invoiceService.getEpodFile(id, type, principal.getUserId(), hasOversight(principal));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.filename() + "\"")
                .body(file.resource());
    }

    /** True when the caller holds a role that may read any invoice (manager or admin). */
    private static boolean hasOversight(UserPrincipal principal) {
        return principal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_SALES_MANAGER") || a.equals("ROLE_ADMIN"));
    }
}
