package com.salesmanagement.visit.internal.seed;

import com.salesmanagement.shared.seed.DemoDataContributor;
import com.salesmanagement.shared.seed.SeedContext;
import com.salesmanagement.shared.seed.SeedContext.SeedRoute;
import com.salesmanagement.routing.api.RouteInfo;
import com.salesmanagement.routing.api.RoutingFacade;
import com.salesmanagement.shared.seed.SeedWindow;
import com.salesmanagement.visit.internal.entity.Visit;
import com.salesmanagement.visit.internal.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * What actually happened at each planned stop.
 *
 * <h2>The four outcomes are all real, and all necessary</h2>
 * The route analytics reports {@code completed}, {@code missed}, {@code inProgress} and
 * {@code notVisited}, and the last of those is not a status at all — it is a planned stop with no
 * visit row, computed as {@code planned − (completed + missed + inProgress)}. So producing it means
 * deliberately leaving some stops without a visit, not writing a fourth status. A seeder that
 * created one visit per stop would silently peg {@code notVisited} at zero forever and nobody would
 * be able to tell whether the number was right or the chart was broken.
 *
 * <p>The mix is drawn per route from the deterministic stream: most stops complete, a minority are
 * marked MISSED (shop closed, owner away), a few are simply never reached. Today's routes also carry
 * an IN_PROGRESS visit — a rep currently inside a shop — which is only truthful on the current date.</p>
 *
 * <h2>Times are real instants on the route's own day</h2>
 * A visit's check-in is placed during working hours of the route date in the business zone, with
 * check-out twenty minutes to an hour later. Visits are built through the entity's own factories
 * ({@code Visit.checkIn}, {@code Visit.missed}, {@code completeCheckOut}) so the status/timestamp
 * invariants are the production ones: a MISSED visit has no times, which is precisely why the route
 * report anchors on route id rather than on check-in time.
 *
 * <p>Idempotent through the schema's own {@code UNIQUE(route_id, customer_id)}: existing pairs are
 * read once and skipped.</p>
 */
