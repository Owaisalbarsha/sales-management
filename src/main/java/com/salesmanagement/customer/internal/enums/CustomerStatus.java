package com.salesmanagement.customer.internal.enums;

/**
 * Lifecycle status of a {@code Customer}.
 *
 * <p>Modelled as an enum (persisted as a {@code String} via
 * {@code @Enumerated(EnumType.STRING)}) rather than a free-text column, matching
 * the system-wide convention for fixed value domains (see {@code UserRole},
 * {@code UserStatus}). This gives the Vue dashboard and Flutter client a fixed
 * contract and lets Jackson reject unknown values with a clean 400.</p>
 *
 * <p>Only two states are required by the specification. A deactivated customer is
 * the basis of the offline-sync rule "reject invoices for disabled customers"
 * (SRS FR-95): {@code invoicing} consults {@code CustomerFacade.isActive(id)}
 * before committing an invoice that arrived from a mobile device.</p>
 *
 * <p>This type is intentionally <strong>internal</strong> — it is never exposed
 * across a module boundary. {@code CustomerInfo} exposes a derived
 * {@code active} boolean instead, mirroring how {@code identity} keeps
 * {@code UserStatus} internal.</p>
 */
public enum CustomerStatus {

    /** The customer is operational: may be routed, visited, and invoiced. */
    ACTIVE,

    /**
     * The customer is disabled. They are retained for historical integrity
     * (existing invoices/visits still reference them) but new invoices are
     * rejected (FR-95). The standard way to "remove" a customer that already
     * has history — a hard delete would be blocked by foreign keys.
     */
    INACTIVE
}