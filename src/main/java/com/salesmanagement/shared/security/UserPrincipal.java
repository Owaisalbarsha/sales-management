package com.salesmanagement.shared.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * The authenticated user object stored in Spring Security's SecurityContext.
 *
 * Every controller and service that needs to know "who is calling this" retrieves
 * a UserPrincipal — either via SecurityUtils.getCurrentUser() or by injecting
 * it directly with @AuthenticationPrincipal UserPrincipal principal.
 *
 * What it carries:
 * - userId  : the USER.UserID PK — the identifier modules use to look up ownership
 * - email   : used as the Spring Security "username" (unique, never null)
 * - role    : exactly one UserRole — the system has no multi-role users
 * - status  : USER.Status — ACTIVE | INACTIVE | SUSPENDED
 *             The filter checks this on every request; SUSPENDED users are
 *             immediately rejected even with a valid token.
 *
 * Implements UserDetails so Spring Security can:
 * - evaluate @PreAuthorize("hasRole('...')") via getAuthorities()
 * - check isEnabled() / isAccountNonLocked() automatically in the filter chain
 *
 * This class is immutable by design. JwtAuthFilter constructs one per request
 * from the validated JWT claims — no setters, no mutable state.
 */
@Getter
public final class UserPrincipal implements UserDetails {

    private final Long     userId;
    private final String   email;
    private final UserRole role;
    private final String   status;   // ACTIVE | INACTIVE | SUSPENDED

    public UserPrincipal(Long userId, String email, UserRole role, String status) {
        this.userId = userId;
        this.email  = email;
        this.role   = role;
        this.status = status;
    }

    // ── UserDetails contract ─────────────────────────────────────────────────

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // Single authority matching the user's role.
        // SimpleGrantedAuthority stores "ROLE_SALES_REP" etc.,
        // which Spring's hasRole("SALES_REP") evaluates correctly.
        return List.of(new SimpleGrantedAuthority(role.toGrantedAuthority()));
    }

    /**
     * Password is never carried in the principal — the token is the credential.
     * Returning null is correct here; Spring Security does not use this after
     * the JWT filter has already authenticated the request.
     */
    @Override
    public String getPassword() {
        return null;
    }

    /**
     * Spring Security uses "username" as the unique identifier.
     * We use email — it's the natural login identifier in this system.
     */
    @Override
    public String getUsername() {
        return email;
    }

    /**
     * Account is non-expired as long as the JWT hasn't expired.
     * Token expiry is enforced in JwtAuthFilter before this object is created.
     */
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    /**
     * SUSPENDED users have their token accepted by the JWT validator
     * (signature and expiry are still valid) but the principal reports
     * the account as locked — Spring Security rejects the request with 403.
     */
    @Override
    public boolean isAccountNonLocked() {
        return !"SUSPENDED".equalsIgnoreCase(status);
    }

    /**
     * Credentials (JWT) expiry is handled by JwtAuthFilter.
     * This always returns true — no additional credential expiry check needed.
     */
    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /**
     * INACTIVE users cannot authenticate. If a user's status changes to INACTIVE
     * while they have a valid token, this check rejects them on the next request.
     */
    @Override
    public boolean isEnabled() {
        return "ACTIVE".equalsIgnoreCase(status);
    }

    // ── Convenience ──────────────────────────────────────────────────────────

    public boolean hasRole(UserRole expectedRole) {
        return this.role == expectedRole;
    }

    public boolean isAdmin()            { return role == UserRole.ADMIN; }
    public boolean isSalesManager()     { return role == UserRole.SALES_MANAGER; }
    public boolean isSalesRep()         { return role == UserRole.SALES_REP; }
    public boolean isWarehouseManager() { return role == UserRole.WAREHOUSE_MANAGER; }
}