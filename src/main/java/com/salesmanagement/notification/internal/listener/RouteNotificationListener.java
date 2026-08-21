package com.salesmanagement.notification.internal.listener;

import com.salesmanagement.notification.internal.enums.NotificationType;
import com.salesmanagement.notification.internal.service.NotificationService;
import com.salesmanagement.routing.api.RouteAssignedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Raises a notification when a route is assigned to a rep (FR-107).
 *
 * <p>Consumes {@link RouteAssignedEvent} (added to {@code routing.api} for this
 * purpose). {@link ApplicationModuleListener} semantics as in the invoice
 * listener: async, post-commit, retried, cycle-free.</p>
 *
 * <p>Recipient is the event's {@code representativeId}; {@code referenceId} is the
 * route id for deep-linking (FR-105). Dedup key is
 * {@code "route-assigned:{routeId}:rep:{repId}"} so a reassignment to the same rep
 * is idempotent while a genuine reassignment to a different rep is a distinct
 * notification.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteNotificationListener {

    private final NotificationService notificationService;

    /** FR-107: notify the rep a route was assigned to them. */
    @ApplicationModuleListener
    void on(RouteAssignedEvent event) {
        String sourceRef = "route-assigned:" + event.routeId() + ":rep:" + event.representativeId();
        notificationService.create(
                event.representativeId(),
                NotificationType.ROUTE,
                "New route assigned",
                "You have been assigned the route \"" + event.routeName() + "\".",
                sourceRef,
                event.routeId());
    }
}
