package com.salesmanagement.sync.internal.controller;

import com.salesmanagement.shared.api.ApiResponse;
import com.salesmanagement.shared.security.UserPrincipal;
import com.salesmanagement.sync.internal.dto.SyncBatchRequest;
import com.salesmanagement.sync.internal.dto.SyncBatchResponse;
import com.salesmanagement.sync.internal.service.SyncService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST entry point for offline data sync — the single gateway for all mobile offline
 * records (R-9, UC-23).
 *
 * <p><strong>Authorization.</strong> Only a {@code SALES_REP} syncs (they own the mobile
 * app and the offline queue). Enforced with {@code @PreAuthorize} on the method, per the
 * project rule that role checks live only at the API layer (R-7). The owning rep is taken
 * from the JWT principal and passed down; the request body never carries a rep id, so a
 * device cannot sync records as someone else.</p>
 *
 * <p><strong>Response contract.</strong> The call returns 200 with a per-item report even
 * when some items were rejected — a batch is a bag of independent records, not one
 * transaction (E4). Only a malformed <em>envelope</em> (bean validation on
 * {@link SyncBatchRequest}) yields a 400, handled by {@code GlobalExceptionHandler}. There
 * is no pull endpoint here; reference-data pull and the notification-log reconcile are
 * separate GETs (built next).</p>
 */
@RestController
@RequestMapping("/api/sync")
@RequiredArgsConstructor
public class SyncController {

    private final SyncService syncService;

    /**
     * Push a batch of offline records (invoices, visits, GPS). Processes them in dependency
     * order, deduplicates by clientUuid, and returns each item's outcome.
     */
    @PostMapping("/batch")
    @PreAuthorize("hasRole('SALES_REP')")
    public ApiResponse<SyncBatchResponse> syncBatch(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody SyncBatchRequest request) {
        SyncBatchResponse response = syncService.processBatch(principal.getUserId(), request);
        return ApiResponse.ok(response, "Sync batch processed");
    }
}
