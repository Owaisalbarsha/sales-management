package com.salesmanagement.sync.internal.repository;

import com.salesmanagement.sync.internal.entity.SyncQueueItem;
import com.salesmanagement.sync.internal.enums.RecordType;
import com.salesmanagement.sync.internal.enums.SyncStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Persistence for the sync ledger. Owned by {@code sync}; never exposed outward.
 *
 * <p>The queries here serve the three jobs of the ledger: idempotency lookup
 * (FR-99/E3), the {@code clientUuid → serverRecordId} remap (Fork D), and
 * dead-letter listing (E4).</p>
 */
public interface SyncQueueItemRepository extends JpaRepository<SyncQueueItem, Long> {

    /**
     * The idempotency lookup (FR-99, E3). A batch that re-sends an item is
     * recognised by its {@code clientUuid}; if the existing row is SYNCED the
     * service returns success without reprocessing.
     */
    Optional<SyncQueueItem> findByClientUuid(String clientUuid);

    /**
     * The remap lookup (Fork D). An offline invoice references its offline visit
     * by that visit's {@code clientUuid}; once the VISIT item is SYNCED, this
     * resolves it to the new server visit id via {@code server_record_id}.
     *
     * <p>Scoped by {@code recordType} and {@code representativeId} so an invoice
     * can only ever bind to a visit pushed by the same rep — defence against a
     * crafted payload pointing at another rep's visit uuid.</p>
     */
    Optional<SyncQueueItem> findByClientUuidAndRecordTypeAndRepresentativeId(
            String clientUuid, RecordType recordType, Long representativeId);

    /** Dead-letter review (E4): a rep's terminally-failed items, for reconciliation. */
    java.util.List<SyncQueueItem> findByRepresentativeIdAndSyncStatus(
            Long representativeId, SyncStatus syncStatus);
}
