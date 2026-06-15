package com.salesmanagement.inventory.internal.entity;

import com.salesmanagement.inventory.internal.enums.ProductStatus;
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
 * A product: an item in the catalog that can be stocked in the warehouse, loaded
 * onto a sales rep's van, and sold on an invoice.
 *
 * <p>Owned by the {@code inventory} module. Other modules never touch this entity;
 * they read a {@link com.salesmanagement.inventory.api.ProductInfo} projection
 * through {@link com.salesmanagement.inventory.api.InventoryFacade}.</p>
 *
 * <p><strong>Identity &amp; audit:</strong> {@code id}, {@code createdAt}, and
 * {@code updatedAt} come from {@link BaseEntity}. {@code status} defaults to
 * {@link ProductStatus#ACTIVE} on creation.</p>
 *
 * <p><strong>Uniqueness:</strong> {@code sku} is mandatory and unique — it is the
 * primary business identifier of a product. {@code barcode} is optional but unique
 * when present (PostgreSQL allows multiple NULLs under a UNIQUE constraint, so
 * products without a scanned barcode coexist freely). Both are enforced by the
 * V5 migration <em>and</em> pre-checked in {@code ProductService} so callers get a
 * clean 409 instead of a raw integrity violation.</p>
 */
@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA only; use the public constructor in code
public class Product extends BaseEntity {

    @Column(nullable = false, length = 150)
    private String name;

    /** Stock Keeping Unit — the product's primary business identifier. Mandatory, unique. */
    @Column(nullable = false, unique = true, length = 50)
    private String sku;

    /** Optional scannable barcode (FR-29 / barcode scanning). Unique when present. */
    @Column(unique = true, length = 50)
    private String barcode;

    /** Unit price. {@code NUMERIC(12,2)}; never negative (validated + DB CHECK). */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    /** Free-text unit of measure (e.g. "BOX", "BOTTLE", "KG"). Kept as text per the ERD. */
    @Column(name = "unit_of_measure", nullable = false, length = 30)
    private String unitOfMeasure;

    /**
     * Reorder threshold (FR-32). When warehouse stock for this product drops below
     * this level it is surfaced as "low stock". Defaults to 0 (no alert).
     */
    @Column(name = "min_stock_level", nullable = false)
    private int minStockLevel = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ProductStatus status = ProductStatus.ACTIVE;

    /**
     * Creates a new product in the {@link ProductStatus#ACTIVE} state.
     *
     * @param barcode       may be {@code null} for products without a scanned code
     * @param minStockLevel reorder threshold; 0 disables the low-stock alert
     */
    public Product(String name,
                   String sku,
                   String barcode,
                   BigDecimal price,
                   String unitOfMeasure,
                   int minStockLevel) {
        this.name = name;
        this.sku = sku;
        this.barcode = barcode;
        this.price = price;
        this.unitOfMeasure = unitOfMeasure;
        this.minStockLevel = minStockLevel;
        this.status = ProductStatus.ACTIVE;
    }
}
