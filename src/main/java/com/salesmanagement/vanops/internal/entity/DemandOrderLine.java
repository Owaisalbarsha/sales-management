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
 * One line on a {@link DemandOrder}: "this product, this much requested, this much
 * actually fulfilled."
 *
 * <p><strong>Two kinds of reference (project rule):</strong></p>
 * <ul>
 *   <li>{@code demandOrder} — within-module {@code @ManyToOne}, the allowed exception.</li>
 *   <li>{@code productId} — cross-module link to {@code inventory.products(id)}.
 *       Plain {@code Long}, no {@code @ManyToOne}; the DB FK is declared in the V6 migration.</li>
 * </ul>
 *
 * <p>{@code requestedQty} is what the sales manager asked for; {@code fulfilledQty} is what
 * the system trimmed it to after checking warehouse stock. The two are equal when the
 * order's status is {@code SUBMITTED}; they diverge on at least one line when the status
 * is {@code ADJUSTED}.</p>
 *
 * <p>{@code UNIQUE(demand_order_id, product_id)} keeps each product on an order to a
 * single line — preventing two "Pepsi" lines on one order with conflicting quantities.</p>
 */
@Entity
@Table(
        name = "demand_order_lines",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_demand_order_lines_order_product",
                columnNames = {"demand_order_id", "product_id"})
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DemandOrderLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "demand_order_id", nullable = false)
    private DemandOrder demandOrder;

    /** Cross-module FK to {@code inventory.products(id)}. */
    @Column(name = "product_id", nullable = false)
    private Long productId;

    /** Quantity asked for by the sales manager. */
    @Column(name = "requested_qty", nullable = false)
    private int requestedQty;

    /** Quantity actually fulfilled (after stock check). {@code <= requestedQty}; can be 0. */
    @Column(name = "fulfilled_qty", nullable = false)
    private int fulfilledQty;

    public DemandOrderLine(Long productId, int requestedQty) {
        this.productId = productId;
        this.requestedQty = requestedQty;
        this.fulfilledQty = requestedQty; // optimistic default; service trims if needed
    }
}