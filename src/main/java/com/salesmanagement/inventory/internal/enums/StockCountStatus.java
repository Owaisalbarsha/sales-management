package com.salesmanagement.inventory.internal.enums;

/**
 * Lifecycle of a physical stock count (FR-124).
 *
 * <ul>
 *   <li>{@code DRAFT} — the manager is entering counted quantities. Lines are mutable.
 *       No recorded-stock snapshot has been taken yet, so variance is undefined.</li>
 *   <li>{@code FINALIZED} — the count is closed. At the moment of finalization the system
 *       snapshotted the recorded warehouse quantity onto every line (the variance "as-of"
 *       instant). The count is immutable from here; this is a terminal state.</li>
 * </ul>
 *
 * <p>Unlike {@code ReturnSheetStatus} (DRAFT/COMPLETED), finalizing a count moves <em>no</em>
 * stock — it only freezes the audit record. Reconciliation (correcting stock) is separate,
 * future work.</p>
 */
public enum StockCountStatus {
    DRAFT,
    FINALIZED
}
