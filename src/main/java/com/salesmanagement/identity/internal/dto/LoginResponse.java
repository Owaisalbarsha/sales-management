package com.salesmanagement.identity.internal.dto;

import com.salesmanagement.shared.security.UserRole;

/**
 * Outbound payload returned by {@code POST /api/auth/login}
 * and {@code POST /api/auth/refresh}.
 *
 * <p>Contains everything the client needs to bootstrap its session:
 * user identity fields for display purposes and two JWT tokens
 * for request authentication and token renewal.
 *
 * <p><b>Token strategy:</b>
 * <ul>
 *   <li>{@code accessToken}  — short-lived (15 min). Sent in the
 *       {@code Authorization: Bearer} header on every API request.</li>
 *   <li>{@code refreshToken} — long-lived (7 days). Used exclusively
 *       on {@code POST /api/auth/refresh} to obtain a new access token
 *       without re-entering credentials. Must be stored securely on the
 *       client (e.g. Flutter SecureStorage, not SharedPreferences).</li>
 * </ul>
 *
 * <p>{@code role} is included so the mobile app and web dashboard can
 * render the correct UI immediately after login without a second API call.
 */
public record LoginResponse(

        /** Database identifier of the authenticated user. */
        Long userId,

        /** Full display name, ready to render in the UI header. */
        String name,

        /** Email of the authenticated user, useful for display and support. */
        String email,

        /**
         * The role that governs what this user can see and do.
         * The client uses this to decide which screens and actions to expose;
         * the server enforces the same rules independently via {@code @PreAuthorize}.
         */
        UserRole role,

        /**
         * Short-lived JWT for authenticating API requests.
         * Expires in 15 minutes (configurable via {@code app.jwt.access-token-expiry-ms}).
         */
        String accessToken,

        /**
         * Long-lived JWT for renewing the access token.
         * Expires in 7 days (configurable via {@code app.jwt.refresh-token-expiry-ms}).
         * Should never be sent on regular API calls — only to {@code /api/auth/refresh}.
         */
        String refreshToken

) {}