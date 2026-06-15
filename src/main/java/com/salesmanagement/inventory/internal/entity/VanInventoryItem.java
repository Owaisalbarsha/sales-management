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
 * The quantity of one product currently loaded on a sales rep's van.
 *
 * <p>Each (representative, product) pair has <strong>at most one</strong> row, enforced
 * by {@code UNIQUE(representative_id, product_id)} (decision C). This is what makes the
 * BR-4 deduction unambiguous: a sale subtracts from exactly one row.</p>
 *
 * <p><strong>Two kinds of reference, deliberately different:</strong></p>
 * <ul>
 *   <li>{@code representativeId} — a <em>cross-module</em> link to a user in the
 *       {@code identity} module. It is a plain {@code Long}, <strong>not</strong> a
 *       {@code @ManyToOne User}: object references never cross module boundaries.
 *       The database FK to {@code users(id)} is declared in this module's V5 migration
 *       (the dependent module owns the constraint). Per the ERD, this user must have
 *       role {@code SALES_REP} — enforced at the application layer in {@code StockService}
 *       via {@code UserFacade.getRoleById}, because a DB FK cannot express "and the role
 *       must be SALES_REP".</li>
 *   <li>{@code product} — a <em>within-module</em> link, so a real {@code @ManyToOne}
 *       is used (the allowed exception to the no-cross-module-reference rule).</li>
 * </ul>
 *
 * <p>Van quantity is never mutated through a REST endpoint. It changes only via the
 * facade: up on restock approval ({@code transferWarehouseToVan}), down on a sale
 * ({@code deductVanStock}). The {@code CHECK (quantity >= 0)} column constraint is the
 * last-ditch database backstop for BR-4.</p>
 */
@Entity
@Table(
        name = "van_inventory_items",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_van_inventory_rep_product",
                columnNames = {"representative_id", "product_id"})
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VanInventoryItem extends BaseEntity {

    /**
     * Owning sales rep's user id. Cross-module FK to {@code users(id)} (declared in the
     * V5 migration). Must reference a {@code SALES_REP} — enforced in the application
     * layer, not by the database.
     */
    @Column(name = "representative_id", nullable = false)
    private Long representativeId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** Quantity on the van. Never negative (BR-4: validated in service + DB CHECK). */
    @Column(nullable = false)
    private int quantity = 0;

    public VanInventoryItem(Long representativeId, Product product, int quantity) {
        this.representativeId = representativeId;
        this.product = product;
        this.quantity = quantity;
    }
}
