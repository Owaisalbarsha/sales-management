package com.salesmanagement.identity.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Inbound payload for {@code POST /api/auth/login}.
 *
 * <p>Validation is intentionally minimal here — we do not reveal whether
 * the phone number exists or the password is wrong beyond a generic "Invalid credentials"
 * message. This prevents user enumeration attacks (if we said "phone number not found",
 * an attacker learns which phone numbers are registered).
 *
 * <p>Fields are {@code String} (not {@code char[]}) because Spring's
 * {@code HttpMessageConverter} produces Strings. The security gain of
 * {@code char[]} is marginal in a JVM heap context anyway.
 */
public record LoginRequest(


        @NotBlank(message = "Phone number is required")
        @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "Must be a valid phone number")
        String phoneNumber,

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