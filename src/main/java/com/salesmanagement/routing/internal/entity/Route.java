package com.salesmanagement.routing.internal.entity;

import com.salesmanagement.routing.internal.enums.RouteStatus;
import com.salesmanagement.shared.domain.BaseEntity;
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
 * A rep's planned visit schedule for one day: a sequenced list of customer stops.
 *
 * <p>Owned by the {@code routing} module. Other modules never touch this entity —
 * they read it through {@code RoutingFacade}.</p>
 *
 * <p><strong>Two kinds of reference, deliberately different (project rule):</strong></p>
 * <ul>
 *   <li>{@code representativeId} / {@code territoryId} — cross-module links to the
 *       {@code identity} and {@code territory} modules. Plain {@code Long}, <em>not</em>
 *       {@code @ManyToOne} — object references never cross module boundaries. The DB FKs
 *       to {@code users(id)} and {@code territories(id)} are declared in this module's V7
 *       migration. The rep's {@code SALES_REP} role and the territory's existence are
 *       validated in {@code RouteService} via {@code UserFacade} / {@code TerritoryFacade}.</li>
 *   <li>{@code assignments} — within-module {@code @OneToMany} to
 *       {@link RouteCustomerAssignment}, the allowed exception. The stops are owned by
 *       the route: cascade-all + orphan-removal, so a removed stop is deleted with it.</li>
 * </ul>
 *
 * <p><strong>ERD deviation (sanctioned):</strong> {@code territoryId} is not in the ERD's
 * {@code ROUTE} block. It was added so the route can enforce that every assigned customer
 * belongs to one territory (the only way to express "the rep's territory" — there is no
 * rep→territory link anywhere in the schema).</p>
 *
 * <p>{@code optimized} maps to the ERD's {@code IsOptimized} flag: {@code true} once the
 * sequence numbers were auto-computed by {@code RouteOptimizationService}, reset to
 * {@code false} the moment a human edits the stops (assign / remove / reorder).</p>
 */
@Entity
@Table(name = "routes")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Route extends BaseEntity {

    /** Cross-module FK to {@code users(id)} — must reference a {@code SALES_REP}. */
    @Column(name = "representative_id", nullable = false)
    private Long representativeId;

    /** Cross-module FK to {@code territories(id)}. Every stop must be in this territory. */
    @Column(name = "territory_id", nullable = false)
    private Long territoryId;

    @Column(nullable = false, length = 120)
    private String name;

    /** The business date this schedule is for. ({@code date} is a reserved word, hence the column name.) */
    @Column(name = "route_date", nullable = false)
    private LocalDate routeDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RouteStatus status;

    /** ERD {@code IsOptimized}: were the sequence numbers auto-computed? */
    @Column(name = "is_optimized", nullable = false)
    private boolean optimized;

    /**
     * The ordered stops on this route. {@code mappedBy} = the back-reference field on the
     * assignment side, so the assignment owns the FK column. Cascade-all + orphan-removal
     * binds each stop's lifecycle to the route's.
     */
    @OneToMany(mappedBy = "route", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RouteCustomerAssignment> assignments = new ArrayList<>();

    public Route(Long representativeId, Long territoryId, String name, LocalDate routeDate) {
        this.representativeId = representativeId;
        this.territoryId = territoryId;
        this.name = name;
        this.routeDate = routeDate;
        this.status = RouteStatus.PLANNED;
        this.optimized = false;
    }

    /** Adds a stop and keeps both sides of the relationship consistent. */
    public void addAssignment(RouteCustomerAssignment assignment) {
        assignment.setRoute(this);
        this.assignments.add(assignment);
    }
}
