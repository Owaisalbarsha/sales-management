package com.salesmanagement.notification.internal.entity;

import com.salesmanagement.shared.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * An FCM registration token for one device install (D2, FR-100/101).
 *
 * <p>Owned by the {@code notification} module. Not part of the canonical ERD —
 * a deliberate extension to support push delivery (documented in {@code V13}).
 * Holds only a plain {@code Long} {@code userId} as a cross-module link to
 * {@code identity.users(id)}.</p>
 *
 * <p><strong>Why one row per device, many per user.</strong> FCM mints a token
 * per device install, independently of the app's login/session lifecycle, and
 * the token rotates over time. Storing tokens in a table (rather than a column
 * on the user) lets a user have several devices and lets dead tokens be removed
 * individually. See
 * <a href="https://firebase.google.com/docs/cloud-messaging/manage-tokens">FCM:
 * manage tokens</a>.</p>
 *
 * <p><strong>Lifecycle.</strong> Registered via
 * {@code POST /api/notifications/device-tokens} after login and on Firebase's
 * {@code onTokenRefresh}; deleted on logout and whenever an FCM send reports the
 * token {@code UNREGISTERED}. The token string is globally unique (the upsert
 * key); re-registering an existing token only touches {@code updated_at}.</p>
 */
@Entity
@Table(name = "device_tokens")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeviceToken extends BaseEntity {

    /** Owner. Cross-module FK to {@code identity.users(id)}. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** The FCM registration token; globally unique per device install (the upsert key). */
    @Column(name = "token", nullable = false, length = 512)
    private String token;

    /**
     * Registers a token for a user.
     *
     * @param userId the device owner (from the JWT principal)
     * @param token  the FCM registration token
     */
    public DeviceToken(Long userId, String token) {
        this.userId = userId;
        this.token  = token;
    }

    /**
     * Re-points this token row at a (possibly different) owner and refreshes it.
     * Used by the upsert path when a token already exists but the current
     * principal differs — a device handed to another user keeps a single row
     * rather than colliding on the unique token. Mutation is persisted by the
     * caller's explicit {@code save()} (dirty checking is not relied upon in
     * this project).
     *
     * @param userId the current owner from the JWT principal
     */
    public void reassignTo(Long userId) {
        this.userId = userId;
    }
}
