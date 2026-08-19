package com.salesmanagement.sync.internal.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.salesmanagement.sync.internal.enums.OperationType;
import com.salesmanagement.sync.internal.enums.RecordType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * One item in a push-sync batch, exactly as the device sends it (matches the
 * protocol in the architecture doc). The {@code payload} stays a raw {@link JsonNode}
 * here — sync does not bind it to a concrete type until it knows the {@code recordType}
 * and hands the right slice to the right processor.
 *
 * <p>The envelope fields are bean-validated (a malformed envelope is a 400 for the
 * whole request — a client bug, not offline data). The {@code payload} itself is only
 * shape-checked later, per type; a corrupt payload is an E4 item-level FAILED, never a
 * batch-level 400.</p>
 *
 * <p>Note there is no {@code representativeId} — the owner is the authenticated
 * principal, taken from the JWT by the controller. A device cannot claim to sync as
 * another rep.</p>
 *
 * @param clientUuid      per-record idempotency key (v4 UUID); the dedup key (FR-99)
 * @param recordType      INVOICE | VISIT | GPS_LOG
 * @param operationType   CREATE | UPDATE | DELETE (DELETE is a no-op in v1, Fork H)
 * @param payload         self-contained JSON of the record; bound per type by the processor
 * @param clientCreatedAt device UTC instant the record was captured offline (ERD CreatedAt)
 * @param retryCount      the device's own resend count for this item (Fork G); 0 on first send
 */
public record SyncItemRequest(
        @NotBlank @Size(max = 36) String clientUuid,
        @NotNull RecordType recordType,
        @NotNull OperationType operationType,
        @NotNull JsonNode payload,
        @NotNull Instant clientCreatedAt,
        @Min(0) int retryCount
) {}
