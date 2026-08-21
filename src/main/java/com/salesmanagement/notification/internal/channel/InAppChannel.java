package com.salesmanagement.notification.internal.channel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The always-on in-app channel (FR-109/110).
 *
 * <p>Its "delivery" is a no-op by design: the notification has already been
 * written to the {@code notifications} table by the time any channel is invoked,
 * and the client obtains in-app notifications by reading that feed over REST
 * (list + unread-count). There is nothing to push for the in-app case — the row
 * <em>is</em> the delivery. This class exists so the in-app path is represented
 * as a first-class {@link NotificationChannel} alongside FCM, and so the seam
 * has at least one implementation that is always present and never fails
 * (unlike {@code FcmChannel}, which is config-gated and may be absent).</p>
 *
 * <p>Live push to an <em>open</em> app (a badge updating without a manual
 * refresh) would be an additive concern here — e.g. an SSE broadcaster, mirroring
 * {@code /api/tracking/live}. It is intentionally not built in v1: the client
 * polling the feed on foreground is sufficient, and FCM covers the
 * app-backgrounded/closed case (FR-101).</p>
 */
@Slf4j
@Component
public class InAppChannel implements NotificationChannel {

    @Override
    public void deliver(NotificationPayload payload) {
        // The feed row already exists; the client reads it over REST. Nothing to
        // send. Logged at trace only — this fires for every notification.
        log.trace("In-app notification available in feed: userId={} type={} referenceId={}",
                payload.recipientUserId(), payload.type(), payload.referenceId());
    }
}
