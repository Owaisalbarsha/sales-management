package com.salesmanagement.identity.internal.dto;

import com.salesmanagement.identity.internal.service.UserService;
import com.salesmanagement.shared.security.UserRole;
import jakarta.validation.constraints.*;

/**
 * Inbound payload for {@code POST /api/users} — ADMIN only (BR-7).
 *
 * <p>User accounts are created exclusively by an ADMIN; self-registration
 * is intentionally not supported. This matches the SRS requirement that
 * the system is a closed, company-internal tool with controlled access.
 *
 * <p>The raw {@code password} supplied here is encoded by
 * {@code PasswordEncoder} inside {@link UserService# create}
 * before persisting. It is never stored or logged in plain text.
 *
 * <p>Email is stored lowercased to guarantee case-insensitive uniqueness
 * (enforced at the application layer; the database unique constraint is
 * case-sensitive by default in PostgreSQL).
 */
public record CreateUserRequest(

        /**
         * Full name of the new user, used for display across the system.
         * Other modules receive this via {@link com.salesmanagement.identity.api.UserInfo#name()}.
         */
        @NotBlank(message = "Name is required")
        @Size(min = 2, max = 255, message = "Name must be between 2 and 255 characters")
        String name,

        @NotBlank(message = "Phone number is required")
        @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "Must be a valid phone number")
        String phoneNumber,

        /**
         * Initial password chosen by the ADMIN for the new account.
         * The user should be instructed to change it on first login
         * (future enhancement — not in current SRS scope).
         */
        @NotBlank(message = "Password is required")
        @Size(min = 8, message = "Password must be at least 8 characters")
        String password,

        /**
         * Role assigned to this user at account creation.
         * Roles are fixed at design time (ADMIN | SALES_MANAGER | SALES_REP | WAREHOUSE_MANAGER)
         * and cannot be changed after creation in the current version.
         * Role changes require deactivating the account and creating a new one.
         */
        @NotNull(message = "Role is required")
        UserRole role

) {}