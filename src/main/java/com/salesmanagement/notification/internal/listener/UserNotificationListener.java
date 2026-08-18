package com.salesmanagement.notification.internal.listener;

import com.salesmanagement.identity.api.UserCreatedEvent;
import com.salesmanagement.notification.internal.enums.NotificationType;
import com.salesmanagement.notification.internal.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Raises a welcome notification when an admin creates a new user account.
 *
 * <p>Consumes {@link UserCreatedEvent}, which {@code identity} already publishes
 * on account creation and which was designed with {@code notification} as its
 * intended consumer. This is the one trigger that needed no new upstream event —
 * it was already there — so honouring it is low-cost and gives every new user a
 * first entry in their feed. {@link ApplicationModuleListener} semantics as
 * elsewhere: async, post-commit, retried, cycle-free.</p>
 *
 * <p>Type is {@link NotificationType#SYSTEM} (an account/administrative message);
 * {@code referenceId} is {@code null} (no deep-link target). Dedup key is
 * {@code "user-created:{userId}"} — one welcome per account even if the event is
 * redelivered.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserNotificationListener {

    private final NotificationService notificationService;

    /** Welcome the newly created user. */
    @ApplicationModuleListener
    void on(UserCreatedEvent event) {
        String sourceRef = "user-created:" + event.userId();
        notificationService.create(
                event.userId(),
                NotificationType.SYSTEM,
                "Welcome",
                "Welcome, " + event.name() + ". Your account is ready.",
                sourceRef,
                null);
    }
}
