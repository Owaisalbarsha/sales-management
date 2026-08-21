package com.salesmanagement.notification.internal.repository;

import com.salesmanagement.notification.internal.entity.Notification;
import com.salesmanagement.notification.internal.enums.ReadStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * Persistence for {@link Notification}. Serves the client-facing feed reads and
 * the read-state transitions; both scope every query by {@code userId} so a
 * caller can only ever see or mutate their own notifications (the controller
 * supplies the id from the JWT principal).
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** One page of a user's feed, caller-supplied ordering (newest-first at the controller). */
    Page<Notification> findByUserId(Long userId, Pageable pageable);

    /** One page of a user's feed filtered by read state (e.g. unread-only). */
    Page<Notification> findByUserIdAndReadStatus(Long userId, ReadStatus readStatus, Pageable pageable);

    /** FR-111 badge: how many of a user's notifications are unread. */
    long countByUserIdAndReadStatus(Long userId, ReadStatus readStatus);

    /** True if a notification with this idempotency key already exists (FR-114 fast-path check). */
    boolean existsBySourceRef(String sourceRef);

    /**
     * Marks every UNREAD notification of a user READ in one statement
     * ({@code PATCH /read-all}). Bypasses the entity's {@code @PreUpdate}, so
     * {@code updated_at} is set explicitly here to keep the audit column honest.
     *
     * @param userId the owner whose feed is being cleared
     * @param now    the timestamp to stamp on the touched rows
     * @return the number of rows flipped
     */
    @Modifying
    @Query("""
           update Notification n
              set n.readStatus = com.salesmanagement.notification.internal.enums.ReadStatus.READ,
                  n.updatedAt  = :now
            where n.userId = :userId
              and n.readStatus = com.salesmanagement.notification.internal.enums.ReadStatus.UNREAD
           """)
    int markAllReadForUser(@Param("userId") Long userId, @Param("now") Instant now);
}
