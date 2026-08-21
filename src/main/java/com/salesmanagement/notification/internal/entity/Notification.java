package com.salesmanagement.notification.internal.entity;

import com.salesmanagement.notification.internal.enums.NotificationType;
import com.salesmanagement.notification.internal.enums.ReadStatus;
import com.salesmanagement.shared.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A single stored notification in a user's feed.
 *
 * <p>Owned by the {@code notification} module. This is a leaf aggregate: no
 * other module references it, and it holds only plain {@code Long} ids
 * ({@code userId}, {@code referenceId}) as cross-module links — never a JPA
 * association to another module's entity, per the project rule.</p>
 *
 * <p><strong>Lifecycle.</strong> Created {@link ReadStatus#UNREAD} by an event
 * listener or the announcement endpoint; flips once to {@link ReadStatus#READ}
 * via {@link #markRead()}. There is no un-read transition and no other mutable
 * state — the row is otherwise immutable once written.</p>
 *
 * <p><strong>Fields.</strong></p>
 * <ul>
 *   <li>{@code userId} — recipient; FK to {@code identity.users(id)}, any role.</li>
 *   <li>{@code type} — FR-102 category (drives the client's target screen).</li>
 *   <li>{@code title} / {@code message} — feed heading and body.</li>
 *   <li>{@code readStatus} — FR-111 read state (owned here, D4).</li>
 *   <li>{@code sourceRef} — FR-114 idempotency key; {@code null} for
 *       announcements (see {@code V13} for why they are exempt).</li>
 *   <li>{@code referenceId} — FR-105 deep-link target id; {@code null} for
 *       {@link NotificationType#SYSTEM}.</li>
 * </ul>
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends BaseEntity {

    /** Recipient. Cross-module FK to {@code identity.users(id)}; may be any role. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** FR-102 category. Persisted as a string. */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private NotificationType type;

    /** Short heading shown in the feed list. */
    @Column(name = "title", nullable = false, length = 150)
    private String title;

    /** Body text; {@code TEXT} in the DB (a rejection reason may be long, FR-104). */
    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    /** FR-111 read state. Owned solely by this module (D4). */
    @Enumerated(EnumType.STRING)
    @Column(name = "read_status", nullable = false, length = 10)
    private ReadStatus readStatus;

    /**
     * FR-114 idempotency key, deterministic per triggering event
     * (e.g. {@code "invoice:42:APPROVED"}). {@code null} for announcements,
     * which are intentionally not deduplicated. Partial-unique in the DB.
     */
    @Column(name = "source_ref", length = 120)
    private String sourceRef;

    /**
     * FR-105 deep-link target id (invoice / route / product id) matching
     * {@link #type}. {@code null} for {@link NotificationType#SYSTEM}.
     */
    @Column(name = "reference_id")
    private Long referenceId;

    /**
     * Creates an UNREAD notification.
     *
     * @param userId      recipient (from the triggering event or announcement target)
     * @param type        FR-102 category
     * @param title       feed heading
     * @param message     feed body
     * @param sourceRef   FR-114 idempotency key, or {@code null} for announcements
     * @param referenceId FR-105 deep-link target id, or {@code null} for SYSTEM
     */
    public Notification(Long userId, NotificationType type, String title, String message,
                        String sourceRef, Long referenceId) {
        this.userId      = userId;
        this.type        = type;
        this.title       = title;
        this.message     = message;
        this.sourceRef   = sourceRef;
        this.referenceId = referenceId;
        this.readStatus  = ReadStatus.UNREAD;
    }

    /**
     * Marks this notification read (FR-111). Idempotent: calling it on an
     * already-read notification is a no-op, so a duplicate client tap does not
     * churn {@code updated_at} through the caller's explicit {@code save()}.
     *
     * @return {@code true} if the state actually changed (was UNREAD)
     */
    public boolean markRead() {
        if (this.readStatus == ReadStatus.READ) {
            return false;
        }
        this.readStatus = ReadStatus.READ;
        return true;
    }

    /** Whether this notification is currently unread. */
    public boolean isUnread() {
        return this.readStatus == ReadStatus.UNREAD;
    }
}
