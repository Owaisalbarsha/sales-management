package com.salesmanagement.identity.internal.dto;

import com.salesmanagement.identity.internal.entity.User;
import com.salesmanagement.identity.internal.entity.UserStatus;
import com.salesmanagement.shared.security.UserRole;

import java.time.Instant;

/**
 * Outbound representation of a {@link User} for API consumers.
 *
 * <p>Used by {@code GET /api/users}, {@code GET /api/users/{id}},
 * and the response body of {@code POST /api/users}.
 *
 * <p>Deliberately omits {@code passwordHash} — it must never leave the server.
 * Also omits {@code updatedAt} as it carries no business value for the
 * admin screens that consume this response.
 *
 * <p>The static factory {@link #from(User)} is the only construction path.
 * This keeps the mapping logic in one place and prevents callers from
 * accidentally constructing a partial response.
 *
 * <p>Note: this DTO is internal to the identity module. Other modules that
 * need user data receive {@link com.salesmanagement.identity.api.UserInfo} via
 * {@link com.salesmanagement.identity.api.UserFacade} — never this class.
 */
public record UserResponse(

        /** Stable database identifier. */
        Long id,

        /** Full display name. */
        String name,

        /** Login email — also the unique business identifier. */
        String email,

        /** Assigned role governing system access and permissions. */
        UserRole role,

        /**
         * Current account lifecycle status.
         * Included so the admin UI can render active/inactive/suspended
         * indicators without a separate status endpoint.
         */
        UserStatus status,

        /**
         * UTC timestamp of account creation.
         * Carried from {@link com.salesmanagement.shared.domain.BaseEntity#getCreatedAt()}.
         * Serialized as ISO-8601 by Jackson's default {@code Instant} serializer.
         */
        Instant createdAt

) {
    /**
     * Maps a {@link User} entity to its API representation.
     * Call this method — never call the record constructor directly.
     *
     * @param user the entity loaded from the database; must not be {@code null}
     * @return a fully populated {@code UserResponse}
     */
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole(),
                user.getStatus(),
                user.getCreatedAt()
        );
    }
}