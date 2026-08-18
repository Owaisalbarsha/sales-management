package com.salesmanagement.notification.internal.enums;

/**
 * The read state of a notification (FR-111), matching the ERD's
 * NOTIFICATION.ReadStatus column.
 *
 * <p>Owned solely by the notification module (D4). A notification is created
 * {@link #UNREAD} and flips to {@link #READ} when the client opens it
 * ({@code PATCH /api/notifications/{id}/read}) or marks the whole feed read
 * ({@code PATCH /api/notifications/read-all}). Listing the feed never changes
 * it, so the unread badge stays meaningful.</p>
 *
 * <p>Persisted as a string ({@code EnumType.STRING}).</p>
 */
public enum ReadStatus {
    UNREAD,
    READ
}
