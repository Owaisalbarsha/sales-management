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
 * The quantity of one product held in the central warehouse.
 *
 * <p>There is a single warehouse in this system (no {@code Warehouse} entity), so
 * each product has <strong>at most one</strong> warehouse-stock row. That invariant
 * is enforced by {@code UNIQUE(product_id)} (decision B) — without it a bug could
 * create two rows for the same product and the on-hand quantity would be ambiguous.</p>
 *
 * <p><strong>Within-module association:</strong> the link to {@link Product} is a
 * real JPA {@code @ManyToOne} because both entities live inside the {@code inventory}
 * module. This is the deliberate exception to the project-wide "IDs cross module
 * boundaries, object references do not" rule, which applies only <em>across</em>
 * modules. Fetch is {@code LAZY}; response mappers touch {@code product} inside the
 * service transaction.</p>
 *
 * <p><strong>{@code LastUpdated} (ERD) → {@code updated_at}:</strong> the ERD lists a
 * {@code LastUpdated} field on this entity. It is satisfied by the inherited
 * {@link BaseEntity#getUpdatedAt()} column, which {@code @PreUpdate} stamps on every
 * change — exactly the semantics of "last updated". No separate column is added
 * (decision A), the same way {@code Customer}'s audit columns come from
 * {@link BaseEntity} though the ERD did not list them.</p>
 */
@Entity
@Table(
        name = "warehouse_stock_items",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_warehouse_stock_product",
                columnNames = "product_id")
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WarehouseStockItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** On-hand quantity in the warehouse. Never negative (validated + DB CHECK). */
    @Column(nullable = false)
    private int quantity = 0;

    public WarehouseStockItem(Product product, int quantity) {
        this.product = product;
        this.quantity = quantity;
    }
}
