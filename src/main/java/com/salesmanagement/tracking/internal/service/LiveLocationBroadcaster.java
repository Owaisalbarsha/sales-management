package com.salesmanagement.tracking.internal.service;

import com.salesmanagement.identity.api.UserFacade;
import com.salesmanagement.systemconfig.api.ConfigFacade;
import com.salesmanagement.systemconfig.api.ConfigKey;
import com.salesmanagement.tracking.internal.TrackingPolicy;
import com.salesmanagement.tracking.internal.dto.RepLatestLocationResponse;
import com.salesmanagement.tracking.internal.event.GpsPointsIngested;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pushes live position updates to subscribed dashboards over Server-Sent Events (FR-62, FR-63).
 *
 * <p><strong>Why SSE and not WebSocket.</strong> The traffic is one-directional: the server has
 * news, the dashboard listens. WebSocket would add an upgrade handshake, a second protocol,
 * usually a STOMP broker, and hand-rolled reconnection, all to enable a client-to-server channel
 * nothing uses. SSE is plain HTTP, so the existing JWT filter and any proxy in front of the app
 * just work, and {@code EventSource} reconnects on its own. The mobile app is unaffected either
 * way: it <em>posts</em> points over ordinary REST and never subscribes here.</p>
 *
 * <p><strong>Single-instance only.</strong> Emitters are held in a JVM-local list. Two application
 * instances behind a load balancer would each reach only their own subscribers, and a rep's update
 * would appear on some dashboards and not others. Making this horizontal would mean moving the fan-
 * out to a shared broker (Redis pub/sub, or Postgres {@code LISTEN}/{@code NOTIFY}), with each
 * instance subscribing and relaying to its local emitters. That is a deployment decision, not a
 * code one, and this system deploys as a single instance.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveLocationBroadcaster {

    /**
     * How long an SSE connection is held before the server closes it and the browser transparently
     * reconnects.
     *
     * <p>Finite on purpose. {@code Long.MAX_VALUE} sounds like "never drop the client" but actually
     * means a leaked emitter is leaked forever, and a client that misses the close never notices a
     * half-dead connection. A bounded lifetime turns {@code EventSource}'s built-in reconnect into
     * a periodic self-heal that also recovers subscribers after a server restart.</p>
     */
    private static final Duration EMITTER_TIMEOUT = Duration.ofMinutes(30);

    /**
     * Live subscribers. {@code CopyOnWriteArrayList} because the read pattern (iterate to broadcast)
     * overwhelmingly dominates the write pattern (a tab opens or closes), and because iteration
     * must not throw {@code ConcurrentModificationException} while a failed send removes an entry
     * mid-loop.
     */
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    private final UserFacade userFacade;
    private final ConfigFacade configFacade;

    // ═══════════════════════════════════════════════════════════════════════
    //  Subscription
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Registers a new dashboard subscriber and returns the emitter for the controller to hand back
     * to Spring MVC.
     *
     * <p>All four lifecycle callbacks are wired. Skipping any of them leaks: {@code onCompletion}
     * covers a normal close, {@code onTimeout} the expiry above, {@code onError} a transport
     * failure. Without them the list grows with every page refresh until the heap gives out.</p>
     */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT.toMillis());

        emitter.onCompletion(() -> remove(emitter, "completed"));
        emitter.onTimeout(()    -> remove(emitter, "timed out"));
        emitter.onError(e       -> remove(emitter, "errored: " + e.getMessage()));

        emitters.add(emitter);
        log.debug("SSE subscriber added; active subscribers={}", emitters.size());
        return emitter;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Broadcast
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Fans a position update out to every subscriber, <strong>after</strong> the ingest transaction
     * has committed.
     *
     * <p>{@link TransactionPhase#AFTER_COMMIT} is the whole point of this method's existence.
     * Broadcasting inside the transaction would push points that a subsequent rollback erases,
     * leaving the map showing positions the database does not contain and no way to correct them:
     * SSE has no retraction. Committing first means everything on the dashboard is something a
     * later {@code /latest} call will confirm.</p>
     *
     * <p>The listener is intentionally synchronous rather than {@code @Async}. The work is a name
     * lookup and a fan-out over a handful of connections; handing it to another thread pool would
     * add a scheduling dependency and an ordering hazard for no measurable gain at this scale.</p>
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGpsPointsIngested(GpsPointsIngested event) {
        RepLatestLocationResponse payload = new RepLatestLocationResponse(
                event.representativeId(),
                safeUserName(event.representativeId()),
                event.latitude(),
                event.longitude(),
                event.recordedAt(),
                isActive(event.recordedAt()));

        broadcast(payload);
    }

    private void broadcast(RepLatestLocationResponse payload) {
        if (emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("location")
                        .data(payload));
            } catch (IOException | IllegalStateException e) {
                // The browser tab closed, or the emitter already completed. Not an error worth a
                // stack trace; drop the subscriber and carry on with the rest.
                remove(emitter, "send failed");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Heartbeat
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Sends an SSE comment frame to every subscriber every 30 seconds.
     *
     * <p>Not optional. Reverse proxies, ingress controllers and free-tier platform routers close
     * connections that have been idle for around 60 seconds, and reps do not move constantly, so a
     * quiet stretch is normal. Without a heartbeat the connection is severed, the dashboard stops
     * updating, and because {@code EventSource} reconnects quietly the failure is invisible until
     * someone notices the markers have frozen. A comment line costs three bytes and removes the
     * entire failure mode.</p>
     *
     * <p>Requires {@code @EnableScheduling} on the application (already present for the visit
     * module's nightly sweep). Verify before assuming this method runs.</p>
     */
    @Scheduled(fixedRate = 30_000)
    public void heartbeat() {
        if (emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
                remove(emitter, "heartbeat failed");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private void remove(SseEmitter emitter, String reason) {
        if (emitters.remove(emitter)) {
            log.debug("SSE subscriber removed ({}); active subscribers={}", reason, emitters.size());
        }
    }

    private boolean isActive(Instant recordedAt) {    // drop 'static'
        int windowMinutes = configFacade.getInt(
                ConfigKey.TRACKING_ACTIVE_WINDOW_MINUTES,
                (int) TrackingPolicy.ACTIVE_WINDOW.toMinutes());
        return recordedAt.isAfter(Instant.now().minus(Duration.ofMinutes(windowMinutes)));
    }

    /** Name enrichment must never break a broadcast; an unresolvable user yields {@code null}. */
    private String safeUserName(Long userId) {
        try {
            return userFacade.getNameById(userId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Current subscriber count. Test and diagnostics only. */
    int subscriberCount() {
        return emitters.size();
    }
}
