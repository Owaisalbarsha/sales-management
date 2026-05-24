package com.salesmanagement.shared.domain;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Base superclass for every @Entity in every module.
 *
 * Design decisions:
 * - Instant (UTC) instead of LocalDateTime — avoids timezone ambiguity across
 *   server deployments. Store once in UTC, interpret at display time in the client.
 * - GenerationType.IDENTITY — straightforward for PostgreSQL BIGSERIAL. Avoids
 *   the overhead of a shared sequence table (TABLE strategy) and is simpler to
 *   reason about than SEQUENCE across module boundaries.
 * - @PrePersist / @PreUpdate instead of @EnableJpaAuditing — keeps shared
 *   self-contained with zero Spring context dependency. No @CreatedDate /
 *   @LastModifiedDate annotations needed, which would require AuditingEntityListener
 *   wiring on every entity. Simple is better here.
 * - Column(updatable = false) on createdAt — once written it never changes.
 *   The DB NEVER lies about when a record was born.
 */
@Getter
@Setter
@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false, updatable = false)
    private Long id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}