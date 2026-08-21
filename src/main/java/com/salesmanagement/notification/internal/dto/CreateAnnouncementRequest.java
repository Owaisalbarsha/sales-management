package com.salesmanagement.notification.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /api/notifications/announcements} — an admin broadcasting
 * an announcement to all active users (FR-108, D6). ADMIN-only at the controller.
 *
 * @param title   announcement heading (maps to notification.title, bounded)
 * @param message announcement body (maps to notification.message)
 */
public record CreateAnnouncementRequest(
        @NotBlank(message = "title is required")
        @Size(max = 150, message = "title must not exceed 150 characters")
        String title,

        @NotBlank(message = "message is required")
        String message
) {}
