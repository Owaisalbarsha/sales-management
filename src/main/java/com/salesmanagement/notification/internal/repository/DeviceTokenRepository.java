package com.salesmanagement.notification.internal.repository;

import com.salesmanagement.notification.internal.entity.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for {@link DeviceToken}. Supports the registration upsert
 * (lookup by the globally-unique token), the send path (all tokens for a user),
 * and the cleanup paths (delete on logout, delete on FCM {@code UNREGISTERED}).
 */
public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {

    /** Upsert key: find the row for an FCM token, if registered. */
    Optional<DeviceToken> findByToken(String token);

    /** Send path: every device token currently registered for a user. */
    List<DeviceToken> findByUserId(Long userId);

    /** Cleanup: drop a dead token reported {@code UNREGISTERED} by FCM. */
    void deleteByToken(String token);

    /** Cleanup on logout: drop all of a user's device tokens. */
    void deleteByUserId(Long userId);
}
