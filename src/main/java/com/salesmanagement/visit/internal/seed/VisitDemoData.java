package com.salesmanagement.visit.internal.seed;

import com.salesmanagement.shared.seed.DemoDataContributor;
import com.salesmanagement.shared.seed.SeedContext;
import com.salesmanagement.shared.seed.SeedContext.SeedRoute;
import com.salesmanagement.shared.seed.SeedWindow;
import com.salesmanagement.visit.internal.entity.Visit;
import com.salesmanagement.visit.internal.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * What actually happened at each planned stop.
 *
 * <h2>A finished route has no unfinished stops</h2>
 * The route lifecycle is driven by this module: {@code VisitService} publishes
 * {@code RouteVisitsFinalized} only once <em>every</em> stop on a route carries a terminal visit,
 * and only then does routing flip the route to COMPLETED. So a COMPLETED route with a stop that has
 * no visit row is a state the application cannot reach - the nightly {@code VisitSweepJob} exists
 * precisely to materialise the missing MISSED rows before finalising. An earlier version of this
 * seeder deliberately left stops without visits on COMPLETED routes in order to give the route
 * report a non-zero {@code notVisited} column; that produced a number no running system would ever
 * show, and it disagreed with the route's own status.
 *
 * <p>{@code notVisited} is still populated, from the only place it can honestly come from: the
 * stops on <strong>today's ACTIVE routes</strong> that the rep has not reached yet. The rule is
 * therefore:</p>
 * <ul>
 *   <li><strong>Past route (always COMPLETED)</strong> - every stop gets a terminal visit, either
 *       COMPLETED or MISSED. Nothing is left open, nothing is left absent.</li>
 *   <li><strong>Today, ACTIVE</strong> - the stops already worked are COMPLETED, the stop the rep is
 *       standing in right now is the single IN_PROGRESS visit, and the stops after it have no row
 *       yet. That last group is what {@code notVisited} counts.</li>
 *   <li><strong>Today, PLANNED</strong> - no visits at all. The rep has not set off.</li>
 * </ul>
 *
 * <h2>One open visit per route, ever</h2>
 * {@code VisitService.checkIn} refuses a check-in while any visit on the route is still open (the
 * "force check-out" rule). At most one IN_PROGRESS visit is created per route here, and only on
 * today's ACTIVE ones.
 *
 * <h2>Times are real instants, ordered, and never in the future</h2>
 * Check-ins march forward through the working day in stop order, each check-out twenty to seventy
 * minutes after its own check-in - so {@code checkOutTime >= checkInTime} holds per visit and the
 * day reads as a sequence rather than a scatter. Today's visits are additionally clamped to the
 * current wall clock, because {@code VisitService} rejects any timestamp more than five minutes
 * ahead of now. Visits are built through the entity's own factories ({@code Visit.checkIn},
 * {@code Visit.missed}, {@code completeCheckOut}) so the status/timestamp invariants are the
 * production ones: a MISSED visit has all four time and location columns null.
 *
 * <p>Idempotent through the schema's own {@code UNIQUE(route_id, customer_id)}: existing pairs are
 * read once and skipped.</p>
 */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class VisitDemoData implements DemoDataContributor {

    /**
     * Marks a visit as seeded. The {@code client_uuid} column exists for offline idempotency and is
     * nullable, which makes it the natural place to record ownership: it lets a reset delete exactly
     * the visits this seeder created and leave every other visit - and any invoice bound to one -
     * untouched. Selecting by date window instead would sweep up rows the seeder does not own.
     */
    private static final String SEED_UUID_PREFIX = "demo-visit-";

    /** First and last check-in hour of a working day, in the business zone. */
    private static final int DAY_START_HOUR = 8;
    private static final int DAY_END_HOUR = 17;

    private final VisitRepository visitRepository;

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

        SeedWindow window = context.window();

        // Clock-skew guard: VisitService refuses a timestamp more than five minutes ahead of now,
        // so today's visits stop at the current instant rather than running to 17:00.
        Instant latestAllowed = Instant.now().minus(Duration.ofMinutes(1));

        List<Visit> batch = new ArrayList<>();
        List<Pending> pending = new ArrayList<>();
        int completed = 0;
        int missed = 0;
        int inProgress = 0;
        int notReachedYet = 0;

        for (SeedRoute route : routes) {
            boolean isToday = window.isToday(route.date());
            // One stream per route, so a route's outcomes never shift because a different route
            // was skipped as already-seeded earlier in the loop.
            Random random = context.randomFor("visits", route.id());

            // Existing visits for this route, so a re-run adds nothing (UNIQUE(route, customer)).
            Set<Long> alreadyVisited = new HashSet<>();
            List<Visit> existing = visitRepository.findByRouteId(route.id());
            existing.forEach(v -> alreadyVisited.add(v.getCustomerId()));

            // A visit left open on a day that is over. Same situation VisitSweepJob handles at
            // 02:00: the route has been closed out (either by the calendar moving past it, or
            // because an older seeder left it open), and a stop cannot stay IN_PROGRESS on a
            // finished route - rule B8. The rep demonstrably arrived, so this closes as COMPLETED
            // rather than MISSED, which would falsely say they never turned up.
            if (!isToday) {
                closeLingeringVisits(existing, route.date());
            }

            List<Long> stops = route.customerIds();
            if (stops.isEmpty()) {
                continue;
            }

            // A PLANNED route has not started: no visits at all.
            if ("PLANNED".equals(route.status())) {
                continue;
            }

            // How far through the stop list the rep has got. A finished route reached the end of
            // its list by definition; today's live route is somewhere in the middle of it.
            int progress = isToday
                    ? 1 + random.nextInt(stops.size())     // at least one stop under way
                    : stops.size();

            // Route quality: most days go well, some are rough. Drives how many of the stops the
            // rep did reach were nevertheless a wasted trip - shop shut, owner away.
            int quality = random.nextInt(100);
            int missRate = quality < 70 ? 8 : quality < 90 ? 18 : 30;

            Instant cursor = startOfDay(route.date(), random);
            // Two ceilings at once: a route never runs past the end of its own working day, and
            // today's never runs past the current wall clock.
            Instant ceiling = min(endOfDay(route.date()), latestAllowed);

            for (int i = 0; i < stops.size(); i++) {
                Long customerId = stops.get(i);

                if (i >= progress) {
                    // Not reached yet. Only reachable on today's ACTIVE routes - a past route ran
                    // to the end of its list, so this branch never fires for one.
                    notReachedYet++;
                    continue;
                }

                boolean isCurrentStop = isToday && i == progress - 1;

                if (alreadyVisited.contains(customerId)) {
                    continue;
                }

                // Rule B7: the stop the rep is standing in is the route's single open visit.
                if (isCurrentStop) {
                    Instant checkIn = min(cursor, ceiling);
                    Visit open = Visit.checkIn(customerId, route.representativeId(), route.id(),
                            checkIn, location(random));
                    open.setClientUuid(seedKey(route.id(), customerId));
                    batch.add(open);
                    pending.add(new Pending(route, customerId, "IN_PROGRESS", checkIn, null));
                    inProgress++;
                    continue;
                }

                if (random.nextInt(100) < missRate) {
                    // Rule B2: a MISSED visit carries no times and no locations at all.
                    Visit skipped = Visit.missed(customerId, route.representativeId(), route.id());
                    skipped.setClientUuid(seedKey(route.id(), customerId));
                    batch.add(skipped);
                    pending.add(new Pending(route, customerId, "MISSED", null, null));
                    missed++;
                    // A missed stop still costs the drive there and a few minutes at the door.
                    cursor = cursor.plusSeconds(60L * (8 + random.nextInt(12)));
                    continue;
                }

                Instant checkIn = min(cursor, ceiling);
                Instant checkOut = min(
                        checkIn.plusSeconds(60L * (20 + random.nextInt(50))), ceiling);

                Visit visit = Visit.checkIn(customerId, route.representativeId(), route.id(),
                        checkIn, location(random));
                // Rule B3: check-out is never before check-in - min() above preserves the order
                // because checkIn is itself already clamped to the same ceiling.
                visit.completeCheckOut(checkOut, location(random));
                visit.setClientUuid(seedKey(route.id(), customerId));
                batch.add(visit);
                pending.add(new Pending(route, customerId, "COMPLETED", checkIn, checkOut));
                completed++;

                // Next stop: the visit itself, plus the drive to the following shop.
                cursor = checkOut.plusSeconds(60L * (10 + random.nextInt(25)));
            }
        }

        List<Visit> saved = visitRepository.saveAll(batch);

        for (int i = 0; i < saved.size(); i++) {
            Pending p = pending.get(i);
            context.visits().add(new SeedContext.SeedVisit(
                    saved.get(i).getId(), p.route().id(), p.customerId(),
                    p.route().representativeId(), p.route().date(),
                    p.status(), p.checkIn(), p.checkOut()));
        }

        context.count("visits", saved.size());
        log.info("Demo visits ready: {} created ({} completed, {} missed, {} in progress); "
                        + "{} stops on today's live routes not reached yet",
                saved.size(), completed, missed, inProgress, notReachedYet);
    }

    /**
     * Deletes every visit this seeder stamped, wherever it is dated.
     *
     * <p><strong>Deliberately not limited to the current window.</strong> The window is rolling, so
     * it moves a day forward every day and moved by six months the day it was shortened from eight
     * months to two. A reset bounded by it would leave every visit that had fallen out of range
     * behind - permanently, because no later run would ever look at those dates again. The
     * {@code client_uuid} prefix is exact ownership and needs no date bound to be safe.</p>
     *
     * <p>The prefix filter is the safety, not the date. A visit created by the application - or by
     * the SQL demo seed - may have an invoice pointing at it, and deleting it would either break
     * that foreign key or silently take a real invoice with it. Only rows carrying the seeder's own
     * marker are touched.</p>
     */
    @Override
    @Transactional
    public void resetSeededData(SeedContext context) {
        List<Visit> doomed = visitRepository.findAll().stream()
                .filter(v -> v.getClientUuid() != null
                        && v.getClientUuid().startsWith(SEED_UUID_PREFIX))
                .toList();

        visitRepository.deleteAll(doomed);
        log.warn("Reset: deleted {} seeded visits", doomed.size());
    }

    /** Deterministic ownership key; also unique per (route, customer), matching the schema. */
    private String seedKey(Long routeId, Long customerId) {
        return SEED_UUID_PREFIX + routeId + "-" + customerId;
    }

    /** The instant the rep set off on the given business day - a little after eight. */
    private Instant startOfDay(LocalDate date, Random random) {
        return date.atTime(LocalTime.of(DAY_START_HOUR, random.nextInt(45), random.nextInt(60)))
                .atZone(SeedWindow.BUSINESS_ZONE)
                .toInstant();
    }

    /**
     * Checks out any visit still open on a finished day.
     *
     * <p>{@code VisitSweepJob} does this with a null check-out time, on the grounds that the server
     * genuinely does not know when the rep left. A seeder does know - it invented the day - so it
     * writes the close of business instead, which is both a legal COMPLETED shape and better data
     * than a null the visit-duration report would have to skip.</p>
     */
    private void closeLingeringVisits(List<Visit> existing, LocalDate date) {
        for (Visit visit : existing) {
            if (visit.getStatus() != com.salesmanagement.visit.internal.enums.VisitStatus.IN_PROGRESS) {
                continue;
            }
            Instant closedAt = endOfDay(date);
            // Rule B3 still applies: never close before the rep checked in.
            if (visit.getCheckInTime() != null && closedAt.isBefore(visit.getCheckInTime())) {
                closedAt = visit.getCheckInTime();
            }
            visit.completeCheckOut(closedAt, visit.getCheckInLocation());
            visitRepository.save(visit);
            log.info("Closed lingering IN_PROGRESS visit id={} on finished route {} at {}",
                    visit.getId(), visit.getRouteId(), closedAt);
        }
    }

    /** The instant the working day closes on the given business date. */
    private Instant endOfDay(LocalDate date) {
        return date.atTime(LocalTime.of(DAY_END_HOUR, 0))
                .atZone(SeedWindow.BUSINESS_ZONE)
                .toInstant();
    }

    /**
     * The earlier of two instants.
     *
     * <p>Used as a ceiling in two directions at once. On a past day it caps a long route at the end
     * of the working day rather than letting the cursor run into the small hours; on today it caps
     * every timestamp at the current wall clock, which {@code VisitService.requireNotFuture} would
     * otherwise reject. Applying the same clamp to check-in and check-out keeps their order intact:
     * if both are pinned to the ceiling they are equal, which the service permits.</p>
     */
    private Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    /**
     * A {@code "lat,lng"} inside greater Damascus, as the mobile app records it.
     *
     * <p><strong>{@link Locale#ROOT} is not optional here.</strong> The default locale on a machine
     * configured for Arabic renders digits as Arabic-Indic numerals, so this method was writing
     * check-in locations like {@code ٣٣٫٥٢٤٠٢٥,٣٦٫٢٨٩} into the column. They look right in a
     * terminal and are unparseable as numbers by anything that reads them - the live map, the
     * distance calculations, and {@code VisitService.toLocation}, which produces plain ASCII via
     * {@code BigDecimal.toPlainString()} and would never have generated such a value itself. The
     * column is a machine-readable pair, so it is formatted in a fixed locale.</p>
     */
    private String location(Random random) {
        double lat = 33.49 + random.nextDouble() * 0.07;
        double lng = 36.22 + random.nextDouble() * 0.11;
        return String.format(Locale.ROOT, "%.6f,%.6f", lat, lng);
    }

    /** Carries what was built, so the saved ids can be published once the batch has flushed. */
    private record Pending(SeedRoute route, Long customerId, String status,
                           Instant checkIn, Instant checkOut) {}
}
