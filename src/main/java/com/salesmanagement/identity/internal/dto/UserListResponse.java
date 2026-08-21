package com.salesmanagement.identity.internal.dto;

import com.salesmanagement.shared.api.PageResponse;

/**
 * Combined response for the user management screen.
 *
 * <p>Bundles two things the dashboard needs together in a single API call:
 * <ul>
 *   <li>{@code users} — the paginated, filtered, searched list of users.</li>
 *   <li>{@code counts} — global summary counts by status for the summary cards.</li>
 * </ul>
 *
 * <p>Returned by {@code GET /api/users}. The pagination, search term, role
 * filter, and status filter all apply to {@code users}; {@code counts} is
 * always global (see {@link UserStatusCounts}).
 */
public record UserListResponse(
        PageResponse<UserResponse> users,
        UserStatusCounts           counts
) {}