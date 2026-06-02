package com.salesmanagement.identity.api;

import com.salesmanagement.shared.security.UserRole;

/**
 * Published by identity when an ADMIN creates a new user account.
 * Consumers (e.g. notification) listen with @ApplicationModuleListener.
 */
public record UserCreatedEvent(
        Long     userId,
        String   name,
        String   email,
        UserRole role
) {}