@Component
@Profile({"local", "dev"})
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class VisitDemoData implements DemoDataContributor {

    /**
     * Marks a visit as seeded. The {@code client_uuid} column exists for offline idempotency and is
     * nullable, which makes it the natural place to record ownership: it lets a reset delete exactly
     * the visits this seeder created and leave every other visit — and any invoice bound to one —
     * untouched. Selecting by date window instead would sweep up rows the seeder does not own.
     */
    private static final String SEED_UUID_PREFIX = "demo-visit-";

    private final VisitRepository visitRepository;
    private final RoutingFacade routingFacade;

    @Override
    public int order() {
        return SeedOrder.VISITS;
    }

    @Override
    public String label() {
        return "visits";
    }

    @Override
    @Transactional
    public void contribute(SeedContext context) {
        List<SeedRoute> routes = context.routes();
        if (routes.isEmpty()) {
            log.warn("Skipping visits: no routes were seeded in this run");
            return;
        }

        Random random = context.random("visits");
        LocalDate today = context.window().today();

        List<Visit> batch = new ArrayList<>();
        int completed = 0;
        int missed = 0;
        int inProgress = 0;
        int notVisited = 0;

        for (SeedRoute route : routes) {
            boolean isToday = route.date().equals(today);

            // Existing visits for this route, so a re-run adds nothing.
            List<Long> alreadyVisited = visitRepository.findByRouteId(route.id()).stream()
                    .map(Visit::getCustomerId)
                    .toList();

            // Route quality: most days go well, some are rough.
            int quality = random.nextInt(100);
            int missRate = quality < 70 ? 8 : quality < 90 ? 20 : 35;
            int skipRate = quality < 70 ? 5 : quality < 90 ? 12 : 22;

            List<Long> stops = route.customerIds();
            for (int i = 0; i < stops.size(); i++) {
                Long customerId = stops.get(i);
                if (alreadyVisited.contains(customerId)) {
                    continue;
                }

                // A PLANNED route that has not started has no visits at all yet.
                if ("PLANNED".equals(route.status())) {
                    notVisited++;
                    continue;
                }

                int roll = random.nextInt(100);

                // Today's active routes: the last stop of the day is still being served.
                if (isToday && "ACTIVE".equals(route.status()) && i == stops.size() - 1) {
                    Visit open = Visit.checkIn(customerId, route.representativeId(), route.id(),
                            instantAt(route.date(), 13, 40, random), location(random));
                    open.setClientUuid(seedKey(route.id(), customerId));
                    batch.add(open);
                    inProgress++;
                    continue;
                }

                if (roll < missRate) {
                    Visit skipped = Visit.missed(customerId, route.representativeId(), route.id());
                    skipped.setClientUuid(seedKey(route.id(), customerId));
                    batch.add(skipped);
                    missed++;
                } else if (roll < missRate + skipRate) {
                    // Never reached: no visit row at all — this is what notVisited counts.
                    notVisited++;
                } else {
                    Instant checkIn = instantAt(route.date(), 8 + (i % 6), i * 7 % 60, random);
                    Visit visit = Visit.checkIn(customerId, route.representativeId(), route.id(),
                            checkIn, location(random));
                    visit.completeCheckOut(
                            checkIn.plusSeconds(60L * (20 + random.nextInt(40))), location(random));
                    visit.setClientUuid(seedKey(route.id(), customerId));
                    batch.add(visit);
                    completed++;
                }
            }
        }

        visitRepository.saveAll(batch);

        context.count("visits", batch.size());
        log.info("Demo visits ready: {} created ({} completed, {} missed, {} in progress); "
                        + "{} planned stops deliberately left unvisited",
                batch.size(), completed, missed, inProgress, notVisited);
    }

    /**
     * Deletes visits belonging to routes inside the seeded window. Runs before the route reset, so
     * the routes are still there to be asked about — and it must ask, because a MISSED visit has no
     * timestamp of its own and cannot be selected by date. The route ids come from
     * {@code RoutingFacade}, the same public read the route reports anchor on, rather than from the
     * seed context: during a reset the context has not been populated yet.
     */
    @Override
    @Transactional
    public void resetSeededData(SeedContext context) {
        List<Long> routeIds = routingFacade
                .findRoutesInRange(context.window().start(), context.window().exclusiveEnd(), null)
                .stream()
                .map(RouteInfo::id)
                .toList();

        // Only rows this seeder stamped. A visit created by the application — or by the SQL demo
        // seed — may have an invoice pointing at it, and deleting it would either break that foreign
        // key or silently take a real invoice with it.
        List<Visit> doomed = visitRepository.findByRouteIdIn(routeIds).stream()
                .filter(v -> v.getClientUuid() != null
                        && v.getClientUuid().startsWith(SEED_UUID_PREFIX))
                .toList();

        visitRepository.deleteAll(doomed);
        log.warn("Reset: deleted {} seeded visits across {} routes inside the window",
                doomed.size(), routeIds.size());
    }

    /** Deterministic ownership key; also unique per (route, customer), matching the schema. */
    private String seedKey(Long routeId, Long customerId) {
        return SEED_UUID_PREFIX + routeId + "-" + customerId;
    }

    /** An instant during working hours of the given business day. */
    private Instant instantAt(LocalDate date, int hour, int minute, Random random) {
        int safeHour = Math.min(17, Math.max(8, hour));
        return date.atTime(LocalTime.of(safeHour, minute % 60, random.nextInt(60)))
                .atZone(SeedWindow.BUSINESS_ZONE)
                .toInstant();
    }

    /** A {@code "lat,lng"} inside greater Damascus, as the mobile app records it. */
    private String location(Random random) {
        double lat = 33.49 + random.nextDouble() * 0.07;
        double lng = 36.22 + random.nextDouble() * 0.11;
        return String.format("%.6f,%.6f", lat, lng);
    }
}
