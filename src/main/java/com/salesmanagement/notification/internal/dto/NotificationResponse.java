package com.salesmanagement.notification.internal.dto;

import com.salesmanagement.notification.internal.entity.Notification;
import com.salesmanagement.notification.internal.enums.NotificationType;
import com.salesmanagement.notification.internal.enums.ReadStatus;

import java.time.Instant;

/**
 * Client-facing projection of a {@link Notification} — what the feed endpoints
 * return. Never exposes the entity directly.
 *
 * <p>{@code sourceRef} is intentionally omitted: it is an internal dedup key
 * whose format may change, and the client must not depend on it. The client
 * deep-links (FR-105) from {@link #type} + {@link #referenceId} instead.</p>
 *
 * @param id          notification id (used by the mark-read endpoint)
 * @param type        FR-102 category — selects the client's target screen
 * @param title       heading
 * @param message     body
 * @param readStatus  UNREAD | READ
 * @param referenceId FR-105 deep-link target id, or {@code null} for SYSTEM
 * @param createdAt   when the notification was raised (the feed's sort key)
 */
public record NotificationResponse(
        Long             id,
        NotificationType type,
        String           title,
        String           message,
        ReadStatus       readStatus,
        Long             referenceId,
        Instant          createdAt
) {
    /** Maps an entity to its response projection. */
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(
                n.getId(),
                n.getType(),
                n.getTitle(),
                n.getMessage(),
                n.getReadStatus(),
                n.getReferenceId(),
                n.getCreatedAt());
    }
}
