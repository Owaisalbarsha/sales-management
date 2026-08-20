package com.salesmanagement.identity.api;

import com.salesmanagement.shared.security.UserRole;

/**
 * Immutable public projection of a User, safe to pass across module boundaries.
 * Never expose the User entity or UserResponse DTO outside identity.
 */
public record UserInfo(
        Long     id,
        String   name,
        String phoneNumber,
        UserRole role
) {}