package com.salesmanagement.invoicing.internal.entity;

import com.salesmanagement.invoicing.internal.enums.EpodArtifactType;
import com.salesmanagement.shared.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One Electronic Proof of Delivery artifact attached to an {@link Invoice} (decision D16):
 * a customer signature or a delivery photo, captured on the rep's device at submit.
 *
 * <p><strong>Reference:</strong> {@code invoice} is a within-module {@code @ManyToOne} (the
 * allowed exception). There are no cross-module references on this entity.</p>
 *
 * <p><strong>What each row holds:</strong> a {@code type}, a {@code url} pointing at the stored
 * file on disk (the bytes never live in the DB), a {@code hash} that binds the file to the
 * invoice's identity data so it cannot be swapped or reused elsewhere, the {@code latitude}/
 * {@code longitude} where it was captured, and the {@code capturedAt} UTC instant. Artifacts
 * are captured and frozen at submit and never mutated afterward.</p>
 *
 * <p>{@code UNIQUE(invoice_id, type)} means each artifact type appears at most once per
 * invoice.</p>
 */
@Entity
@Table(
        name = "epod_artifacts",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_epod_artifacts_invoice_type",
                columnNames = {"invoice_id", "type"})
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EpodArtifact extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private EpodArtifactType type;

    /** Pointer to the stored file (write-once path). The row never stores the bytes. */
    @Column(name = "url", nullable = false, columnDefinition = "TEXT")
    private String url;

    /** SHA-256 hex of (invoice identity data + file bytes). Server-computed at submit. */
    @Column(name = "hash", nullable = false, length = 64)
    private String hash;

    /** GPS latitude where the artifact was captured; may be {@code null}. */
    @Column(name = "latitude", precision = 9, scale = 6)
    private BigDecimal latitude;

    /** GPS longitude where the artifact was captured; may be {@code null}. */
    @Column(name = "longitude", precision = 9, scale = 6)
    private BigDecimal longitude;

    /** UTC instant the artifact was captured (client sends ISO 8601 with offset on sync). */
    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    public EpodArtifact(EpodArtifactType type,
                        String url,
                        String hash,
                        BigDecimal latitude,
                        BigDecimal longitude,
                        Instant capturedAt) {
        this.type       = type;
        this.url        = url;
        this.hash       = hash;
        this.latitude   = latitude;
        this.longitude  = longitude;
        this.capturedAt = capturedAt;
    }
}
