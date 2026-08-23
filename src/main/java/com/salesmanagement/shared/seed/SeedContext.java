package com.salesmanagement.shared.seed;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The blackboard the demo-data contributors share: the seeded window, a deterministic randomness
 * source, the ids each module produced, and the running row counts for the final summary.
 *
 * <p><strong>Why ids travel as plain values.</strong> The customer seeder needs territory ids, the
 * invoice seeder needs customer, product and rep ids. Passing entities would mean every module
 * importing every other module's internals — the exact coupling the architecture forbids. So
 * contributors publish flat records here (id plus the few attributes a downstream contributor needs
 * to make a realistic choice), which is the same shape the modules' public facades already use to
 * talk to each other.</p>
 *
 * <p><strong>Randomness is seeded and per-purpose.</strong> {@link #random(String)} derives a stream
 * from a fixed seed and the purpose name, so every run produces byte-identical data and — crucially —
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
     * Guarded twice upstream (dev/local profile AND an explicit property) and never touches master
     * data — see {@link DemoDataContributor#resetSeededData(SeedContext)}.
     */
    public boolean isReset() {
        return reset;
    }

    /** A reproducible stream for one purpose; independent of every other purpose's stream. */
    public Random random(String purpose) {
        return new Random(RANDOM_SEED * 31 + purpose.hashCode());
    }

    // ── registries ────────────────────────────────────────────────────────────

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

    // ── summary counters ──────────────────────────────────────────────────────

    /** Records how many rows of one kind were created, for the closing log line. */
    public void count(String label, int created) {
        counts.merge(label, created, Integer::sum);
    }

    public Map<String, Integer> counts() {
        return counts;
    }

    // ── flat carriers ─────────────────────────────────────────────────────────

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
     * @param dormant if true the customer stops buying part-way through the year, so the
     *                dormant-customer report has genuine subjects instead of an empty table
     */
    public record SeedCustomer(Long id, Long territoryId, String name, int tier, boolean dormant) {}

    /** A product, with the sales behaviour the historical invoices should give it. */
    public record SeedProduct(Long id, String sku, String name, BigDecimal price,
                              int minStockLevel, MovementClass movementClass) {}

    /** A seeded route and the customers assigned to it, published for the visit seeder. */
    public record SeedRoute(Long id, Long representativeId, Long territoryId, LocalDate date,
                            List<Long> customerIds, String status) {}

    /**
     * How often a product sells across the seeded year. Not a label the reports read — it only
     * steers how many invoice lines a product lands on, and the fast/slow and aging reports then
     * derive their own verdicts from that history.
     */
    public enum MovementClass {
        /** On a large share of invoices, all year. Drives {@code fastMovingProducts}. */
        FAST,
        /** Sells regularly but unremarkably. */
        MODERATE,
        /** Occasional sales, and none in recent weeks. */
        SLOW,
        /**
         * Stocked but not sold or loaded for longer than the aging threshold, so the aging report
         * finds it on its own. Deliberately still has on-hand — dead stock with zero quantity is
         * not the operational problem the report exists to surface.
         */
        AGING
    }
}
