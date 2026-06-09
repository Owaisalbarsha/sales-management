package com.salesmanagement.customer.internal;

import com.salesmanagement.shared.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * A customer: an operational outlet that belongs to exactly one territory and is
 * visited and invoiced by sales representatives.
 *
 * <p>Owned by the {@code customer} module. Other modules never touch this entity;
 * they read a {@code CustomerInfo} projection through {@code CustomerFacade}.</p>
 *
 * <p><strong>Cross-module reference rule:</strong> the link to a territory is held
 * as a plain {@code territoryId} (a {@code Long} column), <em>not</em> a JPA
 * {@code @ManyToOne Territory}. The foreign key is enforced at the database level
 * by the customer migration; the object graph stays inside module boundaries.
 * This is the project-wide rule for inter-module relationships — IDs cross
 * boundaries, object references do not.</p>
 *
 * <p>Audit columns ({@code id}, {@code createdAt}, {@code updatedAt}) come from
 * {@link BaseEntity}. {@code status} defaults to {@link CustomerStatus#ACTIVE} on
 * creation.</p>
 */
@Entity
@Table(name = "customers")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA only; use the public constructor in code
public class Customer extends BaseEntity {

    /**
     * The owning territory's id. FK to {@code territories(id)} enforced by the
     * database (declared in this module's migration, since the dependent module
     * owns the constraint). Mandatory — every customer belongs to one territory.
     */
    @Column(name = "territory_id", nullable = false)
    private Long territoryId;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 500)
    private String address;

    @Column(length = 30)
    private String phone;

    /** Customer location latitude. Range −90..90; precision matches {@code NUMERIC(9,6)}. */
    @Column(precision = 9, scale = 6)
    private BigDecimal latitude;

    /** Customer location longitude. Range −180..180; precision matches {@code NUMERIC(9,6)}. */
    @Column(precision = 9, scale = 6)
    private BigDecimal longitude;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CustomerCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CustomerStatus status = CustomerStatus.ACTIVE;

    /**
     * Creates a new customer in the {@link CustomerStatus#ACTIVE} state.
     * Coordinates may be {@code null} when the outlet is registered from the
     * office before its GPS position is captured on the first visit.
     */
    public Customer(Long territoryId,
                    String name,
                    String address,
                    String phone,
                    BigDecimal latitude,
                    BigDecimal longitude,
                    CustomerCategory category) {
        this.territoryId = territoryId;
        this.name = name;
        this.address = address;
        this.phone = phone;
        this.latitude = latitude;
        this.longitude = longitude;
        this.category = category;
        this.status = CustomerStatus.ACTIVE;
    }
}