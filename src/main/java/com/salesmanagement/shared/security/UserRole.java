package com.salesmanagement.shared.security;

/**
 * The four fixed roles of the system, defined in SRS section 3.1.
 *
 * Why an enum and not a ROLE table:
 * - The SRS defines exactly 4 roles that will not change dynamically.
 * - A DB-driven role table adds runtime complexity (cache invalidation,
 *   join on every auth check) for zero functional benefit.
 * - Spring Security's hasRole() / hasAnyRole() works natively with String/enum.
 * - If roles ever need to be dynamic, migrate the enum to a table at that point.
 *   YAGNI applies here.
 *
 * Naming convention: Spring Security prefixes roles with "ROLE_" internally.
 * By storing "SALES_REP" (no prefix) in the JWT and in the DB, and letting
 * Spring Security handle the prefix, we avoid double-prefixing bugs.
 *
 * Usage in @PreAuthorize:
 *   @PreAuthorize("hasRole('SALES_REP')")      <- Spring adds ROLE_ prefix
 *   @PreAuthorize("hasRole('SALES_MANAGER')")
 *   @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
 *
 * Why here in shared/security and not in identity/api:
 *   UserPrincipal and JwtAuthFilter (both in shared) carry/read UserRole.
 *   shared must never import from a module. Modules import from shared.
 */
public enum UserRole {

    /**
     * Full system access. Manages users, territories, system config.
     * Maps to BR-7 in the SRS.
     */
    ADMIN,

    /**
     * Manages the sales team. Approves/rejects invoices (BR-2).
     * Views real-time GPS tracking map (FR-63).
     * Assigns routes to sales reps.
     */
    SALES_MANAGER,

    /**
     * Field sales representative. Uses the mobile app.
     * Creates invoices, visits customers, requests restocks (BR-5).
     * Only role that may own VAN_INVENTORY_ITEM.
     */
    SALES_REP,

    /**
     * Manages warehouse stock. Approves/rejects restock requests (BR-5).
     * Receives low-stock alerts (FR-32).
     */
    WAREHOUSE_MANAGER;

    /**
     * Returns the Spring Security authority string for this role.
     * Spring Security's hasRole() strips the "ROLE_" prefix when matching,
     * so the GrantedAuthority must carry it.
     */
    public String toGrantedAuthority() {
        return "ROLE_" + this.name();
    }
}