package com.salesmanagement.notification.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /api/notifications/device-tokens} — the client registering
 * its FCM token (D2). The owner is taken from the JWT principal, never from the
 * body, so only the token itself is sent.
 *
 * @param token the FCM registration token
 */
public record RegisterDeviceTokenRequest(
        @NotBlank(message = "token is required")
        @Size(max = 512, message = "token must not exceed 512 characters")
        String token
) {}
