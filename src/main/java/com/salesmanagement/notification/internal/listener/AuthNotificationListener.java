package com.salesmanagement.notification.internal.listener;

import com.salesmanagement.identity.api.UserLoggedOutEvent;
import com.salesmanagement.notification.internal.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Deletes a user's FCM device tokens when they log out (D2).
 *
 * <p>Consumes {@link UserLoggedOutEvent}, published by {@code identity} on logout.
 * Doing this via an event — rather than having {@code identity} call
 * {@code notification} directly — keeps the dependency one-directional
 * ({@code notification} → {@code identity}); a direct call would add an
 * {@code identity} → {@code notification} edge and create the cycle
 * {@code ApplicationModules.verify()} rejects. Same rationale as the routing
 * events.</p>
 *
 * <p>A logged-out device should stop receiving pushes immediately; this is the
 * proactive counterpart to {@code FcmChannel}'s lazy prune of tokens that FCM
 * reports {@code UNREGISTERED}. {@link ApplicationModuleListener} semantics:
 * async, post-commit, retried, backed by the event-publication log.</p>
 *
 * <p><strong>Requires</strong> the {@code UserLoggedOutEvent} record and the
 * publish call in identity's {@code AuthController} — see
 * {@code _identity_edits/README_logout_cleanup.txt}. If those are not applied,
 * remove this listener.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthNotificationListener {

    private final NotificationService notificationService;

    /** Drop the user's device tokens on logout. */
    @ApplicationModuleListener
    void on(UserLoggedOutEvent event) {
        notificationService.deleteDeviceTokensForUser(event.userId());
        log.debug("Cleared device tokens on logout for userId={}", event.userId());
    }
}
