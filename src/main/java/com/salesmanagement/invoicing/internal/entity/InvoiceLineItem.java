package com.salesmanagement.invoicing.internal.entity;

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

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * One line on an {@link Invoice}: "this product, this quantity, at this captured unit price,
 * with this fixed discount."
 *
 * <p><strong>Two kinds of reference (project rule):</strong></p>
 * <ul>
 *   <li>{@code invoice} — within-module {@code @ManyToOne}, the allowed exception.</li>
 *   <li>{@code productId} — cross-module link to {@code inventory.products(id)}. Plain
 *       {@code Long}, no {@code @ManyToOne}; the DB FK is declared in the V10 migration.</li>
 * </ul>
 *
 * <p><strong>Money (D9):</strong> {@code price} is captured from inventory and re-read on every
 * DRAFT edit, frozen at submit (BR-9). {@code discount} is a single line-level fixed amount
 * (D7). {@code subtotal} is always {@code quantity * price - discount}, recomputed by
 * {@link #recompute()} whenever quantity, price, or discount changes — never trusted from the
 * client. All values are {@code NUMERIC(12,2)} with {@code HALF_UP} rounding.</p>
 *
 * <p>{@code UNIQUE(invoice_id, product_id)} keeps each product to a single line (D11).</p>
 */
@Entity
@Table(
        name = "invoice_line_items",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_invoice_line_items_invoice_product",
                columnNames = {"invoice_id", "product_id"})
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InvoiceLineItem extends BaseEntity {

    /** Money scale for all invoice amounts — 2 decimal places, matching NUMERIC(12,2). */
    private static final int MONEY_SCALE = 2;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    /** Cross-module FK to {@code inventory.products(id)}. */
    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    /** Unit price captured from inventory; re-read on each DRAFT edit, frozen at submit (BR-9). */
    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    /** Line-level fixed-amount discount (0 .. price*quantity). */
    @Column(name = "discount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discount;

    /** Server-computed quantity*price - discount. Never set from the client. */
    @Column(name = "subtotal", nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    /**
     * Creates a line with a captured price and a discount, computing the subtotal immediately.
     *
     * @param productId cross-module product id
     * @param quantity  units sold (must be {@code > 0})
     * @param price     captured unit price (must be {@code >= 0})
     * @param discount  fixed line discount (must be {@code >= 0} and {@code <= price*quantity});
     *                  {@code null} is treated as zero
     */
    public InvoiceLineItem(Long productId, int quantity, BigDecimal price, BigDecimal discount) {
        this.productId = productId;
        this.quantity  = quantity;
        this.price     = scaled(price);
        this.discount  = scaled(discount == null ? BigDecimal.ZERO : discount);
        recompute();
    }

    /**
     * Recomputes {@link #subtotal} from the current quantity, price, and discount using
     * {@code HALF_UP} at 2 dp. Call after any mutation of those fields. The caller is
     * responsible for having validated {@code discount <= price*quantity} (the service does
     * this before saving); this method does not clamp, so an over-discount would surface as a
     * negative subtotal and be rejected by the DB CHECK.
     */
    public void recompute() {
        BigDecimal gross = price.multiply(BigDecimal.valueOf(quantity));
        this.subtotal = scaled(gross.subtract(discount));
    }

    /** The gross (pre-discount) line amount, {@code quantity * price}. */
    public BigDecimal gross() {
        return scaled(price.multiply(BigDecimal.valueOf(quantity)));
    }

    private static BigDecimal scaled(BigDecimal v) {
        return v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
