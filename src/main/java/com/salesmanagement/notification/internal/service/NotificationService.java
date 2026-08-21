package com.salesmanagement.notification.internal.service;

import com.salesmanagement.identity.api.UserFacade;
import com.salesmanagement.notification.internal.channel.NotificationChannel;
import com.salesmanagement.notification.internal.channel.NotificationChannel.NotificationPayload;
import com.salesmanagement.notification.internal.dto.NotificationResponse;
import com.salesmanagement.notification.internal.entity.DeviceToken;
import com.salesmanagement.notification.internal.entity.Notification;
import com.salesmanagement.notification.internal.enums.NotificationType;
import com.salesmanagement.notification.internal.enums.ReadStatus;
import com.salesmanagement.notification.internal.repository.DeviceTokenRepository;
import com.salesmanagement.notification.internal.repository.NotificationRepository;
import com.salesmanagement.shared.api.PageResponse;
import com.salesmanagement.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Business logic for the notification module.
 *
 * <p><strong>Write side (internal).</strong> {@link #create} is the single entry
 * point for raising a notification; the event listeners and the announcement
 * fan-out both funnel through it. It enforces FR-114 dedup on {@code sourceRef}
 * and then dispatches the persisted notification to every {@link
 * NotificationChannel} (in-app always; FCM when enabled).</p>
 *
 * <p><strong>Read side (client-facing).</strong> {@link #list},
 * {@link #unreadCount}, {@link #markRead}, {@link #markAllRead} back the REST
 * endpoints. Every one is scoped to a {@code userId} the controller takes from
 * the JWT principal, so a caller can only ever see or change their own feed.</p>
 *
 * <p><strong>Device tokens (D2).</strong> {@link #registerDeviceToken} upserts on
 * the token string; {@link #deleteDeviceTokensForUser} and
 * {@link #deleteDeviceToken} are the logout / stale-token cleanup paths.</p>
 *
 * <p>No published facade: nothing outside this module calls this service (D8).
 * It is invoked only by this module's own listeners and controllers.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final UserFacade userFacade;
    /** All delivery channels discovered by Spring. InAppChannel is always present; FcmChannel only when enabled. */
    private final List<NotificationChannel> channels;

    // ═══════════════════════════════════════════════════════════════════════
    //  WRITE — raise a notification (internal; used by listeners + announcements)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Raises one notification: persists it (UNREAD), then dispatches it to every
     * channel. The single write path for the whole module.
     *
     * <p><strong>FR-114 dedup.</strong> When {@code sourceRef} is non-null and a
     * row with that key already exists, this is a redelivery of the same event
     * (Spring Modulith retries failed listeners) — it is skipped and no channel
     * fires. A pre-check via {@code existsBySourceRef} handles the common case;
     * the partial-unique index is the hard backstop, and a concurrent-retry race
     * that slips past the pre-check surfaces as a {@link
     * DataIntegrityViolationException}, which is caught and treated as a
     * successful no-op (mirroring the tracking module's ingest dedup).</p>
     *
     * @param userId      recipient
     * @param type        FR-102 category
     * @param title       heading
     * @param message     body
     * @param sourceRef   FR-114 idempotency key, or {@code null} to skip dedup (announcements)
     * @param referenceId FR-105 deep-link target id, or {@code null}
     * @return the persisted notification, or {@link Optional#empty()} if it was a
     *         deduplicated redelivery (nothing written, nothing dispatched)
     */
    @Transactional
    public Optional<Notification> create(Long userId, NotificationType type, String title,
                                         String message, String sourceRef, Long referenceId) {
        if (sourceRef != null && notificationRepository.existsBySourceRef(sourceRef)) {
            log.debug("Dedup: notification sourceRef={} already exists; skipping.", sourceRef);
            return Optional.empty();
        }

        Notification notification = new Notification(userId, type, title, message, sourceRef, referenceId);
        Notification saved;
        try {
            saved = notificationRepository.save(notification);
        } catch (DataIntegrityViolationException e) {
            // Concurrent redelivery won the race between the pre-check and the insert.
            // The other delivery persisted and dispatched; this one is a no-op.
            log.debug("Dedup (race): notification sourceRef={} inserted concurrently; skipping.", sourceRef);
            return Optional.empty();
        }

        log.info("Raised notification id={} userId={} type={} referenceId={} sourceRef={}",
                saved.getId(), userId, type, referenceId, sourceRef);

        dispatch(saved);
        return Optional.of(saved);
    }

    /** Fan the persisted notification out to every channel. Channels never throw (their contract). */
    private void dispatch(Notification n) {
        NotificationPayload payload = new NotificationPayload(
                n.getUserId(), n.getType(), n.getTitle(), n.getMessage(), n.getReferenceId());
        for (NotificationChannel channel : channels) {
            channel.deliver(payload);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  WRITE — FR-108 admin announcement fan-out (D6)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Fans an administrative announcement out to every active user as an
     * individual {@link NotificationType#SYSTEM} notification (D6: N rows, one per
     * user, matching the per-user read model).
     *
     * <p>Not deduplicated ({@code sourceRef} is null): an admin may legitimately
     * post the same text twice. {@code referenceId} is null (no deep-link target).
     * The recipient set comes from {@code identity}; see {@link
     * #activeRecipientIds()} for the sourcing decision.</p>
     *
     * @param title   announcement heading
     * @param message announcement body
     * @return the number of users the announcement was delivered to
     */
    @Transactional
    public int broadcastAnnouncement(String title, String message) {
        List<Long> recipients = activeRecipientIds();
        for (Long userId : recipients) {
            create(userId, NotificationType.SYSTEM, title, message, null, null);
        }
        log.info("Broadcast announcement to {} users: '{}'", recipients.size(), title);
        return recipients.size();
    }

    /**
     * The announcement audience (FR-108). Sourced from {@code identity} via
     * {@link UserFacade}.
     *
     * <p><strong>Note — pending a facade method.</strong> {@code UserFacade} does
     * not yet expose a "list active user ids" query, and this module must not
     * reach into identity's tables. The clean fix is a small read method on
     * {@code UserFacade} (e.g. {@code findActiveUserIds()}), added when this
     * endpoint is turned on. Until then this returns an empty list and logs, so
     * the module compiles and the rest of it is exercisable; the announcement
     * endpoint is the one piece gated on that facade addition. This is called out
     * in the batch notes rather than hidden.</p>
     */
    private List<Long> activeRecipientIds() {
        // TODO(identity): add UserFacade.findActiveUserIds() and return it here.
        log.warn("Announcement fan-out is a no-op until UserFacade.findActiveUserIds() exists.");
        return List.of();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  READ — client-facing feed
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * One page of the caller's feed, newest first. Optionally filtered to unread.
     *
     * @param userId     the caller (from the principal)
     * @param unreadOnly when true, return only UNREAD notifications
     * @param pageable   page + size; ordering is applied by the controller
     */
    public PageResponse<NotificationResponse> list(Long userId, boolean unreadOnly, Pageable pageable) {
        Page<Notification> page = unreadOnly
                ? notificationRepository.findByUserIdAndReadStatus(userId, ReadStatus.UNREAD, pageable)
                : notificationRepository.findByUserId(userId, pageable);
        return PageResponse.of(page.map(NotificationResponse::from));
    }

    /** FR-111 badge count: the caller's unread total. */
    public long unreadCount(Long userId) {
        return notificationRepository.countByUserIdAndReadStatus(userId, ReadStatus.UNREAD);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  WRITE — FR-111 read transitions (D4)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Marks one of the caller's notifications read (FR-111). Load-mutate-{@code
     * save()}, per the project's explicit-save rule. Idempotent: re-reading an
     * already-read notification returns it unchanged.
     *
     * @param id       the notification to mark read
     * @param callerId the authenticated user (must own it)
     * @return the updated notification
     * @throws BusinessException 404 if no such notification; 403 if it is not the caller's
     */
    @Transactional
    public NotificationResponse markRead(Long id, Long callerId) {
        Notification n = notificationRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound(
                        "Notification not found: " + id, "NOTIFICATION_NOT_FOUND"));
        requireOwner(n, callerId);

        if (n.markRead()) {
            notificationRepository.save(n);   // explicit save (dirty checking not relied upon)
        }
        return NotificationResponse.from(n);
    }

    /**
     * Marks all of the caller's unread notifications read in one statement
     * ({@code PATCH /read-all}).
     *
     * <p>A bulk {@code @Modifying} UPDATE, a deliberate exception to the
     * per-entity explicit-save rule: clearing a full feed one row at a time would
     * be N loads + N writes. Because the bulk update bypasses the persistence
     * context and {@code @PreUpdate}, {@code updated_at} is stamped explicitly in
     * the query.</p>
     *
     * @param callerId the authenticated user
     * @return how many notifications were flipped to READ
     */
    @Transactional
    public int markAllRead(Long callerId) {
        int updated = notificationRepository.markAllReadForUser(callerId, Instant.now());
        log.info("Marked {} notifications read for userId={}", updated, callerId);
        return updated;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  DEVICE TOKENS (D2) — registration upsert + cleanup
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Registers (or refreshes) an FCM token for the caller's device. Upsert on the
     * globally-unique token string:
     * <ul>
     *   <li>token unknown → insert {@code (callerId, token)};</li>
     *   <li>token known, same owner → touch it (bumps {@code updated_at} via the
     *       caller's explicit save, a liveness signal);</li>
     *   <li>token known, different owner → re-point it at the caller (a device
     *       handed to another user keeps one row instead of colliding on the
     *       unique token).</li>
     * </ul>
     *
     * <p>Called by the client after login and from Firebase's {@code
     * onTokenRefresh} (tokens rotate mid-session, so login alone is insufficient).</p>
     *
     * @param callerId the device owner (from the principal)
     * @param token    the FCM registration token
     */
    @Transactional
    public void registerDeviceToken(Long callerId, String token) {
        Optional<DeviceToken> existing = deviceTokenRepository.findByToken(token);
        if (existing.isPresent()) {
            DeviceToken dt = existing.get();
            if (!dt.getUserId().equals(callerId)) {
                dt.reassignTo(callerId);
            }
            deviceTokenRepository.save(dt);   // explicit save touches updated_at
            log.debug("Refreshed device token for userId={}", callerId);
        } else {
            deviceTokenRepository.save(new DeviceToken(callerId, token));
            log.info("Registered new device token for userId={}", callerId);
        }
    }

    /**
     * Deletes all of a user's device tokens (logout cleanup, D2). Idempotent.
     *
     * @param userId the user logging out
     */
    @Transactional
    public void deleteDeviceTokensForUser(Long userId) {
        deviceTokenRepository.deleteByUserId(userId);
        log.debug("Deleted all device tokens for userId={}", userId);
    }

    /**
     * Deletes one dead token (stale-token cleanup, D2). Idempotent — deleting an
     * already-absent token is a no-op.
     *
     * @param token the FCM token to remove
     */
    @Transactional
    public void deleteDeviceToken(String token) {
        deviceTokenRepository.deleteByToken(token);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Guards
    // ═══════════════════════════════════════════════════════════════════════

    private void requireOwner(Notification n, Long callerId) {
        if (!n.getUserId().equals(callerId)) {
            throw BusinessException.forbidden(
                    "Notification " + n.getId() + " does not belong to you", "NOTIFICATION_NOT_OWNED");
        }
    }
}
