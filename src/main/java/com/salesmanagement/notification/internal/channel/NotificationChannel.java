package com.salesmanagement.notification.internal.channel;

import com.salesmanagement.notification.internal.enums.NotificationType;

/**
 * The delivery seam (D2). A channel takes a notification that has already been
 * persisted to the feed and attempts to deliver it out-of-band (e.g. an FCM
 * push to the user's devices).
 *
 * <p><strong>Why a seam.</strong> The persisted feed (the {@code notifications}
 * table, surfaced over REST) is the system of record and is written
 * unconditionally by {@code NotificationService}. Out-of-band delivery is a
 * best-effort side effect layered on top. Modelling it as a list of
 * {@link NotificationChannel} beans means adding, say, an email channel later is
 * a new class, not a change to the service — and it keeps the FCM SDK dependency
 * isolated to one implementation.</p>
 *
 * <p><strong>Contract.</strong> Implementations must be non-throwing: a delivery
 * failure is logged and swallowed, never propagated. Delivery runs after the
 * feed row is committed (the service invokes channels from the same
 * already-committed event-listener transaction), so a channel failure can never
 * roll back or lose the stored notification. If every channel fails, the user
 * still sees the notification when their client next reads the feed.</p>
 */
public interface NotificationChannel {

    /**
     * Attempt to deliver an already-persisted notification. Must not throw.
     *
     * @param payload the notification to deliver
     */
    void deliver(NotificationPayload payload);

    /**
     * A transport-neutral view of a persisted notification, handed to each
     * channel. Deliberately not the JPA entity — channels are transport code and
     * have no business touching the aggregate.
     *
     * @param recipientUserId the user to deliver to
     * @param type            FR-102 category (an FCM data key, so the client can route)
     * @param title           notification heading
     * @param message         notification body
     * @param referenceId     FR-105 deep-link target id, or {@code null}
     */
    record NotificationPayload(
            Long recipientUserId,
            NotificationType type,
            String title,
            String message,
            Long referenceId
    ) {}
}
