package com.salesmanagement.inventory.internal.entity;

import com.salesmanagement.shared.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One line on a {@link StockCount}: "this product, this much physically counted, and — once
 * the count is finalized — this much the system had recorded."
 *
 * <p><strong>Two kinds of reference (project rule):</strong> unlike a {@code DemandOrderLine}
 * (whose {@code productId} is a cross-module plain {@code Long}), this line lives inside the
 * {@code inventory} module <em>with</em> {@link Product}, so the link is a real within-module
 * {@code @ManyToOne} — the same allowed exception {@link WarehouseStockItem} uses. That lets the
 * variance projection read the product's name and SKU inside the service transaction without a
 * facade round-trip.</p>
 *
 * <p><strong>Variance is not stored.</strong> Variance = {@code countedQuantity - recordedQuantity},
 * trivially derivable from two columns, so it is computed on read (in the response/projection
 * mapper) rather than persisted — one source of truth, no risk of a stored value drifting.</p>
 *
 * <p>{@code recordedQuantity} is {@code null} while the parent count is DRAFT and is populated
 * with a snapshot of {@link WarehouseStockItem#getQuantity()} (0 if the product has no warehouse
 * row) at the instant the count is finalized. {@code countedQuantity} may be 0 — the manager
 * counted the shelf and found none, a genuine finding.</p>
 *
 * <p>{@code UNIQUE(stock_count_id, product_id)} keeps each product on a count to a single line.</p>
 */
@Entity
@Table(
        name = "stock_count_lines",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_stock_count_lines_count_product",
                columnNames = {"stock_count_id", "product_id"})
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockCountLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_count_id", nullable = false)
    private StockCount stockCount;

    /** Within-module {@code @ManyToOne} to the counted product (the allowed exception). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** Quantity physically counted by the manager. {@code >= 0} (0 is valid). */
    @Column(name = "counted_quantity", nullable = false)
    private int countedQuantity;

    /**
     * Snapshot of the system-recorded warehouse quantity, captured at finalize.
     * {@code null} while the count is DRAFT; {@code >= 0} once finalized.
     */
    @Column(name = "recorded_quantity")
    private Integer recordedQuantity;

    public StockCountLine(Product product, int countedQuantity) {
        this.product = product;
        this.countedQuantity = countedQuantity;
        this.recordedQuantity = null; // populated at finalize
    }
}
