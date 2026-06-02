package com.salesmanagement.identity.internal.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound payload for {@code POST /api/auth/login}.
 *
 * <p>Validation is intentionally minimal here — we do not reveal whether
 * the email exists or the password is wrong beyond a generic "Invalid credentials"
 * message. This prevents user enumeration attacks (if we said "email not found",
 * an attacker learns which emails are registered).
 *
 * <p>Fields are {@code String} (not {@code char[]}) because Spring's
 * {@code HttpMessageConverter} produces Strings. The security gain of
 * {@code char[]} is marginal in a JVM heap context anyway.
 */
public record LoginRequest(

        /**
         * The user's registered email address, used as the login identifier.
         * Lowercased before lookup in {@link com.salesmanagement.identity.internal.UserService}
         * to ensure case-insensitive matching regardless of how the user types it.
         */
        @NotBlank(message = "Email is required")
        @Email(message = "Must be a valid email address")
        String email,

        /**
         * The user's raw password, matched against the stored BCrypt hash.
         * Never logged, never stored, discarded immediately after authentication.
         * Minimum length is enforced here to fail fast before hitting the
         * BCrypt computation (which is intentionally expensive).
         */
        @NotBlank(message = "Password is required")
        @Size(min = 8, message = "Password must be at least 8 characters")
        String password

) {}