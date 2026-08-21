package com.salesmanagement.identity.internal.dto;

/**
 * Summary counts of users grouped by account status.
 *
 * <p>Returned alongside the paginated user list so the dashboard can render
 * summary cards ("12 Active, 3 Inactive, 1 Suspended") without a separate
 * API call.
 *
 * <p><b>Scope decision:</b> these counts are GLOBAL — they reflect every user
 * in the system, not the currently filtered/searched subset. The dashboard
 * summary is meant to show the overall health of the user base regardless of
 * what the admin is currently filtering the table by. If the frontend instead
 * wants counts scoped to the active filter, that is a small change to
 * {@code UserService.searchUsers} — confirm the intended behaviour with the
 * frontend developer.
 */
public record UserStatusCounts(
        long active,
        long inactive,
        long suspended,
        long total
) {
    public static UserStatusCounts of(long active, long inactive, long suspended) {
        return new UserStatusCounts(active, inactive, suspended,
                active + inactive + suspended);
    }
}