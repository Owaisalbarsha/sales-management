package com.salesmanagement.inventory.internal.entity;

import com.salesmanagement.inventory.internal.enums.StockCountStatus;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A physical stock-count session (FR-124, write half) — a manager's record of what was
 * physically counted in the warehouse on a given date, used to surface variance against the
 * system-recorded stock.
 *
 * <p>Owned by the {@code inventory} module. Other modules never touch this entity; they read
 * variance through {@link com.salesmanagement.inventory.api.InventoryFacade} as
 * {@link com.salesmanagement.inventory.api.StockVarianceInfo} projections.</p>
 *
 * <p><strong>Two kinds of reference (project rule):</strong></p>
 * <ul>
 *   <li>{@code countedById} — cross-module link to a user in the {@code identity} module.
 *       Plain {@code Long}, <em>not</em> {@code @ManyToOne}. The DB FK to {@code users(id)}
 *       is declared in this module's V13 migration.</li>
 *   <li>{@code lines} — within-module {@code @OneToMany} to {@link StockCountLine}, the allowed
 *       exception. Lines are owned by the count: cascade-all + orphan-removal so a line removed
 *       from a draft is deleted with it.</li>
 * </ul>
 *
 * <p><strong>Read-only / audit:</strong> a count never mutates warehouse stock. Finalization
 * only freezes the record and stamps {@code finalizedAt} — the instant the recorded snapshot
 * was taken (see {@link StockCountLine#getRecordedQuantity()}). Audit columns (id, createdAt,
 * updatedAt) come from {@link BaseEntity}.</p>
 */
@Entity
@Table(name = "stock_counts")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockCount extends BaseEntity {

    /** Cross-module FK to {@code users(id)} — the ADMIN/WAREHOUSE_MANAGER who owns the count. */
    @Column(name = "counted_by_id", nullable = false)
    private Long countedById;

    /** Business date the physical count represents. Defaults to "today" at creation. */
    @Column(name = "count_date", nullable = false)
    private LocalDate countDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private StockCountStatus status;

    /** Instant the recorded-stock snapshot was taken (the variance as-of moment). {@code null} while DRAFT. */
    @Column(name = "finalized_at")
    private Instant finalizedAt;

    /**
     * Lines belonging to this count. {@code mappedBy} = the back-reference field on the line
     * side, so the line owns the FK column. Cascade-all + orphan-removal binds the lines'
     * lifecycle to the count's.
     */
    @OneToMany(mappedBy = "stockCount", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<StockCountLine> lines = new ArrayList<>();

    public StockCount(Long countedById, LocalDate countDate) {
        this.countedById = countedById;
        this.countDate = countDate;
        this.status = StockCountStatus.DRAFT;
    }

    /** Adds a line and keeps both sides of the relationship consistent. */
    public void addLine(StockCountLine line) {
        line.setStockCount(this);
        this.lines.add(line);
    }

    /** True while the count is still an editable draft. */
    public boolean isDraft() {
        return this.status == StockCountStatus.DRAFT;
    }

    /**
     * Freezes the count: DRAFT → FINALIZED, stamping the as-of instant. The service must have
     * already snapshotted every line's {@code recordedQuantity} within the same transaction.
     *
     * @throws BusinessException 409 if the count is not DRAFT (already finalized)
     */
    public void markFinalized(Instant at) {
        requireDraft();
        this.status = StockCountStatus.FINALIZED;
        this.finalizedAt = at;
    }

    /** @throws BusinessException 409 if the count is not editable (already finalized) */
    public void requireDraft() {
        if (this.status != StockCountStatus.DRAFT) {
            throw BusinessException.conflict(
                    "Stock count " + getId() + " is not editable in status " + this.status,
                    "STOCK_COUNT_NOT_DRAFT");
        }
    }
}
