package com.salesmanagement.identity.api;

import java.time.Instant;

/**
 * Domain event: a user logged out ({@code POST /api/auth/logout}).
 *
 * <p>Consumed by the {@code notification} module to delete that user's FCM device
 * tokens, so a logged-out device stops receiving pushes (D2). Declared here in
 * {@code identity.api} so identity owns it and no {@code identity → notification}
 * dependency edge is created — {@code notification} listens to this event, which
 * keeps the module dependency one-directional and cycle-free (the same rule the
 * routing and invoicing events follow).</p>
 *
 * @param userId the user who logged out
 * @param at     when logout occurred (server UTC instant)
 */
public record UserLoggedOutEvent(
        Long    userId,
        Instant at
) {}
