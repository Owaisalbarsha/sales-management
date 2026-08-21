package com.salesmanagement.notification.internal.channel;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import com.salesmanagement.notification.internal.entity.DeviceToken;
import com.salesmanagement.notification.internal.repository.DeviceTokenRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * FCM push channel (FR-100/101): pushes an already-persisted notification to every
 * device token registered for the recipient, including when their app is
 * backgrounded or closed.
 *
 * <p><strong>Config-gated (D2).</strong> This bean and its companion
 * {@link FirebaseConfig} are created only when {@code notification.fcm.enabled=true}.
 * With the property absent or false — the default for local dev, CI, the seeder,
 * and any environment without a Firebase project — neither bean exists, the seam
 * falls back to {@link InAppChannel} alone, and the app starts and runs identically.
 * The persisted feed is unaffected either way.</p>
 *
 * <p><strong>Non-throwing and self-healing.</strong> Per the channel contract, a
 * send failure is logged and swallowed; the stored feed row is the source of truth
 * and is unaffected. When FCM reports a token {@link MessagingErrorCode#UNREGISTERED}
 * (the app was uninstalled or the token rotated), that row is deleted so the table
 * self-heals, per
 * <a href="https://firebase.google.com/docs/cloud-messaging/manage-tokens">FCM's
 * token-management guidance</a>.</p>
 *
 * <p><strong>Payload.</strong> Each message carries a visible notification (title +
 * body) plus a data map with {@code type} and {@code referenceId}, which the Flutter
 * client uses to deep-link to the right screen (FR-105).</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "notification.fcm.enabled", havingValue = "true")
public class FcmChannel implements NotificationChannel {

    private final DeviceTokenRepository deviceTokenRepository;

    public FcmChannel(DeviceTokenRepository deviceTokenRepository) {
        this.deviceTokenRepository = deviceTokenRepository;
    }

    @Override
    public void deliver(NotificationPayload payload) {
        List<DeviceToken> tokens = deviceTokenRepository.findByUserId(payload.recipientUserId());
        if (tokens.isEmpty()) {
            log.debug("No device tokens for userId={}; nothing to push (feed row still stored).",
                    payload.recipientUserId());
            return;
        }

        for (DeviceToken deviceToken : tokens) {
            sendToToken(deviceToken.getToken(), payload);
        }
    }

    /**
     * Sends one message to one token. Never throws (channel contract): a transient
     * failure is logged; an {@link MessagingErrorCode#UNREGISTERED} /
     * {@link MessagingErrorCode#INVALID_ARGUMENT} result prunes the dead token.
     */
    private void sendToToken(String token, NotificationPayload payload) {
        Message message = Message.builder()
                .setToken(token)
                .setNotification(Notification.builder()
                        .setTitle(payload.title())
                        .setBody(payload.message())
                        .build())
                .putData("type", payload.type().name())
                .putData("referenceId",
                        payload.referenceId() == null ? "" : payload.referenceId().toString())
                .build();

        try {
            String messageId = FirebaseMessaging.getInstance().send(message);
            log.debug("FCM push sent to token(prefix)={} messageId={}", tokenPrefix(token), messageId);

        } catch (FirebaseMessagingException e) {
            MessagingErrorCode code = e.getMessagingErrorCode();
            if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
                // Dead or malformed token — prune so the table self-heals.
                deviceTokenRepository.deleteByToken(token);
                log.info("Pruned dead FCM token(prefix)={} ({})", tokenPrefix(token), code);
            } else {
                // Transient / server-side — log and move on. Not retried here; the
                // feed row already exists and the client will pull it regardless.
                log.warn("FCM push failed for token(prefix)={}: code={} msg={}",
                        tokenPrefix(token), code, e.getMessage());
            }
        } catch (RuntimeException e) {
            // Contract: never propagate out of a channel.
            log.warn("Unexpected FCM error for token(prefix)={}: {}", tokenPrefix(token), e.toString());
        }
    }

    /** First few chars only — never log a full FCM token. */
    private static String tokenPrefix(String token) {
        return token == null ? "null" : token.substring(0, Math.min(8, token.length())) + "…";
    }
}
