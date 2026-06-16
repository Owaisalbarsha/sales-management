package com.salesmanagement.vanops.internal.entity;

import com.salesmanagement.shared.domain.BaseEntity;
import com.salesmanagement.vanops.internal.enums.DemandOrderStatus;
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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The morning fill document — a sales manager's demand to load a representative's van
 * with a list of products and quantities for the day.
 *
 * <p>Owned by the {@code vanops} module. Other modules never touch this entity.</p>
 *
 * <p><strong>Two kinds of reference, deliberately different (project rule):</strong></p>
 * <ul>
 *   <li>{@code salesManagerId} / {@code representativeId} — cross-module links to users
 *       in the {@code identity} module. Plain {@code Long}, <em>not</em> {@code @ManyToOne}
 *       — object references never cross module boundaries. Database FKs to {@code users(id)}
 *       are declared in this module's V6 migration. Roles ({@code SALES_MANAGER} /
 *       {@code SALES_REP}) are enforced in the service via {@code UserFacade.getRoleById}.</li>
 *   <li>{@code lines} — within-module {@code @OneToMany} to {@link DemandOrderLine},
 *       the allowed exception to the no-cross-module-reference rule. Lines are owned by
 *       the order: cascaded persist, orphan-removal so removed lines are deleted with the
 *       order itself.</li>
 * </ul>
 *
 * <p>{@code orderDate} is the business date this load is for (set by the system on submit).
 * Audit columns (id, createdAt, updatedAt) come from {@link BaseEntity}.</p>
 */
@Entity
@Table(name = "demand_orders")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DemandOrder extends BaseEntity {

    /** Cross-module FK to {@code users(id)} — must reference a {@code SALES_MANAGER}. */
    @Column(name = "sales_manager_id", nullable = false)
    private Long salesManagerId;

    /** Cross-module FK to {@code users(id)} — must reference a {@code SALES_REP}. */
    @Column(name = "representative_id", nullable = false)
    private Long representativeId;

    /** Business date the load is for. Defaults to "today" at submit time. */
    @Column(name = "order_date", nullable = false)
    private LocalDate orderDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DemandOrderStatus status;

    /**
     * Lines belonging to this order. {@code mappedBy} = the back-reference field on the
     * line side, so the line owns the FK column. Cascade-all + orphan-removal so the lines'
     * lifecycle is bound to the order's.
     */
    @OneToMany(mappedBy = "demandOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DemandOrderLine> lines = new ArrayList<>();

    public DemandOrder(Long salesManagerId, Long representativeId, LocalDate orderDate) {
        this.salesManagerId = salesManagerId;
        this.representativeId = representativeId;
        this.orderDate = orderDate;
        this.status = DemandOrderStatus.SUBMITTED;
    }

    /** Adds a line and keeps both sides of the relationship consistent. */
    public void addLine(DemandOrderLine line) {
        line.setDemandOrder(this);
        this.lines.add(line);
    }
}