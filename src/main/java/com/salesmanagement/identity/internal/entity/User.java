package com.salesmanagement.identity.internal.entity;

import com.salesmanagement.identity.internal.service.UserService;
import com.salesmanagement.shared.domain.BaseEntity;
import com.salesmanagement.shared.security.UserRole;
import jakarta.persistence.*;
import lombok.*;

/**
 * Persistent representation of a system user.
 *
 * <p>This entity is internal to the {@code identity} module. No other module
 * may import or reference it directly. Cross-module access is provided
 * exclusively through {@link com.salesmanagement.identity.api.UserFacade}.
 *
 * <p>Role is stored as a {@link UserRole} enum (shared kernel) because
 * roles are fixed at design time and do not require a dynamic ROLE table.
 * Status lifecycle is managed by {@link UserStatus}.
 *
 * <p>Password is never stored in plain text. The field holds a BCrypt hash
 * produced by {@code PasswordEncoder} in {@link UserService}. It is excluded
 * from {@code toString()} and {@code equals/hashCode} deliberately.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true, exclude = "passwordHash")
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class User extends BaseEntity {

    // ─── Identity ────────────────────────────────────────────────────────────

    @EqualsAndHashCode.Include
    @Column(nullable = false)
    private String name;

    @EqualsAndHashCode.Include
    @Column(nullable = false, unique = true)
    private String phoneNumber;

    /**
     * BCrypt hash of the user's password.
     * Never exposed via DTO, never logged, never included in equality checks.
     */
    @Column(nullable = false)
    private String passwordHash;

    // ─── Authorization ───────────────────────────────────────────────────────

    /**
     * Fixed role assigned at account creation.
     * Values: ADMIN | SALES_MANAGER | SALES_REP | WAREHOUSE_MANAGER.
     * Stored as VARCHAR — ordinal is never used.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private UserRole role;

    /**
     * Account lifecycle status.
     * Only {@link UserStatus#ACTIVE} accounts may authenticate.
     *
     * @see UserStatus#allowsLogin()
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    // ─── Factory constructor ──────────────────────────────────────────────────

    /**
     * Creates a new User with {@link UserStatus#ACTIVE} by default.
     * Callers supply a pre-hashed password — raw passwords must never
     * reach this constructor.
     *
     * @param name         full display name
     * @param phoneNumber;        unique login email (lowercased by caller)
     * @param passwordHash BCrypt hash produced by {@code PasswordEncoder}
     * @param role         assigned role from the fixed role set
     */
    public User(String name, String phoneNumber, String passwordHash, UserRole role) {
        this.name         = name;
        this.phoneNumber  = phoneNumber;
        this.passwordHash = passwordHash;
        this.role         = role;
        this.status       = UserStatus.ACTIVE;
    }

    // ─── Domain behaviour ────────────────────────────────────────────────────

    /**
     * Returns {@code true} if this account is permitted to authenticate.
     * Delegates to {@link UserStatus#allowsLogin()} — callers never
     * compare status literals inline.
     */
    public boolean canLogin() {
        return status.allowsLogin();
    }

    /**
     * Activates the account. Valid from INACTIVE or SUSPENDED.
     */
    public void activate() {
        this.status = UserStatus.ACTIVE;
    }

    /**
     * Deactivates the account permanently (e.g. rep has left the company).
     * Historical data — visits, invoices, GPS logs — is preserved.
     */
    public void deactivate() {
        this.status = UserStatus.INACTIVE;
    }

    /**
     * Suspends the account temporarily (e.g. policy violation).
     * Can be reversed by calling {@link #activate()}.
     */
    public void suspend() {
        this.status = UserStatus.SUSPENDED;
    }
}