package com.salesmanagement.notification.internal.dto;

/**
 * Result of an announcement broadcast (FR-108): how many users received it.
 *
 * @param recipients the number of active users the announcement was delivered to
 */
public record AnnouncementResult(int recipients) {}
