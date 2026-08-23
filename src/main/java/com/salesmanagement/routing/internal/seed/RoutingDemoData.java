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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Daily field routes across the whole seeded period — three representatives out on every working day
 * (Sunday to Thursday), each with a district and an ordered list of stops.
 *
 * <h2>Why routes are persisted directly rather than through {@code RouteService}</h2>
 * {@code RouteService.create} is built for planning tomorrow's work: it validates the date against
 * the present and drives the PLANNED → ACTIVE → COMPLETED lifecycle forward in real time. Replaying
 * eight months of finished field work through it would mean asking it to accept dates it is designed
 * to reject, and then walking every route through a status machine whose transitions are meant to be
 * triggered by a rep with a phone. So historical routes are written as the finished records they are,
 * using the module's own entity and repository — inside the owning module, where that is legitimate,
 * and through the entity's own {@code addAssignment} so the parent/child invariant still holds.
 *
 * <p><strong>Today is different.</strong> Today's routes are seeded in the states a real working day
 * would show mid-afternoon: some ACTIVE (which is what the dashboard's {@code activeRoutesToday} tile
 * counts), one still PLANNED, so the tile reads as a live figure rather than a constant.</p>
 *
 * <h2>Outcome variety is engineered upstream of the visits</h2>
 * Every route gets a deterministic "quality" from the seeded random stream, which the visit seeder
 * reads back to decide how many stops were completed, missed, or never reached. Uniformly perfect
 * routes would make {@code routeOutcomes} a single-slice donut and the completion rate a flat 100%.
 *
 * <p>Idempotent on (representative, date) — the seeder plans at most one route per rep per day, so
 * that pair identifies a seeded route even though the schema no longer constrains it.</p>
 */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class RoutingDemoData implements DemoDataContributor {

    /** Reps in the field on a normal working day. */
    private static final int REPS_PER_DAY = 3;

    private static final List<String> ROUTE_PREFIXES = List.of(
            "جولة", "خط سير", "مسار");

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

        Random random = context.random("routes");

        // One lookup for the whole window; the alternative is a query per day.
        Map<String, Route> existing = new HashMap<>();
        routeRepository.findWithAssignmentsInRange(window.start(), window.exclusiveEnd(), null)
                .forEach(r -> existing.putIfAbsent(key(r.getRepresentativeId(), r.getRouteDate()), r));

        List<Route> batch = new ArrayList<>();
        List<PendingRoute> pending = new ArrayList<>();
        int repCursor = 0;
        int reused = 0;

        for (LocalDate day : window.workDays()) {
            boolean isToday = day.equals(window.today());

            for (int slot = 0; slot < REPS_PER_DAY; slot++) {
                SeedRep rep = reps.get(repCursor++ % reps.size());

                // Already planned by an earlier run: publish it to the context anyway. The visit and
                // tracking contributors build on this list, so a re-run that silently omitted routes
                // it had created previously would leave them permanently without visits.
                Route already = existing.get(key(rep.userId(), day));
                if (already != null) {
                    context.routes().add(new SeedContext.SeedRoute(
                            already.getId(), already.getRepresentativeId(), already.getTerritoryId(),
                            already.getRouteDate(),
                            already.getAssignments().stream()
                                    .sorted(java.util.Comparator.comparingInt(
                                            RouteCustomerAssignment::getSequenceNumber))
                                    .map(RouteCustomerAssignment::getCustomerId)
                                    .toList(),
                            already.getStatus().name()));
                    reused++;
                    continue;
                }

                SeedTerritory territory = territoryFor(context, rep, random);
                List<SeedCustomer> stops = pickStops(context, territory, random);
                if (stops.isEmpty()) {
                    continue;
                }

                RouteStatus status = statusFor(day, isToday, slot, random);
                String name = ROUTE_PREFIXES.get(random.nextInt(ROUTE_PREFIXES.size()))
                        + " " + territory.name() + " - " + day;

                Route route = new Route(rep.userId(), territory.id(), name, day);
                route.setStatus(status);
                route.setOptimized(random.nextInt(100) < 60);

                int sequence = 1;
                for (SeedCustomer stop : stops) {
                    route.addAssignment(new RouteCustomerAssignment(stop.id(), sequence++));
                }

                batch.add(route);
                pending.add(new PendingRoute(route, rep, territory, day, stops, status));
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
        log.info("Demo routes ready: {} created with {} planned stops, {} reused from an earlier run, "
                        + "across {} working days",
                saved.size(), assignments, reused, window.workDays().size());
    }

    /*
     * Routes are deliberately NOT deleted on reset.
     *
     * A route carries no marker identifying it as seeded, so the only way to select them would be by
     * date window — which would also sweep up routes this seeder never created, and take their visits
     * and any invoices bound to those visits with them. That is precisely the "blindly delete data"
     * failure mode a reset flag must not have. Nothing is lost by keeping them: routes are idempotent
     * on (representative, date), so a re-run reuses them rather than duplicating them, and the
     * documents that hang off them — visits, invoices, van operations — are rebuilt as normal.
     */

    /**
     * Today's routes carry live statuses so the dashboard tile has something to count; historical
     * routes are overwhelmingly COMPLETED, with a residue of routes that were never closed — which is
     * exactly the operational untidiness the route report exists to surface.
     */
    private RouteStatus statusFor(LocalDate day, boolean isToday, int slot, Random random) {
        if (isToday) {
            return slot < 2 ? RouteStatus.ACTIVE : RouteStatus.PLANNED;
        }
        int roll = random.nextInt(100);
        if (roll < 92) {
            return RouteStatus.COMPLETED;
        }
        return roll < 97 ? RouteStatus.ACTIVE : RouteStatus.PLANNED;
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

    /** Four to six stops, drawn from the district's own customers. */
    private List<SeedCustomer> pickStops(SeedContext context, SeedTerritory territory, Random random) {
        List<SeedCustomer> pool = new ArrayList<>(context.customersOf(territory.id()));
        if (pool.isEmpty()) {
            return List.of();
        }
        java.util.Collections.shuffle(pool, random);
        return pool.subList(0, Math.min(pool.size(), 4 + random.nextInt(3)));
    }

    private String key(Long repId, LocalDate date) {
        return repId + "@" + date;
    }

    private record PendingRoute(Route route, SeedRep rep, SeedTerritory territory, LocalDate date,
                                List<SeedCustomer> stops, RouteStatus status) {}
}
