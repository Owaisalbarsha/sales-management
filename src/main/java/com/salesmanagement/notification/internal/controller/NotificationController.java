package com.salesmanagement.notification.internal.controller;

import com.salesmanagement.notification.internal.dto.NotificationResponse;
import com.salesmanagement.notification.internal.dto.RegisterDeviceTokenRequest;
import com.salesmanagement.notification.internal.dto.UnreadCountResponse;
import com.salesmanagement.notification.internal.service.NotificationService;
import com.salesmanagement.shared.api.ApiResponse;
import com.salesmanagement.shared.api.PageResponse;
import com.salesmanagement.shared.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The client-facing notification API. Every endpoint operates on the caller's own
 * notifications: the {@code userId} comes from the authenticated principal, never
 * from the request, so a user can only ever read or change their own feed.
 *
 * <p><strong>Authorization.</strong> Open to any authenticated role — every user
 * type has a feed (a rep gets invoice/route notifications; managers get low-stock;
 * everyone gets announcements and a welcome). {@code hasAnyRole(...all four...)}
 * expresses "authenticated" while staying explicit, consistent with the project's
 * controller-level {@code @PreAuthorize} rule (there is no per-method ownership
 * concern beyond the principal scoping, which the service enforces).</p>
 *
 * <p>The feed is returned newest-first: this controller builds the {@link Pageable}
 * with a fixed {@code created_at DESC} sort rather than accepting an arbitrary sort
 * from the client, because a notification feed has exactly one sensible order.</p>
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    /** Any authenticated user. All four roles have a notification feed. */
    private static final String ANY_AUTHENTICATED =
            "hasAnyRole('ADMIN', 'SALES_MANAGER', 'SALES_REP', 'WAREHOUSE_MANAGER')";

    /** Hard cap on page size, matching shared PageRequest's ceiling. */
    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationService notificationService;

    /**
     * The caller's feed, newest first. Optional {@code unreadOnly=true} filters to
     * unread. Paged via {@code page} and {@code size} (size capped at 100).
     */
    @GetMapping
    @PreAuthorize(ANY_AUTHENTICATED)
    public ApiResponse<PageResponse<NotificationResponse>> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        return ApiResponse.ok(notificationService.list(principal.getUserId(), unreadOnly, pageable));
    }

    /** The caller's unread total, for the FR-111 badge. */
    @GetMapping("/unread-count")
    @PreAuthorize(ANY_AUTHENTICATED)
    public ApiResponse<UnreadCountResponse> unreadCount(
            @AuthenticationPrincipal UserPrincipal principal) {
        long unread = notificationService.unreadCount(principal.getUserId());
        return ApiResponse.ok(new UnreadCountResponse(unread));
    }

    /** FR-111: mark one of the caller's notifications read. 403 if it is not theirs. */
    @PatchMapping("/{id}/read")
    @PreAuthorize(ANY_AUTHENTICATED)
    public ApiResponse<NotificationResponse> markRead(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        return ApiResponse.ok(notificationService.markRead(id, principal.getUserId()));
    }

    /** Mark all of the caller's unread notifications read. Returns how many changed. */
    @PatchMapping("/read-all")
    @PreAuthorize(ANY_AUTHENTICATED)
    public ApiResponse<Integer> markAllRead(
            @AuthenticationPrincipal UserPrincipal principal) {
        int updated = notificationService.markAllRead(principal.getUserId());
        return ApiResponse.ok(updated, "Marked " + updated + " notifications read");
    }

    /**
     * D2: register (or refresh) the caller's FCM device token. Called by the client
     * after login and from Firebase's {@code onTokenRefresh}. Idempotent upsert on
     * the token string; returns no body.
     */
    @PostMapping("/device-tokens")
    @PreAuthorize(ANY_AUTHENTICATED)
    public ApiResponse<Void> registerDeviceToken(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody RegisterDeviceTokenRequest request) {
        notificationService.registerDeviceToken(principal.getUserId(), request.token());
        return ApiResponse.noContent("Device token registered");
    }
}
