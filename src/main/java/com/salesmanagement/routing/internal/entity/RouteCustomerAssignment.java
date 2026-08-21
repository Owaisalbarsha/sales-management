package com.salesmanagement.routing.internal.entity;

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
 * One stop on a {@link Route}: "visit this customer, in this position."
 *
 * <p><strong>ERD deviation (sanctioned):</strong> the ERD models this as a pure junction
 * with a composite PK {@code (RouteID, CustomerID)} and no surrogate key. We give it a
 * surrogate {@code id} from {@link BaseEntity} instead, plus a
 * {@code UNIQUE(route_id, customer_id)} constraint that preserves exactly the same
 * integrity (one stop per customer per route). Reason: every entity in this codebase
 * extends {@code BaseEntity}; a composite-key entity cannot, and would lose the audit
 * columns and force {@code @IdClass} boilerplate. The surrogate keeps the module uniform
 * and lets {@code visit} reference a stop by a single {@code routeCustomerAssignmentId}.</p>
 *
 * <p><strong>Two kinds of reference (project rule):</strong></p>
 * <ul>
 *   <li>{@code route} — within-module {@code @ManyToOne}, the allowed exception.</li>
 *   <li>{@code customerId} — cross-module link to {@code customer.customers(id)}.
 *       Plain {@code Long}, no {@code @ManyToOne}; the DB FK is declared in the V7 migration.</li>
 * </ul>
 *
 * <p>{@code sequenceNumber} defines the visit order (1, 2, 3, ...). It is rewritten by a
 * manual reorder or by optimisation; it is never unique-constrained at the DB level so
 * that a bulk reorder can rewrite all rows without transient collisions.</p>
 */
@Entity
@Table(
        name = "route_customer_assignments",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_route_customer_assignments_route_customer",
                columnNames = {"route_id", "customer_id"})
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RouteCustomerAssignment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_id", nullable = false)
    private Route route;

    /** Cross-module FK to {@code customer.customers(id)}. */
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** Visit order on the route (> 0). Rewritten by reorder / optimise. */
    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    public RouteCustomerAssignment(Long customerId, int sequenceNumber) {
        this.customerId = customerId;
        this.sequenceNumber = sequenceNumber;
    }
}
