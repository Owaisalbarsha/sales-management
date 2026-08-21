package com.salesmanagement.territory.internal.entity;

import com.salesmanagement.shared.domain.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A sales territory — a geographic area that customers are grouped into.
 *
 * <p>This is reference data: it is created once by an administrator and changes
 * rarely. It owns no business logic and publishes no events. The {@code customer}
 * module references a territory by its ID (via the {@code CUSTOMER.territory_id}
 * foreign key); no other module holds a JPA reference to this entity.</p>
 *
 * <p>Schema is owned by Flyway (see {@code V3__create_territories_table.sql}).
 * Following the project convention, this entity carries no redundant column
 * definitions — only the mappings JPA cannot infer (the unique constraint is
 * enforced by the database, not re-declared here).</p>
 *
 * <p>Audit fields ({@code id}, {@code createdAt}, {@code updatedAt}) are inherited
 * from {@link BaseEntity}.</p>
 */
@Entity
@Table(name = "territories")
@Getter
@Setter
@NoArgsConstructor
public class Territory extends BaseEntity {

    /**
     * Human-readable name of the territory (e.g. "North Region", "Coastal Zone").
     * Unique across the system — enforced by the {@code uq_territories_name}
     * database constraint. Required.
     */
    private String name;

    /**
     * Optional free-text description of the territory: boundary notes, coverage
     * details, or any administrative annotation. May be {@code null}.
     */
    private String description;

    /**
     * Convenience constructor for creating a new territory from validated input.
     * The {@code id} and audit timestamps are assigned by JPA/{@link BaseEntity},
     * not here.
     *
     * @param name        the unique territory name
     * @param description optional description; may be {@code null}
     */
    public Territory(String name, String description) {
        this.name = name;
        this.description = description;
    }
}