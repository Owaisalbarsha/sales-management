package com.salesmanagement.shared.seed;

/**
 * One module's share of the demo dataset.
 *
 * <p><strong>Why the seeder is split this way.</strong> The obvious shape — a single class that
 * creates users, products, invoices and routes — cannot exist here: it would have to import nine
 * modules' internal repositories, which {@code ApplicationModules.verify()} rejects, and rightly so.
 * Instead each module keeps a contributor beside its own entities, using its own services and
 * repositories, and only this interface (in the OPEN shared kernel) crosses the boundary. Spring
 * injects the implementations by type, so no module ever names another module's seeder, and the
 * orchestrator names none of them.</p>
 *
 * <p><strong>Cross-module data flows through {@link SeedContext}</strong> as flat ids and values —
 * the same currency the public facades already deal in. A contributor reads what earlier ones
 * published and publishes what later ones will need.</p>
 *
 * <p><strong>Every contributor must be idempotent.</strong> Re-running the seeder must not create a
 * second copy of anything, so implementations look up their own business keys — phone number, SKU,
 * territory name, rep + date — and create only what is missing. This is checked by
 * {@code DemoDataSeederIdempotencyTest}.</p>
 */
public interface DemoDataContributor {

    /**
     * Position in the seeding sequence; lower runs first. Ordering follows the real foreign keys:
     * users before routes, territories before customers, products before invoice lines, and the
     * warehouse stock snapshot last of all, because it represents where the stock ended up after the
     * whole seeded history.
     *
     * @see SeedOrder
     */
    int order();

    /** Short human label for the progress log, e.g. {@code "customers"}. */
    String label();

    /** Creates this module's slice of the demo data. */
    void contribute(SeedContext context);

    /**
     * Deletes the operational rows this contributor owns inside the seeded window, so a reset run
     * can rebuild them. Default: do nothing.
     *
     * <p><strong>Master data is deliberately never deleted here.</strong> Users, territories,
     * customers and products are looked up by business key and reused, so rebuilding history does
     * not need them gone — and deleting them would take real referencing data with them. Only
     * contributors that generate dated operational documents override this.</p>
     *
     * <p>Reached only when {@code app.seed.reset=true}; see {@code DemoDataSeeder}.</p>
     */
    default void resetSeededData(SeedContext context) {
        // no-op: most contributors are check-before-create and need no teardown
    }

    /** Canonical ordering constants, so the sequence is readable in one place. */
    final class SeedOrder {
        private SeedOrder() {}

        public static final int USERS = 10;
        public static final int TERRITORIES = 20;
        public static final int CUSTOMERS = 30;
        public static final int PRODUCTS = 40;
        public static final int ROUTES = 50;
        public static final int VISITS = 60;
        public static final int VAN_OPERATIONS = 70;
        public static final int INVOICES = 80;
        public static final int TRACKING = 85;
        /** The warehouse and van snapshot is the end state of every movement above. */
        public static final int WAREHOUSE_STOCK = 95;
        /**
         * Last of all. A stock count records what a manager physically found on the shelf
         * <em>against</em> the recorded figure, so it can only be written once that recorded figure
         * exists - otherwise every variance would be measured against an empty warehouse and the
         * variance report would show the entire catalogue as missing.
         */
        public static final int STOCK_COUNTS = 98;
    }
}
