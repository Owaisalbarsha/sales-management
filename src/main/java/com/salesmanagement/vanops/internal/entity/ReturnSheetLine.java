package com.salesmanagement.vanops.internal.entity;

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
 * One line on a {@link ReturnSheet}: "this product, this quantity coming back."
 *
 * <p>{@code UNIQUE(return_sheet_id, product_id)} — each product appears at most once on a
 * sheet (a rep doesn't return Pepsi twice on the same form).</p>
 */
@Entity
@Table(
        name = "return_sheet_lines",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_return_sheet_lines_sheet_product",
                columnNames = {"return_sheet_id", "product_id"})
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReturnSheetLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "return_sheet_id", nullable = false)
    private ReturnSheet returnSheet;

    /** Cross-module FK to {@code inventory.products(id)}. */
    @Column(name = "product_id", nullable = false)
    private Long productId;

    /** Quantity returned. Must be > 0 (a zero-quantity line is meaningless on a return sheet). */
    @Column(nullable = false)
    private int quantity;

    public ReturnSheetLine(Long productId, int quantity) {
        this.productId = productId;
        this.quantity = quantity;
    }
}