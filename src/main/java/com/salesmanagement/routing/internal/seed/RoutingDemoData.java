package com.salesmanagement.routing.internal.seed;

import com.salesmanagement.routing.internal.entity.Route;
import com.salesmanagement.routing.internal.entity.RouteCustomerAssignment;
import com.salesmanagement.routing.internal.enums.RouteStatus;
import com.salesmanagement.routing.internal.repository.RouteRepository;
import com.salesmanagement.shared.seed.DemoDataContributor;
import com.salesmanagement.shared.seed.SeedContext;
import com.salesmanagement.shared.seed.SeedContext.SeedCustomer;
import com.salesmanagement.shared.seed.SeedContext.SeedRep;
import com.salesmanagement.shared.seed.SeedContext.SeedTerritory;
import com.salesmanagement.shared.seed.SeedWindow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Daily field routes across the seeded window: finished work on every past working day, and exactly
 * one live route per representative today.
 *
 * <h2>The status of a route is decided by its date, not by a dice roll</h2>
 * An earlier version left a few percent of historical routes PLANNED or ACTIVE, on the theory that
 * operational untidiness is realistic. It is not reproducible, though, because
 * {@code VisitSweepJob} runs at 02:00 every night, finds every route dated before today that is
 * still open, marks its unvisited stops MISSED and finalises it. A past-dated open route is
 * therefore a state the running application actively destroys - seeding one produces a dataset that
 * silently rewrites itself the first night nobody is watching. So:
 *
 * <ul>
 *   <li><strong>Past working days</strong> - always {@code COMPLETED}. The visit contributor gives
 *       every stop on them a terminal visit, which is the precondition the routing listener
 *       requires before it will accept a route as finished.</li>
 *   <li><strong>Today</strong> - exactly one open route per rep. The reps who have already started
 *       are {@code ACTIVE} (this is what the dashboard's {@code activeRoutesToday} tile counts);
 *       the rest are still {@code PLANNED}, waiting to be picked up.</li>
 * </ul>
 *
 * <h2>One route per rep per day, and one open route per rep, full stop</h2>
 * {@code RouteService.create} refuses a second PLANNED-or-ACTIVE route for a rep on a date, and
 * {@code update} refuses a date collision with any route at all. Both are honoured here by
 * construction: the loop assigns each rep at most one route per date, and because no past route is
 * left open, each rep ends the run with exactly one open route in the entire database.
 *
 * <h2>Why routes are persisted directly rather than through {@code RouteService}</h2>
 * {@code RouteService.create} is built for planning tomorrow's work: it drives the
 * PLANNED to ACTIVE to COMPLETED lifecycle forward in real time, one transition per call, each one
 * meant to be triggered by a rep with a phone. Replaying two months of finished field work through
 * it would mean walking several hundred routes through that machine in a startup hook. Historical
 * routes are therefore written as the finished records they are, using this module's own entity and
 * repository - inside the owning module, where that is legitimate - and through the entity's own
 * {@code addAssignment} so the parent/child invariant still holds. Every rule the service would
 * have enforced is enforced here explicitly, and the verification pass re-checks each one against
 * the database afterwards.
 *
 * <p>Idempotent on (representative, date): the seeder plans at most one route per rep per day, so
 * that pair identifies a seeded route.</p>
 */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class RoutingDemoData implements DemoDataContributor {

    /**
     * Chance in a hundred that a given rep is out in the field on a given past working day. Under
     * 100 so the daily route count varies - leave, training, a sick day. A constant headcount makes
     * the route-volume trend a horizontal line.
     */
    private static final int FIELD_DAY_CHANCE = 82;

    /** Share of today's reps who have already started their route by the time the demo is viewed. */
    private static final int STARTED_TODAY_CHANCE = 62;

    /**
     * When the field day begins. Must match the visit contributor's first check-in hour: a route is
     * ACTIVE precisely because a check-in exists, so the two have to agree on when one can exist.
     */
    private static final LocalTime FIELD_DAY_START = LocalTime.of(8, 0);

    private static final List<String> ROUTE_PREFIXES = List.of("جولة", "خط سير", "مسار");

    private final RouteRepository routeRepository;

    @Override
    public int order() {
        return SeedOrder.ROUTES;
    }

    @Override
    public String label() {
        return "routes";
    }

    @Override
    @Transactional
    public void contribute(SeedContext context) {
        SeedWindow window = context.window();
        List<SeedRep> reps = context.representatives();
        if (reps.isEmpty() || context.customers().isEmpty()) {
            log.warn("Skipping routes: no representatives or customers were seeded");
            return;
        }

        // A route is ACTIVE because a rep checked in, and a rep cannot have checked in before the
        // working day began. Seeding at 06:00 must therefore produce a board of PLANNED routes
        // waiting to be picked up, not a fleet already out - and the visit contributor, which caps
        // every timestamp at the current wall clock, would otherwise have to invent check-ins dated
        // before dawn to justify the status. Both contributors read the same clock, so they agree.
        boolean dayHasStarted = !LocalTime.now(SeedWindow.BUSINESS_ZONE).isBefore(FIELD_DAY_START);

        // One lookup for the whole window; the alternative is a query per rep per day.
        Map<String, Route> existing = new HashMap<>();
        routeRepository.findWithAssignmentsInRange(window.start(), window.exclusiveEnd(), null)
                .forEach(r -> existing.putIfAbsent(key(r.getRepresentativeId(), r.getRouteDate()), r));

        List<Route> batch = new ArrayList<>();
        List<PendingRoute> pending = new ArrayList<>();
        int reused = 0;
        int completed = 0;
        int active = 0;
        int planned = 0;

        for (LocalDate day : window.workDays()) {
            boolean isToday = window.isToday(day);

            for (SeedRep rep : reps) {
                // Rule A2/A3: at most one route per rep per date, whatever its status.
                Route already = existing.get(key(rep.userId(), day));
                if (already != null) {
                    // Publish it anyway. The visit, invoice and tracking contributors build on this
                    // list, so a re-run that silently omitted routes it had created previously would
                    // leave them permanently without visits.
                    context.routes().add(toSeedRoute(closeIfOverdue(already, day, isToday)));
                    reused++;
                    continue;
                }

                // A rep works most past days, but not all of them. Today, everybody is rostered:
                // the point of today's slice is to show a full board.
                // One stream per (rep, date). Every decision below - roster, territory, stops,
                // status, optimisation flag - is drawn from it, so the route this seeder would
                // build for this rep on this day is the same on every run regardless of what else
                // already exists in the database. See SeedContext.randomFor.
                Random random = context.randomFor("routes", rep.userId(), day);

                if (!isToday && random.nextInt(100) >= FIELD_DAY_CHANCE) {
                    continue;
                }

                SeedTerritory territory = territoryFor(context, rep, random);
                List<SeedCustomer> stops = pickStops(context, territory, random);
                if (stops.isEmpty()) {
                    continue;
                }

                // Rule A4: nothing dated in the past may be left open.
                RouteStatus status;
                if (!isToday) {
                    status = RouteStatus.COMPLETED;
                    completed++;
                } else if (dayHasStarted && random.nextInt(100) < STARTED_TODAY_CHANCE) {
                    status = RouteStatus.ACTIVE;
                    active++;
                } else {
                    status = RouteStatus.PLANNED;
                    planned++;
                }

                String name = ROUTE_PREFIXES.get(random.nextInt(ROUTE_PREFIXES.size()))
                        + " " + territory.name() + " - " + day;

                Route route = new Route(rep.userId(), territory.id(), name, day);
                route.setStatus(status);
                // Rule A9: the flag answers "is the current order machine-computed?". A route that
                // was hand-built by the manager is not, so most are false.
                route.setOptimized(random.nextInt(100) < 45);

                // Rule A8: sequence numbers are 1..n, contiguous, one per customer.
                int sequence = 1;
                for (SeedCustomer stop : stops) {
                    route.addAssignment(new RouteCustomerAssignment(stop.id(), sequence++));
                }

                batch.add(route);
                pending.add(new PendingRoute(rep, territory, day, stops, status));
            }
        }

        List<Route> saved = routeRepository.saveAll(batch);
        int assignments = saved.stream().mapToInt(r -> r.getAssignments().size()).sum();

        for (int i = 0; i < saved.size(); i++) {
            PendingRoute p = pending.get(i);
            context.routes().add(new SeedContext.SeedRoute(
                    saved.get(i).getId(), p.rep().userId(), p.territory().id(), p.date(),
                    p.stops().stream().map(SeedCustomer::id).toList(),
                    p.status().name()));
        }

        context.count("routes", saved.size());
        context.count("route stops", assignments);
        log.info("Demo routes ready: {} created with {} stops ({} completed, {} active today, "
                        + "{} planned today), {} reused from an earlier run, across {} working days",
                saved.size(), assignments, completed, active, planned, reused,
                window.workDays().size());
    }

    /*
     * Routes are deliberately NOT deleted on reset.
     *
     * A route carries no marker identifying it as seeded, so the only way to select them would be by
     * date window - which would also sweep up routes this seeder never created, and take their
     * visits and any invoices bound to those visits with them. That is precisely the "blindly delete
     * data" failure mode a reset flag must not have. Nothing is lost by keeping them: routes are
     * idempotent on (representative, date), so a re-run reuses them rather than duplicating them,
     * and the documents that hang off them - visits, invoices, van operations - are rebuilt as
     * normal.
     */

    /**
     * Closes a route that is still open on a day that is over.
     *
     * <p>Two situations reach this, and both are ordinary rather than exceptional.</p>
     *
     * <p>The first is the calendar simply moving. A route seeded as today's live ACTIVE route is
     * still ACTIVE tomorrow morning, and a rep does not come in and close yesterday's paperwork -
     * which is exactly why {@code VisitSweepJob} exists. The sweep runs at 02:00, so an application
     * restarted at nine in the morning has spent the night with the route open and would seed a new
     * day on top of it. Doing the same thing the sweep does, at seed time, makes the dataset correct
     * the moment it is written rather than correct only after the first night.</p>
     *
     * <p>The second is a database seeded by an older version of this class, which left a percentage
     * of historical routes PLANNED or ACTIVE on purpose. Those rows are invalid under rule A4 and no
     * amount of re-running would have fixed them, because routes are reused by (rep, date) rather
     * than rewritten. This converts them in place instead of deleting them, which preserves the
     * visits and invoices already hanging off them.</p>
     *
     * <p>Deliberately not a delete. A route is referenced by its visits, and those visits may be
     * referenced by invoices - so removing the row would either break a foreign key or quietly take
     * real sales with it. Advancing the status is the same thing the application would have done.</p>
     */
    private Route closeIfOverdue(Route route, LocalDate day, boolean isToday) {
        if (isToday || route.getStatus() == RouteStatus.COMPLETED) {
            return route;
        }
        RouteStatus was = route.getStatus();
        route.setStatus(RouteStatus.COMPLETED);
        Route saved = routeRepository.save(route);
        log.info("Closed overdue route id={} dated {} ({} -> COMPLETED); the visit contributor will "
                + "settle its remaining stops", saved.getId(), day, was);
        return saved;
    }

    private SeedContext.SeedRoute toSeedRoute(Route route) {
        return new SeedContext.SeedRoute(
                route.getId(), route.getRepresentativeId(), route.getTerritoryId(),
                route.getRouteDate(),
                route.getAssignments().stream()
                        .sorted(Comparator.comparingInt(RouteCustomerAssignment::getSequenceNumber))
                        .map(RouteCustomerAssignment::getCustomerId)
                        .toList(),
                route.getStatus().name());
    }

    /** A rep works a district weighted towards the strong ones, but not always the same one. */
    private SeedTerritory territoryFor(SeedContext context, SeedRep rep, Random random) {
        List<SeedTerritory> territories = context.territories();
        int totalWeight = territories.stream().mapToInt(SeedTerritory::salesWeight).sum();
        int roll = random.nextInt(Math.max(1, totalWeight));
        int cumulative = 0;
        for (SeedTerritory t : territories) {
            cumulative += t.salesWeight();
            if (roll < cumulative && !context.customersOf(t.id()).isEmpty()) {
                return t;
            }
        }
        return territories.get(Math.abs(rep.userId().intValue()) % territories.size());
    }

    /**
     * Four to six stops, drawn from the district's own customers.
     *
     * <p>Rule A6: every stop must be a customer of the route's own territory, so the pool is the
     * territory's customer list and nothing else. Drawing from the global list and hoping would
     * produce routes {@code RouteService} refuses to accept a single edit to.</p>
     */
    private List<SeedCustomer> pickStops(SeedContext context, SeedTerritory territory, Random random) {
        List<SeedCustomer> pool = new ArrayList<>(context.customersOf(territory.id()));
        if (pool.isEmpty()) {
            return List.of();
        }
        Collections.shuffle(pool, random);
        return pool.subList(0, Math.min(pool.size(), 4 + random.nextInt(3)));
    }

    private String key(Long repId, LocalDate date) {
        return repId + "@" + date;
    }

    private record PendingRoute(SeedRep rep, SeedTerritory territory, LocalDate date,
                                List<SeedCustomer> stops, RouteStatus status) {}
}
