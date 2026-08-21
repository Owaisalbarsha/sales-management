package com.salesmanagement.notification.internal.enums;

/**
 * The four notification categories defined by FR-102 and matched in the ERD's
 * NOTIFICATION.Type column.
 *
 * <p>The category drives client behaviour together with
 * {@code Notification.referenceId}: it selects which screen the client opens
 * when the user taps the notification (FR-105).</p>
 *
 * <ul>
 *   <li>{@link #INVOICE}   — an invoice was approved or rejected (FR-103/104).
 *       {@code referenceId} is the invoice id.</li>
 *   <li>{@link #INVENTORY} — a product dropped below its minimum stock level
 *       (FR-106). {@code referenceId} is the product id.</li>
 *   <li>{@link #ROUTE}     — a route was assigned or its customer list changed
 *       (FR-107). {@code referenceId} is the route id.</li>
 *   <li>{@link #SYSTEM}    — an administrative announcement or system message
 *       (FR-108). {@code referenceId} is {@code null} (no target screen).</li>
 * </ul>
 *
 * <p>Persisted as a string ({@code EnumType.STRING}), matching the project-wide
 * convention (invoice status, route status). Reordering these constants never
 * corrupts stored rows.</p>
 */
public enum NotificationType {
    INVOICE,
    INVENTORY,
    ROUTE,
    SYSTEM
}
