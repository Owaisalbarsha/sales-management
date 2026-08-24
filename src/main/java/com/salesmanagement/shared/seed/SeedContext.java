package com.salesmanagement.shared.seed;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * The blackboard the demo-data contributors share: the seeded window, a deterministic randomness
 * source, the ids each module produced, and the running row counts for the final summary.
 *
 * <p><strong>Why ids travel as plain values.</strong> The customer seeder needs territory ids, the
 * invoice seeder needs customer, product, visit and rep ids. Passing entities would mean every
 * module importing every other module's internals - the exact coupling the architecture forbids. So
 * contributors publish flat records here (id plus the few attributes a downstream contributor needs
 * to make a realistic choice), which is the same shape the modules' public facades already use to
 * talk to each other.</p>
 *
 * <p><strong>The registries are how business rules survive a module boundary.</strong> An invoice
 * may only cite a visit whose customer and rep match its own (D13), and the invoicing module cannot
 * read the visit table to check. So the visit seeder publishes {@link SeedVisit} rows and the
 * invoice seeder builds from those, which makes the mismatch unrepresentable rather than merely
 * unlikely. The same holds for van stock: {@link SeedVanMovement} carries what was loaded and what
 * was sold, so the closing van position is arithmetic rather than a guess.</p>
 *
 * <p><strong>Randomness is seeded and per-purpose.</strong> {@link #random(String)} derives a stream
 * from a fixed seed and the purpose name, so every run produces byte-identical data and - crucially -
 * adding a contributor does not shift the numbers every other contributor draws. A shared global
 * {@code Random} would make the whole dataset change whenever anyone inserted one extra call.</p>
 */
public final class SeedContext {

    /**
     * Fixed seed. Documented and deliberate: a demo dashboard whose figures move on every restart is
     * useless for spotting a real regression, and impossible to write assertions against.
     */
    public static final long RANDOM_SEED = 20260101L;

    private final SeedWindow window;
    private final boolean reset;

    private final List<SeedRep> representatives = new ArrayList<>();
    private final List<SeedTerritory> territories = new ArrayList<>();
    private final List<SeedCustomer> customers = new ArrayList<>();
    private final List<SeedProduct> products = new ArrayList<>();
    private final List<SeedRoute> routes = new ArrayList<>();
    private final List<SeedVisit> visits = new ArrayList<>();
    private final List<SeedVanMovement> vanLoadsToday = new ArrayList<>();
    private final List<SeedVanMovement> vanSalesToday = new ArrayList<>();
    private final Map<String, Long> staff = new LinkedHashMap<>();
    private final Map<String, Integer> counts = new LinkedHashMap<>();

    public SeedContext(SeedWindow window, boolean reset) {
        this.window = window;
        this.reset = reset;
    }

    public SeedWindow window() {
        return window;
    }

    /**
     * Whether this run should first delete the operational rows it owns inside the window.
     * Guarded by an explicit property and never touches master data - see
     * {@link DemoDataContributor#resetSeededData(SeedContext)}.
     */
    public boolean isReset() {
        return reset;
    }

    /** A reproducible stream for one purpose; independent of every other purpose's stream. */
    public Random random(String purpose) {
        return new Random(RANDOM_SEED * 31 + purpose.hashCode());
    }

    /**
     * A reproducible stream for one <em>specific thing</em> - one route, one invoice, one day's
     * load - identified by the discriminators.
     *
     * <p><strong>This is what makes a contributor genuinely idempotent, and the plain
     * {@link #random(String)} is not enough on its own.</strong> A single sequential stream per
     * contributor is only reproducible when the contributor makes exactly the same sequence of
     * draws every run. It does not: a re-run finds rows already in the database and skips them
     * <em>before</em> drawing, so every skip shifts the whole remaining sequence by however many
     * numbers the skipped item would have consumed. The route contributor demonstrated this
     * plainly - a second run over an already-seeded database created thirty-five routes that the
     * first run had decided against, because the "is this rep out today" coin came up differently
     * once the stream had shifted underneath it. Left alone it converges on seeding everything,
     * which is the opposite of idempotent.</p>
     *
     * <p>Deriving the stream from the thing's own identity removes the coupling entirely. The
     * decision made about a given route on a given date is the same decision on every run, in any
     * order, whatever else is or is not already present.</p>
     *
     * @param purpose        the contributor's own namespace, e.g. {@code "routes"}
     * @param discriminators what identifies this one item, e.g. rep id and date
     */
    public Random randomFor(String purpose, Object... discriminators) {
        long seed = RANDOM_SEED * 31 + purpose.hashCode();
        for (Object discriminator : discriminators) {
            // A large odd multiplier so two different discriminator lists cannot collide simply by
            // being permutations of one another.
            seed = seed * 1_000_003L + Objects.hashCode(discriminator);
        }
        return new Random(seed);
    }

    // -- registries -----------------------------------------------------------

    public List<SeedRep> representatives() {
        return representatives;
    }

    public List<SeedTerritory> territories() {
        return territories;
    }

    public List<SeedCustomer> customers() {
        return customers;
    }

    public List<SeedProduct> products() {
        return products;
    }

    public List<SeedRoute> routes() {
        return routes;
    }

    /**
     * Every visit this run created, published for the invoice seeder.
     *
     * <p>An invoice that names a visit must agree with it on customer and representative, and the
     * invoicing module has no way to verify that for itself - {@code VisitFacade} would reject the
     * mismatch at runtime, but a seeder writing through the repository bypasses the facade. Reading
     * the invoice's customer and rep straight off the visit removes the possibility.</p>
     */
    public List<SeedVisit> visits() {
        return visits;
    }

    /** Units moved warehouse to van today, per rep and product. Written by the vanops seeder. */
    public List<SeedVanMovement> vanLoadsToday() {
        return vanLoadsToday;
    }

    /** Units sold off the van today, per rep and product. Written by the invoice seeder. */
    public List<SeedVanMovement> vanSalesToday() {
        return vanSalesToday;
    }

    /** Non-rep staff ids by logical role key, e.g. {@code "SALES_MANAGER"}, {@code "ADMIN"}. */
    public Map<String, Long> staff() {
        return staff;
    }

    /** Customers of one territory, in registry order. */
    public List<SeedCustomer> customersOf(Long territoryId) {
        return customers.stream().filter(c -> c.territoryId().equals(territoryId)).toList();
    }

    /** Products in one movement class. */
    public List<SeedProduct> productsOf(MovementClass movementClass) {
        return products.stream().filter(p -> p.movementClass() == movementClass).toList();
    }

    /** Completed visits, the only ones an invoice may be raised against. */
    public List<SeedVisit> completedVisits() {
        return visits.stream().filter(SeedVisit::isCompleted).toList();
    }

    // -- summary counters -----------------------------------------------------

    /** Records how many rows of one kind were created, for the closing log line. */
    public void count(String label, int created) {
        counts.merge(label, created, Integer::sum);
    }

    public Map<String, Integer> counts() {
        return counts;
    }

    // -- flat carriers --------------------------------------------------------

    /**
     * A sales representative.
     *
     * @param salesWeight relative share of sales this rep should end up with (higher = more, larger
     *                    invoices). Drives the {@code topRepresentatives} ranking without any
     *                    ranking figure ever being written down.
     */
    public record SeedRep(Long userId, String name, int salesWeight) {}

    /**
     * A sales territory.
     *
     * @param salesWeight relative commercial strength; decides how often its customers are billed.
     */
    public record SeedTerritory(Long id, String name, int salesWeight) {}

    /**
     * A customer.
     *
     * @param tier    purchase profile: 1 = small shop (few, small invoices), 2 = mid retailer,
     *                3 = supermarket (frequent, large invoices)
     * @param dormant if true the customer stops buying part-way through the window, so the
     *                dormant-customer report has genuine subjects instead of an empty table
     */
    public record SeedCustomer(Long id, Long territoryId, String name, int tier, boolean dormant) {}

    /** A product, with the sales behaviour the historical invoices should give it. */
    public record SeedProduct(Long id, String sku, String name, BigDecimal price,
                              int minStockLevel, MovementClass movementClass) {}

    /** A seeded route and the customers assigned to it, published for the visit seeder. */
    public record SeedRoute(Long id, Long representativeId, Long territoryId, LocalDate date,
                            List<Long> customerIds, String status) {

        /** A finished route: every stop must carry a terminal visit. */
        public boolean isCompleted() {
            return "COMPLETED".equals(status);
        }
    }

    /**
     * A seeded visit, published so the invoice seeder can bind a sale to the stop that produced it.
     *
     * @param status one of {@code COMPLETED}, {@code IN_PROGRESS}, {@code MISSED}
     */
    public record SeedVisit(Long id, Long routeId, Long customerId, Long representativeId,
                            LocalDate date, String status, Instant checkInTime, Instant checkOutTime) {

        /** Only a completed visit is a sale opportunity that has finished happening. */
        public boolean isCompleted() {
            return "COMPLETED".equals(status);
        }
    }

    /**
     * Units of one product moving on or off one rep's van today.
     *
     * <p>Loads and sales are recorded separately rather than as a running balance so the closing
     * position can be computed once, at the end, by the contributor that owns the stock tables -
     * and so a sale can never drive the balance negative mid-stream, which the
     * {@code chk_van_inventory_qty} constraint would reject.</p>
     */
    public record SeedVanMovement(Long representativeId, Long productId, int quantity) {}

    /**
     * How often a product sells across the seeded window. Not a label the reports read - it only
     * steers how many invoice lines a product lands on, and the fast/slow and aging reports then
     * derive their own verdicts from that history.
     */
    public enum MovementClass {
        /** On a large share of invoices, throughout the window. Drives {@code fastMovingProducts}. */
        FAST,
        /** Sells regularly but unremarkably. */
        MODERATE,
        /** Occasional sales, and none in recent weeks. */
        SLOW,
        /**
         * Stocked but not sold or loaded for longer than the aging threshold, so the aging report
         * finds it on its own. Deliberately still has on-hand - dead stock with zero quantity is
         * not the operational problem the report exists to surface.
         */
        AGING
    }
}
