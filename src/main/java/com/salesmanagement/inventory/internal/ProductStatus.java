package com.salesmanagement.inventory.internal;

/**
 * Lifecycle status of a {@code Product}.
 *
 * <p>Modelled as an enum (persisted as a {@code String} via
 * {@code @Enumerated(EnumType.STRING)}) rather than a free-text column, matching
 * the system-wide convention for fixed value domains (see {@code UserRole},
 * {@code CustomerStatus}). This gives the Vue dashboard and Flutter client a
 * fixed contract and lets Jackson reject unknown values with a clean 400.</p>
 *
 * <p>Two states are required. {@code DISCONTINUED} is the product analogue of
 * {@code CustomerStatus.INACTIVE}: a product that is no longer sold but is
 * retained for historical integrity — existing invoice line items, van rows, and
 * restock items still reference it, so a hard delete would be blocked by foreign
 * keys. {@code invoicing} consults {@code InventoryFacade.isProductActive(id)}
 * before accepting a line for a discontinued product.</p>
 *
 * <p>This type is intentionally <strong>internal</strong> — it is never exposed
 * across a module boundary. {@code ProductInfo} exposes a derived {@code active}
 * boolean instead, mirroring how {@code customer} keeps {@code CustomerStatus}
 * internal.</p>
 */
public enum ProductStatus {

    /** The product is sellable: may be invoiced, stocked, and loaded onto a van. */
    ACTIVE,

    /**
     * The product is no longer sold. Retained for historical integrity (existing
     * invoices/van/restock rows still reference it) but excluded from new sales.
     * The standard way to "remove" a product that already has history.
     */
    DISCONTINUED
}
