package com.salesmanagement.notification.internal.controller;

import com.salesmanagement.notification.internal.dto.AnnouncementResult;
import com.salesmanagement.notification.internal.dto.CreateAnnouncementRequest;
import com.salesmanagement.notification.internal.service.NotificationService;
import com.salesmanagement.shared.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin announcements (FR-108, D6). An admin posts an announcement and the system
 * fans it out as one {@code SYSTEM} notification per active user.
 *
 * <p><strong>Authorization:</strong> ADMIN only — this is the sole endpoint in the
 * module that <em>originates</em> notifications rather than reacting to a domain
 * event, so it is gated to the administrative role.</p>
 *
 * <p>Separate controller from {@link NotificationController} because the audience
 * and authorization differ: the feed endpoints are per-caller and open to every
 * role; this one is a system-wide write restricted to ADMIN.</p>
 */
@RestController
@RequestMapping("/api/notifications/announcements")
@RequiredArgsConstructor
public class AnnouncementController {

    private final NotificationService notificationService;

    /**
     * Broadcast an announcement to all active users. Returns the recipient count.
     *
     * <p>Note: until {@code UserFacade.findActiveUserIds()} is added to identity
     * (see the identity edits shipped with this module), the fan-out resolves to
     * an empty audience and this returns {@code recipients = 0}. Wire that facade
     * method to activate delivery.</p>
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<AnnouncementResult> broadcast(
            @Valid @RequestBody CreateAnnouncementRequest request) {
        int recipients = notificationService.broadcastAnnouncement(request.title(), request.message());
        return ApiResponse.created(new AnnouncementResult(recipients));
    }
}
