package com.salesmanagement.sync.internal.service;

import com.salesmanagement.shared.exception.BusinessException;
import com.salesmanagement.sync.internal.dto.SyncBatchRequest;
import com.salesmanagement.sync.internal.dto.SyncBatchResponse;
import com.salesmanagement.sync.internal.dto.SyncItemRequest;
import com.salesmanagement.sync.internal.dto.SyncItemResult;
import com.salesmanagement.sync.internal.dto.payload.GpsPayload;
import com.salesmanagement.sync.internal.entity.SyncQueueItem;
import com.salesmanagement.sync.internal.enums.OperationType;
import com.salesmanagement.sync.internal.enums.RecordType;
import com.salesmanagement.sync.internal.repository.SyncQueueItemRepository;
import com.salesmanagement.tracking.api.GpsPointInput;
import com.salesmanagement.tracking.api.TrackingFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates one push-sync batch (UC-23). It owns the ordering, the idempotency
 * short-circuit, GPS grouping, and per-item result assembly; it holds NO transaction of its
 * own — each item is committed independently by {@link SyncItemProcessor} so a poison item
 * (E4) cannot roll the batch back. Every failure is caught here and turned into a FAILED
 * result; the HTTP call itself always succeeds (200) with a per-item report.
 *
 * <p><strong>Order (Fork D).</strong> Items are processed VISIT → INVOICE → GPS
 * ({@code RecordType.priority()}), and within a type CREATE before UPDATE before DELETE, then
 * by device capture time. This lets an offline invoice resolve the offline visit it references,
 * and a check-out find the check-in it completes.</p>
 *
 * <p><strong>The whole batch belongs to one rep</strong> — the authenticated principal
 * ({@code representativeId}); the device cannot mix reps. That is why GPS points can be
 * collected across the batch and ingested in one deduplicated call.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncService {

    /**
     * Points per tracking ingest call. Mirrors the facade's documented ceiling ("at most
     * 500 points"); a batch with more GPS points than this is chunked. Local because the
     * facade does not publish the value as a constant.
     */
    private static final int GPS_INGEST_CHUNK = 500;

    /** VISIT→INVOICE→GPS, then CREATE→UPDATE→DELETE, then oldest capture first. */
    private static final Comparator<SyncItemRequest> PROCESSING_ORDER =
            Comparator.<SyncItemRequest>comparingInt(i -> i.recordType().priority())
                    .thenComparingInt(i -> i.operationType().ordinal())
                    .thenComparing(SyncItemRequest::clientCreatedAt);

    private final SyncQueueItemRepository repository;
    private final SyncItemProcessor processor;
    private final TrackingFacade trackingFacade;
    private final ObjectMapper objectMapper;

    /**
     * Processes the batch and returns a per-item outcome plus roll-up counts.
     *
     * @param representativeId the owning rep, from the JWT principal (never the payload)
     */
    public SyncBatchResponse processBatch(Long representativeId, SyncBatchRequest request) {
        List<SyncItemRequest> ordered = request.items().stream().sorted(PROCESSING_ORDER).toList();

        List<SyncItemResult> results = new ArrayList<>(ordered.size());
        List<SyncItemRequest> gpsItems = new ArrayList<>();

        for (SyncItemRequest item : ordered) {
            if (item.recordType() == RecordType.GPS_LOG) {
                gpsItems.add(item);           // batched and processed together, last
            } else {
                results.add(processNonGps(representativeId, item));
            }
        }
        results.addAll(processGpsGroup(representativeId, gpsItems));

        SyncBatchResponse response = SyncBatchResponse.of(results);
        log.info("Sync batch for rep {}: {} synced, {} duplicate, {} failed (of {} items)",
                representativeId, response.synced(), response.duplicate(), response.failed(),
                ordered.size());
        return response;
    }

    // ── VISIT / INVOICE ──────────────────────────────────────────────────────────────────

    private SyncItemResult processNonGps(Long representativeId, SyncItemRequest item) {
        if (item.operationType() == OperationType.DELETE) {
            return noOpDelete(item);          // Fork H: accepted, does nothing
        }

        Optional<SyncQueueItem> existing = repository.findByClientUuid(item.clientUuid());
        if (existing.isPresent() && existing.get().isSynced()) {
            return SyncItemResult.duplicate(item.clientUuid(), existing.get().getServerRecordId()); // E3
        }

        try {
            Long serverId = switch (item.recordType()) {
                case VISIT   -> processor.attemptVisit(representativeId, item);
                case INVOICE -> processor.attemptInvoice(representativeId, item);
                case GPS_LOG -> throw new IllegalStateException("GPS is processed in a group");
            };
            return SyncItemResult.synced(item.clientUuid(), serverId);

        } catch (BusinessException e) {
            // A business rejection the retry cannot cure (E4): record it, surface the code.
            processor.recordFailure(representativeId, item, e.getErrorCode(), e.getMessage());
            return SyncItemResult.failed(item.clientUuid(), e.getErrorCode(), e.getMessage());

        } catch (Exception e) {
            // Malformed payload or unexpected error (E4 "corrupt data").
            String code = "SYNC_ITEM_UNPROCESSABLE";
            log.warn("Sync item {} ({}) unprocessable: {}", item.clientUuid(), item.recordType(),
                    e.getMessage());
            processor.recordFailure(representativeId, item, code, e.getMessage());
            return SyncItemResult.failed(item.clientUuid(), code, e.getMessage());
        }
    }

    // ── GPS ───────────────────────────────────────────────────────────────────────────────

    /**
     * Ingests all GPS points in the batch. Already-synced points are reported DUPLICATE (E3);
     * malformed ones FAIL individually; the rest are chunked to the tracking cap and ingested.
     * Ingest is all-or-nothing per chunk (its own transaction), so a chunk fails or succeeds
     * as a unit — acceptable for append-only, low-value-per-row GPS, and the device is expected
     * not to send invalid points (FR-89).
     */
    private List<SyncItemResult> processGpsGroup(Long representativeId, List<SyncItemRequest> gpsItems) {
        if (gpsItems.isEmpty()) {
            return List.of();
        }

        List<SyncItemResult> results = new ArrayList<>(gpsItems.size());
        List<SyncItemRequest> toIngest = new ArrayList<>();

        for (SyncItemRequest item : gpsItems) {
            if (item.operationType() == OperationType.DELETE) {
                results.add(noOpDelete(item));
                continue;
            }
            Optional<SyncQueueItem> existing = repository.findByClientUuid(item.clientUuid());
            if (existing.isPresent() && existing.get().isSynced()) {
                results.add(SyncItemResult.duplicate(item.clientUuid(), null));   // E3
            } else {
                toIngest.add(item);
            }
        }

        // Parse each point; a malformed one fails on its own, not the whole group.
        List<SyncItemRequest> parsedItems = new ArrayList<>();
        List<GpsPointInput> parsedPoints = new ArrayList<>();
        for (SyncItemRequest item : toIngest) {
            try {
                GpsPayload gp = objectMapper.treeToValue(item.payload(), GpsPayload.class);
                parsedItems.add(item);
                parsedPoints.add(new GpsPointInput(gp.latitude(), gp.longitude(), gp.recordedAt()));
            } catch (Exception e) {
                String code = "SYNC_ITEM_UNPROCESSABLE";
                processor.recordFailure(representativeId, item, code, e.getMessage());
                results.add(SyncItemResult.failed(item.clientUuid(), code, e.getMessage()));
            }
        }

        // Chunk to the tracking ingest cap and settle each chunk as a unit.
        for (int from = 0; from < parsedItems.size(); from += GPS_INGEST_CHUNK) {
            int to = Math.min(from + GPS_INGEST_CHUNK, parsedItems.size());
            List<SyncItemRequest> chunkItems = parsedItems.subList(from, to);
            List<GpsPointInput> chunkPoints = parsedPoints.subList(from, to);

            try {
                trackingFacade.ingest(representativeId, chunkPoints);   // idempotent on (rep, recordedAt)
                processor.recordGpsOutcome(representativeId, chunkItems, true, null, null);
                for (SyncItemRequest item : chunkItems) {
                    results.add(SyncItemResult.synced(item.clientUuid(), null));
                }
            } catch (BusinessException e) {
                processor.recordGpsOutcome(representativeId, chunkItems, false, e.getErrorCode(), e.getMessage());
                for (SyncItemRequest item : chunkItems) {
                    results.add(SyncItemResult.failed(item.clientUuid(), e.getErrorCode(), e.getMessage()));
                }
            } catch (Exception e) {
                String code = "GPS_INGEST_FAILED";
                log.warn("GPS ingest failed for rep {}: {}", representativeId, e.getMessage());
                processor.recordGpsOutcome(representativeId, chunkItems, false, code, e.getMessage());
                for (SyncItemRequest item : chunkItems) {
                    results.add(SyncItemResult.failed(item.clientUuid(), code, e.getMessage()));
                }
            }
        }

        return results;
    }

    /** DELETE is accepted but does nothing in v1 (Fork H); reported as success so the device clears it. */
    private SyncItemResult noOpDelete(SyncItemRequest item) {
        log.debug("Sync item {} is a DELETE — no-op in v1", item.clientUuid());
        return SyncItemResult.synced(item.clientUuid(), null);
    }
}
