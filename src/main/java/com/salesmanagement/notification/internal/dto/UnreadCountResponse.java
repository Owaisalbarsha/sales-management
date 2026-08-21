package com.salesmanagement.notification.internal.dto;

/**
 * Result of {@code GET /api/notifications/unread-count} — the caller's unread
 * total for the FR-111 badge.
 *
 * @param unread number of unread notifications for the caller
 */
public record UnreadCountResponse(long unread) {}
