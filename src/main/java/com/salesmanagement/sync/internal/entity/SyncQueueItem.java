package com.salesmanagement.sync.internal.entity;

import com.salesmanagement.shared.domain.BaseEntity;
import com.salesmanagement.sync.internal.enums.OperationType;
import com.salesmanagement.sync.internal.enums.RecordType;
import com.salesmanagement.sync.internal.enums.SyncStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * One offline record pushed by a device — the server-side row of the sync ledger.
 *
 * <p>Owned by the {@code sync} module. Nothing outside sync sees this entity; the
 * device sees only the batch response DTOs, and no other module imports it (sync
 * exposes no facade — it is an edge module).</p>
 *
 * <p><strong>What this row is (see V14 header).</strong> It is not a work queue the
 * server drains asynchronously; processing is synchronous inside the batch request
 * (Fork A). The row persists for three reasons: the idempotency ledger (FR-99, E3),
 * the {@code clientUuid → serverRecordId} remap (Fork D), and the dead-letter for
 * rejected items (E4).</p>
 *
 * <p><strong>Lifecycle.</strong> Created {@link SyncStatus#PENDING} and settled to
 * {@link SyncStatus#SYNCED} (with {@link #serverRecordId} set for INVOICE/VISIT) or
 * {@link SyncStatus#FAILED} (with {@link #errorCode} set) in the same transaction.
 * The settlement helpers below are the only way to move it, so the terminal-shape
 * invariant (a FAILED row always carries a code; a SYNCED row never does) cannot be
 * violated from the service — the DB {@code chk_sync_queue_items_terminal_shape}
 * constraint is the backstop.</p>
 *
 * <p><strong>References.</strong> {@link #representativeId} is a cross-module link to
 * {@code identity.users(id)} — a plain {@code Long}, never {@code @ManyToOne}, taken
 * from the JWT principal (never the payload). The DB FK is declared in V14.</p>
 */
@Entity
@Table(name = "sync_queue_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SyncQueueItem extends BaseEntity {

    /** Per-record idempotency key from the device (v4 UUID). UNIQUE — the dedup key (FR-99). */
    @Column(name = "client_uuid", nullable = false, length = 36, updatable = false)
    private String clientUuid;

    /** Owning SALES_REP, from the JWT principal. Cross-module FK to identity.users(id). */
    @Column(name = "representative_id", nullable = false, updatable = false)
    private Long representativeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "record_type", nullable = false, length = 20, updatable = false)
    private RecordType recordType;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 20, updatable = false)
    private OperationType operationType;

    /** Self-contained JSON of the offline record. Mapped to jsonb; opaque to sync. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false, length = 20)
    private SyncStatus syncStatus;

    /** How many times the DEVICE re-sent this still-unsettled item (Fork G). */
    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    /** Device capture instant (ERD CreatedAt). Offline lag = createdAt - clientCreatedAt. */
    @Column(name = "client_created_at", nullable = false, updatable = false)
    private Instant clientCreatedAt;

    /** Id of the created target record (invoice/visit); null for GPS or unprocessed. Backs the remap (Fork D). */
    @Column(name = "server_record_id")
    private Long serverRecordId;

    /** Terminal-failure code returned to the device and shown in the dead-letter (E4). Null unless FAILED. */
    @Column(name = "error_code", length = 64)
    private String errorCode;

    /** Human-readable failure detail for audit. Null unless FAILED. */
    @Column(name = "error_detail", columnDefinition = "TEXT")
    private String errorDetail;

    /**
     * Receives a freshly-pushed item in {@link SyncStatus#PENDING}. {@code retryCount}
     * carries the device's own resend count for this item (Fork G); the server does
     * not compute it.
     */
    public SyncQueueItem(String clientUuid,
                         Long representativeId,
                         RecordType recordType,
                         OperationType operationType,
                         String payload,
                         Instant clientCreatedAt,
                         int retryCount) {
        this.clientUuid       = clientUuid;
        this.representativeId  = representativeId;
        this.recordType        = recordType;
        this.operationType     = operationType;
        this.payload           = payload;
        this.clientCreatedAt   = clientCreatedAt;
        this.retryCount        = retryCount;
        this.syncStatus        = SyncStatus.PENDING;
    }

    /**
     * Settles the item as {@link SyncStatus#SYNCED}, recording the created target id
     * (null for GPS, which has no addressable row). Clears any prior error.
     */
    public void markSynced(Long serverRecordId) {
        this.syncStatus     = SyncStatus.SYNCED;
        this.serverRecordId = serverRecordId;
        this.errorCode      = null;
        this.errorDetail    = null;
    }

    /**
     * Settles the item as {@link SyncStatus#FAILED} (E4) with a machine-readable
     * code and a human-readable detail. The code is what the device reads and the
     * dead-letter surfaces.
     *
     * @throws IllegalArgumentException if {@code errorCode} is blank — a FAILED row
     *         must always carry a reason (mirrors the DB terminal-shape constraint)
     */
    public void markFailed(String errorCode, String errorDetail) {
        if (errorCode == null || errorCode.isBlank()) {
            throw new IllegalArgumentException("A FAILED sync item must carry an error code");
        }
        this.syncStatus     = SyncStatus.FAILED;
        this.serverRecordId = null;
        this.errorCode      = errorCode;
        this.errorDetail    = errorDetail;
    }

    /** True once the item has been processed successfully — the idempotency short-circuit for E3. */
    public boolean isSynced() {
        return this.syncStatus == SyncStatus.SYNCED;
    }

    /**
     * Refreshes the device resend counter when the same item is pushed again before it
     * settled (Fork G). The count is the device's, not the server's — the server only
     * records the latest value it was told.
     */
    public void refreshRetryCount(int deviceRetryCount) {
        this.retryCount = deviceRetryCount;
    }
}
