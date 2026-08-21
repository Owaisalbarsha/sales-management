package com.salesmanagement.invoicing.internal.enums;

/**
 * Lifecycle states of an invoice (decision D6).
 *
 * <pre>
 *   DRAFT ──submit──▶ SENT ──approve──▶ APPROVED   (terminal)
 *                       └────reject───▶ REJECTED   (terminal)
 * </pre>
 *
 * <ul>
 *   <li>{@code DRAFT} — server-side, editable staging area. No stock has moved. Lines may be
 *       added/removed/edited; prices are re-captured from inventory on each mutation (D5a).
 *       Only the creating rep may edit or delete it (BR-1).</li>
 *   <li>{@code SENT} — submitted and <em>immutable</em>. Van stock was deducted atomically at
 *       this transition (D1); the captured prices are frozen (BR-9); the ePOD artifacts are
 *       captured and frozen. No further edits — only a manager review may follow.</li>
 *   <li>{@code APPROVED} — a manager validated the invoice as clean revenue. Terminal.
 *       Purely informational in the MVP: no stock or state side-effects.</li>
 *   <li>{@code REJECTED} — a manager flagged the invoice as disputed/erroneous, with a
 *       mandatory non-blank reason (BR-3). Terminal. The sale physically happened and stock
 *       stays deducted (D1); a correction is a brand-new invoice, never a re-submit.</li>
 * </ul>
 *
 * <p>Stored as {@code VARCHAR} via {@code @Enumerated(EnumType.STRING)}; the DB
 * {@code chk_invoices_status} CHECK constraint mirrors exactly this set.</p>
 */
public enum InvoiceStatus {
    DRAFT,
    SENT,
    APPROVED,
    REJECTED
}
