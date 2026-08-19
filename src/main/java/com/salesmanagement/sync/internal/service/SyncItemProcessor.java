package com.salesmanagement.sync.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanagement.invoicing.api.InvoiceFacade;
import com.salesmanagement.invoicing.api.OfflineInvoiceInput;
import com.salesmanagement.sync.internal.dto.SyncItemRequest;
import com.salesmanagement.sync.internal.dto.payload.InvoicePayload;
import com.salesmanagement.sync.internal.dto.payload.VisitPayload;
import com.salesmanagement.sync.internal.entity.SyncQueueItem;
import com.salesmanagement.sync.internal.enums.OperationType;
import com.salesmanagement.sync.internal.enums.RecordType;
import com.salesmanagement.sync.internal.enums.SyncStatus;
import com.salesmanagement.sync.internal.repository.SyncQueueItemRepository;
import com.salesmanagement.visit.api.OfflineVisitInput;
import com.salesmanagement.visit.api.VisitFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Processes one queue item inside its OWN transaction, so a poison item rolls back only
 * itself and the batch continues (E4). It is a separate bean from {@link SyncService} on
 * purpose: {@code @Transactional(REQUIRES_NEW)} only takes effect through the Spring proxy,
 * which self-invocation inside a single bean would bypass.
 *
 * <p>Two settlement paths, deliberately in separate transactions:</p>
 * <ul>
 *   <li>{@code attempt*} — calls the target write facade and, only on success, writes the
 *       ledger row SYNCED. If the facade throws, this whole transaction rolls back and
 *       nothing is persisted.</li>
 *   <li>{@link #recordFailure} — a fresh transaction that writes the ledger row FAILED with
 *       the error. It must be separate: the failing facade call and its rollback cannot also
 *       carry the record of the failure, or the FAILED row would roll back too.</li>
 * </ul>
 *
 * <p>Every target facade is itself idempotent on the same client key, so re-running an item
 * whose ledger says FAILED but whose record actually landed simply returns the existing one.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncItemProcessor {

    private final SyncQueueItemRepository repository;
    private final ObjectMapper objectMapper;
    private final VisitFacade visitFacade;
    private final InvoiceFacade invoiceFacade;

    /**
     * Persists an offline visit (CREATE) or applies an offline check-out (UPDATE), then
     * settles the ledger SYNCED. Returns the server visit id (for CREATE and for a resolvable
     * UPDATE); null only if an UPDATE cannot resolve its visit — which itself throws below.
     *
     * @throws com.salesmanagement.shared.exception.BusinessException from the visit facade
     * @throws com.fasterxml.jackson.core.JsonProcessingException surfaced as a caller-side E4
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long attemptVisit(Long representativeId, SyncItemRequest item) throws Exception {
        VisitPayload p = objectMapper.treeToValue(item.payload(), VisitPayload.class);

        Long serverVisitId;
        if (item.operationType() == OperationType.UPDATE) {
            // A check-out completing a previously-synced check-in (Fork H).
            String visitClientUuid = p.visitClientUuid();
            visitFacade.recordOfflineCheckOut(
                    representativeId, visitClientUuid, p.checkOutTime(), p.checkOutLocation());
            serverVisitId = resolveVisitServerId(representativeId, visitClientUuid);
        } else {
            OfflineVisitInput input = new OfflineVisitInput(
                    item.clientUuid(), p.customerId(), p.routeId(),
                    p.checkInTime(), p.checkInLocation(), p.checkOutTime(), p.checkOutLocation());
            serverVisitId = visitFacade.recordOfflineVisit(representativeId, input);
        }

        writeSynced(representativeId, item, RecordType.VISIT, serverVisitId);
        return serverVisitId;
    }

    /**
     * Replays a completed offline sale as a SENT invoice, then settles the ledger SYNCED.
     * Resolves the visit reference (Fork D): an explicit {@code visitId} is used as-is; a
     * {@code visitClientUuid} is remapped through the ledger; an unresolvable reference
     * degrades to a null link (the sale still records — Fork B/C).
     *
     * @return the server invoice id
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long attemptInvoice(Long representativeId, SyncItemRequest item) throws Exception {
        InvoicePayload p = objectMapper.treeToValue(item.payload(), InvoicePayload.class);

        Long visitId = p.visitId();
        if (visitId == null && p.visitClientUuid() != null) {
            visitId = resolveVisitServerId(representativeId, p.visitClientUuid());
            if (visitId == null) {
                log.warn("Offline invoice {} references unresolved visit uuid {}; linking null",
                        item.clientUuid(), p.visitClientUuid());
            }
        }

        List<OfflineInvoiceInput.Line> lines = p.lines().stream()
                .map(l -> new OfflineInvoiceInput.Line(l.productId(), l.quantity(), l.price(), l.discount()))
                .toList();
        List<OfflineInvoiceInput.Artifact> artifacts = p.artifacts().stream()
                .map(a -> new OfflineInvoiceInput.Artifact(
                        a.type(), a.fileToken(), a.capturedAt(), a.latitude(), a.longitude()))
                .toList();

        OfflineInvoiceInput input = new OfflineInvoiceInput(
                item.clientUuid(), p.customerId(), visitId, p.invoiceDate(), lines, artifacts);

        Long invoiceId = invoiceFacade.createFromOfflineSync(representativeId, input);
        writeSynced(representativeId, item, RecordType.INVOICE, invoiceId);
        return invoiceId;
    }

    /**
     * Settles a batch of GPS items after {@code TrackingFacade.ingest} has run (success or
     * failure is decided by the caller). Written in one transaction; GPS is append-only and
     * the ledger rows are pure audit/idempotency records.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordGpsOutcome(Long representativeId, List<SyncItemRequest> items,
                                 boolean succeeded, String errorCode, String errorDetail) {
        for (SyncItemRequest item : items) {
            if (succeeded) {
                writeSynced(representativeId, item, RecordType.GPS_LOG, null);
            } else {
                writeFailed(representativeId, item, errorCode, errorDetail);
            }
        }
    }

    /**
     * Writes (or updates) the ledger row for a terminally-failed item (E4), in its own
     * transaction so it survives the rollback of the failed {@code attempt*} call.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long representativeId, SyncItemRequest item,
                              String errorCode, String errorDetail) {
        writeFailed(representativeId, item, errorCode, errorDetail);
    }

    // ── Ledger upsert helpers (run inside the caller's transaction) ──────────────────────

    private void writeSynced(Long representativeId, SyncItemRequest item,
                             RecordType recordType, Long serverRecordId) {
        SyncQueueItem row = upsert(representativeId, item, recordType);
        row.markSynced(serverRecordId);
        repository.save(row);
    }

    private void writeFailed(Long representativeId, SyncItemRequest item,
                             String errorCode, String errorDetail) {
        // recordType is derived from the item so a corrupt payload still lands typed.
        SyncQueueItem row = upsert(representativeId, item, item.recordType());
        row.markFailed(errorCode, trim(errorDetail));
        repository.save(row);
    }

    /**
     * Finds the ledger row for this clientUuid or creates it. On a retry (an existing FAILED
     * row) it refreshes the device resend count (Fork G); immutable fields stay as first seen.
     */
    private SyncQueueItem upsert(Long representativeId, SyncItemRequest item, RecordType recordType) {
        return repository.findByClientUuid(item.clientUuid())
                .map(existing -> {
                    existing.refreshRetryCount(item.retryCount());
                    return existing;
                })
                .orElseGet(() -> new SyncQueueItem(
                        item.clientUuid(),
                        representativeId,
                        recordType,
                        item.operationType(),
                        item.payload().toString(),
                        item.clientCreatedAt(),
                        item.retryCount()));
    }

    /**
     * Resolves an offline visit's clientUuid to its server id through the ledger (Fork D):
     * the VISIT item processed earlier in this batch recorded its new id as server_record_id.
     * Scoped to this rep so a crafted payload cannot point at another rep's visit.
     */
    private Long resolveVisitServerId(Long representativeId, String visitClientUuid) {
        if (visitClientUuid == null) {
            return null;
        }
        return repository
                .findByClientUuidAndRecordTypeAndRepresentativeId(
                        visitClientUuid, RecordType.VISIT, representativeId)
                .filter(v -> v.getSyncStatus() == SyncStatus.SYNCED)
                .map(SyncQueueItem::getServerRecordId)
                .orElse(null);
    }

    private static String trim(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.length() <= 2000 ? detail : detail.substring(0, 2000);
    }
}
