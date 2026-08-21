package com.salesmanagement.invoicing.internal.entity;

import com.salesmanagement.invoicing.internal.enums.EpodArtifactType;
import com.salesmanagement.invoicing.internal.enums.InvoiceStatus;
import com.salesmanagement.shared.domain.BaseEntity;
import com.salesmanagement.shared.exception.BusinessException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The invoice aggregate — a digital sales invoice raised by a rep for a customer.
 *
 * <p>Owned by the {@code invoicing} module. Other modules never touch this entity; they see
 * only the module's REST responses and its published events.</p>
 *
 * <p><strong>References (project rule):</strong></p>
 * <ul>
 *   <li>{@code customerId} / {@code representativeId} / {@code visitId} / {@code reviewedById}
 *       — cross-module links (customer, identity, visit). Plain {@code Long}, never
 *       {@code @ManyToOne}. DB FKs are declared in the V10 migration.</li>
 *   <li>{@code lines} / {@code epodArtifacts} — within-module {@code @OneToMany}, the allowed
 *       exception. Both are owned by the invoice: cascade-all + orphan-removal.</li>
 * </ul>
 *
 * <p><strong>Lifecycle (D6):</strong> DRAFT (mutable, no stock moved) → SENT (immutable, stock
 * deducted at the transition, prices and ePOD frozen) → APPROVED | REJECTED (terminal,
 * informational). This entity owns the pure-invariant guards (status legality, total
 * recomputation, discount ceiling); the cross-module guards (customer active, product active,
 * price capture, stock deduction) live in {@code InvoiceService} because they need facades.</p>
 *
 * <p><strong>Money (D9):</strong> {@code totalAmount} is always the sum of line subtotals,
 * recomputed by {@link #recomputeTotal()} on every line change — never trusted from the client.
 * {@code NUMERIC(12,2)}, {@code HALF_UP}.</p>
 */
@Entity
@Table(name = "invoices")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Invoice extends BaseEntity {

    private static final int MONEY_SCALE = 2;

    // ── Cross-module references (plain Long ids) ─────────────────────────────

    /** Cross-module FK to {@code customer.customers(id)}. Must be ACTIVE at submit (service). */
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** Cross-module FK to {@code identity.users(id)} — the creating SALES_REP (from principal). */
    @Column(name = "representative_id", nullable = false)
    private Long representativeId;

    /** Cross-module FK to {@code visit.visits(id)}. NULLABLE — invoice may have no visit (D13). */
    @Column(name = "visit_id")
    private Long visitId;

    /** Cross-module FK to {@code identity.users(id)} — the reviewing manager/admin. NULL until reviewed. */
    @Column(name = "reviewed_by_id")
    private Long reviewedById;

    // ── Own fields ───────────────────────────────────────────────────────────

    /** Business date of the sale (D15). Server-set online; client-set on sync. */
    @Column(name = "invoice_date", nullable = false)
    private LocalDate invoiceDate;

    /** Sum of line subtotals; recomputed on every line change. */
    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private InvoiceStatus status;

    /** Mandatory non-blank reason when REJECTED (BR-3); NULL otherwise. */
    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    /** Mobile-generated idempotency key for offline submits (D22); NULL online. */
    @Column(name = "client_uuid", length = 36)
    private String clientUuid;

    // ── Owned children (within-module @OneToMany) ────────────────────────────

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<InvoiceLineItem> lines = new ArrayList<>();

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<EpodArtifact> epodArtifacts = new ArrayList<>();

    // ── Construction ─────────────────────────────────────────────────────────

    /**
     * Opens a new DRAFT invoice. No lines, no ePOD, zero total. Callers add lines via
     * {@link #addLine} while in DRAFT, then submit.
     *
     * @param customerId       target customer (validated ACTIVE by the service)
     * @param representativeId creating rep (from the authenticated principal)
     * @param visitId          optional linked visit; {@code null} for an ad-hoc sale
     * @param invoiceDate      business date (service supplies {@code now()} online)
     * @param clientUuid       optional mobile idempotency key; {@code null} online
     */
    public Invoice(Long customerId, Long representativeId, Long visitId,
                   LocalDate invoiceDate, String clientUuid) {
        this.customerId       = customerId;
        this.representativeId = representativeId;
        this.visitId          = visitId;
        this.invoiceDate      = invoiceDate;
        this.clientUuid       = clientUuid;
        this.status           = InvoiceStatus.DRAFT;
    }

    // ── DRAFT mutation (guarded to DRAFT only) ───────────────────────────────

    /**
     * Adds a line and recomputes the total. Only permitted while DRAFT.
     *
     * @throws BusinessException 409 if the invoice is not DRAFT
     */
    public void addLine(InvoiceLineItem line) {
        requireDraft();
        line.setInvoice(this);
        this.lines.add(line);
        recomputeTotal();
    }

    /**
     * Removes the line for the given product, if present, and recomputes the total. Only
     * permitted while DRAFT.
     *
     * @return {@code true} if a line was removed
     * @throws BusinessException 409 if the invoice is not DRAFT
     */
    public boolean removeLineByProduct(Long productId) {
        requireDraft();
        boolean removed = this.lines.removeIf(l -> l.getProductId().equals(productId));
        if (removed) {
            recomputeTotal();
        }
        return removed;
    }

    /** Finds the existing line for a product, if any (used by the service to update quantity/discount). */
    public Optional<InvoiceLineItem> findLine(Long productId) {
        return this.lines.stream().filter(l -> l.getProductId().equals(productId)).findFirst();
    }

    /** Clears all lines (service uses this when replacing the whole set on a DRAFT edit). DRAFT only. */
    public void clearLines() {
        requireDraft();
        this.lines.clear();
        recomputeTotal();
    }

    /** Recomputes {@link #totalAmount} as the sum of line subtotals ({@code HALF_UP}, 2 dp). */
    public void recomputeTotal() {
        BigDecimal sum = this.lines.stream()
                .map(InvoiceLineItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        this.totalAmount = sum.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    // ── ePOD (attached at submit) ────────────────────────────────────────────

    /**
     * Attaches an ePOD artifact. Called by the service during submit, before the status flips.
     * DRAFT-only so artifacts can never be added to an already-submitted invoice.
     *
     * @throws BusinessException 409 if the invoice is not DRAFT
     */
    public void addEpodArtifact(EpodArtifact artifact) {
        requireDraft();
        artifact.setInvoice(this);
        this.epodArtifacts.add(artifact);
    }

    /** Whether an artifact of the given type is present (service verifies both mandatory types at submit). */
    public boolean hasArtifact(EpodArtifactType type) {
        return this.epodArtifacts.stream().anyMatch(a -> a.getType() == type);
    }

    // ── State transitions ────────────────────────────────────────────────────

    /**
     * Flips DRAFT → SENT. The service must already have deducted stock and attached both
     * mandatory ePOD artifacts within the same transaction; this method only enforces the
     * state invariant and freezes the record.
     *
     * @throws BusinessException 409 if the invoice is not DRAFT
     */
    public void markSent() {
        requireDraft();
        this.status = InvoiceStatus.SENT;
    }

    /**
     * Flips SENT → APPROVED and records the reviewer. Informational (no stock effect).
     *
     * @param reviewerId the approving manager/admin (recorded in {@code reviewedById};
     *                   review time is {@code updated_at})
     * @throws BusinessException 409 if the invoice is not SENT
     */
    public void approve(Long reviewerId) {
        requireSent("approved");
        this.status = InvoiceStatus.APPROVED;
        this.reviewedById = reviewerId;
    }

    /**
     * Flips SENT → REJECTED, recording the reviewer and the mandatory reason (BR-3).
     * Informational (stock stays deducted).
     *
     * @param reviewerId the rejecting manager/admin
     * @param reason     mandatory non-blank rejection reason
     * @throws BusinessException 409 if the invoice is not SENT; 400 if the reason is blank
     */
    public void reject(Long reviewerId, String reason) {
        requireSent("rejected");
        if (reason == null || reason.isBlank()) {
            throw BusinessException.badRequest(
                    "Rejection reason is required", "INVOICE_REJECTION_REASON_REQUIRED");
        }
        this.status = InvoiceStatus.REJECTED;
        this.reviewedById = reviewerId;
        this.rejectionReason = reason.strip();
    }

    // ── Guards ───────────────────────────────────────────────────────────────

    /** True while the invoice is an editable draft. */
    public boolean isDraft() {
        return this.status == InvoiceStatus.DRAFT;
    }

    private void requireDraft() {
        if (this.status != InvoiceStatus.DRAFT) {
            throw BusinessException.conflict(
                    "Invoice " + getId() + " is not editable in status " + this.status,
                    "INVOICE_NOT_DRAFT");
        }
    }

    private void requireSent(String action) {
        if (this.status != InvoiceStatus.SENT) {
            throw BusinessException.conflict(
                    "Invoice " + getId() + " cannot be " + action + " from status " + this.status,
                    "INVOICE_NOT_SENT");
        }
    }
}
