package com.salesmanagement.tracking.internal.controller;

import com.salesmanagement.shared.api.ApiResponse;
import com.salesmanagement.shared.security.UserPrincipal;
import com.salesmanagement.tracking.internal.dto.GpsPointResponse;
import com.salesmanagement.tracking.internal.dto.IngestResponse;
import com.salesmanagement.tracking.internal.dto.RecordGpsRequest;
import com.salesmanagement.tracking.internal.dto.RepLatestLocationResponse;
import com.salesmanagement.tracking.internal.service.LiveLocationBroadcaster;
import com.salesmanagement.tracking.internal.service.TrackingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;
import java.util.List;

/**
 * REST API for GPS tracking.
 *
 * <p><strong>Two audiences, two shapes.</strong> The mobile app writes: one ordinary
 * request/response {@code POST} per capture interval, or one carrying the backlog after a
 * reconnect. The dashboard reads: a snapshot on load, then a held-open stream for updates. Nothing
 * flows the other way on either connection, which is exactly why the read side is SSE and not
 * WebSocket.</p>
 *
 * <p><strong>Authorization</strong> is role-only at this layer, per project rule. Reps write and
 * cannot read the fleet: showing one rep where their colleagues are is peer surveillance, which no
 * requirement asks for. Managers and admins read and cannot write. The single ownership rule in the
 * module sits on the trail endpoint and is expressed declaratively, so no service-layer guard is
 * needed.</p>
 *
 * <p>The rep's id is never accepted from a request body — it comes from the authenticated
 * principal, so there is nothing to forge.</p>
 */
@RestController
@RequestMapping("/api/tracking")
@RequiredArgsConstructor
public class TrackingController {

    private final TrackingService trackingService;
    private final LiveLocationBroadcaster broadcaster;

    /**
     * Record a batch of GPS fixes. SALES_REP only.
     *
     * <p>Always a batch, even for a single point, so the online and reconnect paths are the same
     * endpoint with the same semantics.</p>
     *
     * <p>Returns 200, not 201. A batch is not a resource with a location, and the interesting part
     * of the response is the {@code accepted} / {@code duplicatesSkipped} split the client needs to
     * decide what to clear from its queue. A duplicate-only response is a success, not a
     * conflict.</p>
     */
    @PostMapping("/gps")
    @PreAuthorize("hasRole('SALES_REP')")
    public ApiResponse<IngestResponse> record(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody RecordGpsRequest request) {
        return ApiResponse.ok(IngestResponse.from(
                trackingService.ingest(principal.getUserId(), request)));
    }

    /**
     * Latest known position of every rep (FR-61). ADMIN or SALES_MANAGER.
     *
     * <p>The dashboard calls this once on load to paint the initial marker set, then keeps the
     * markers current from {@code /live}. It should also call it again after any SSE reconnect:
     * events that occurred while the connection was down are not replayed, so a fresh snapshot is
     * what closes the gap.</p>
     *
     * <p>Includes stale reps with {@code active = false} rather than omitting them.</p>
     */
    @GetMapping("/latest")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public ApiResponse<List<RepLatestLocationResponse>> latest() {
        return ApiResponse.ok(trackingService.getLatestLocations());
    }

    /**
     * Live position stream (FR-62, FR-63). ADMIN or SALES_MANAGER.
     *
     * <p>Returns an open {@code text/event-stream} carrying {@code location} events, each one a
     * {@link RepLatestLocationResponse} — the same shape {@code /latest} returns, so the client
     * needs one handler rather than two. Interleaved comment frames every 30 seconds are
     * heartbeats; {@code EventSource} discards them automatically.</p>
     *
     * <p>This response is not wrapped in {@code ApiResponse}. The envelope describes a single
     * completed response, and this one never completes: the wrapper has no correct place to sit in a
     * stream, and {@code EventSource} expects raw event frames.</p>
     *
     * <p>Every rep is broadcast to every subscriber; territory filtering is a client concern.</p>
     */
    @GetMapping(value = "/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public SseEmitter live() {
        return broadcaster.subscribe();
    }

    /**
     * One rep's trail for one day, oldest first. ADMIN, SALES_MANAGER, or the rep themselves.
     *
     * <p>The self-access clause is the module's only ownership rule and the whole of its answer to
     * the privacy question this feature inevitably raises: a rep can see their own movement history,
     * and nobody else's. It uses {@code principal.userId} to match {@code UserPrincipal.getUserId()};
     * {@code principal.id} silently fails to resolve and denies everyone.</p>
     *
     * <p>{@code date} is required and bounds the response to a single day. That is the only thing
     * standing between this endpoint and a request for five years of points.</p>
     */
    @GetMapping("/reps/{repId}/trail")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER') or #repId == principal.userId")
    public ApiResponse<List<GpsPointResponse>> trail(
            @PathVariable Long repId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(trackingService.getTrail(repId, date));
    }
}
