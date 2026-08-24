package com.salesmanagement.inventory.internal.seed;

import com.salesmanagement.inventory.internal.entity.Product;
import com.salesmanagement.inventory.internal.entity.VanInventoryItem;
import com.salesmanagement.inventory.internal.entity.WarehouseStockItem;
import com.salesmanagement.inventory.internal.repository.ProductRepository;
import com.salesmanagement.inventory.internal.repository.VanInventoryItemRepository;
import com.salesmanagement.inventory.internal.repository.WarehouseStockItemRepository;
import com.salesmanagement.shared.seed.DemoDataContributor;
import com.salesmanagement.shared.seed.SeedContext;
import com.salesmanagement.shared.seed.SeedContext.MovementClass;
import com.salesmanagement.shared.seed.SeedContext.SeedProduct;
import com.salesmanagement.shared.seed.SeedContext.SeedVanMovement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Where the stock ended up: the current warehouse position, and what is sitting on the vans right
 * now.
 *
 * <h2>Runs late, because it is the end state of everything else</h2>
 * Two months of loading and selling happened before this contributor; the warehouse figure is what
 * remains after all of it. Trying to derive it by replaying every movement would be a different and
 * much worse thing than it sounds - the seeded history is a plausible story, not a ledger balanced
 * to the unit, and a derived warehouse would drift into negative quantities on the products that
 * sold best, which {@code chk_warehouse_stock_qty} would then reject outright. So the closing
 * warehouse position is set deliberately, which is also the only way to guarantee the stock-health
 * donut has all three of its slices.
 *
 * <h2>The van position, by contrast, is arithmetic</h2>
 * A van is a small, short-lived container: today's contents are exactly what was loaded onto it
 * this morning minus what was sold off it since, and both figures were published to the seed
 * context by the contributors that produced them. So the van rows are computed rather than
 * invented, which makes three things line up that used to be independent guesses - the van stock
 * screen, today's LOADED demand orders, and today's invoices. A rep whose van shows forty cases of
 * cola has forty because sixty were loaded and twenty were sold, and both documents are there to
 * check.
 *
 * <p>The subtraction is floored at zero rather than allowed to go negative. Seeded invoices are
 * written straight to the repository and so never actually called {@code deductVanStock}, which
 * means a rep can sell slightly more in the generated history than the generated load covered.
 * Flooring keeps {@code chk_van_inventory_qty} satisfied; a rep whose sales outran their load
 * simply ends the day at zero on that line, which is what an empty van looks like anyway.</p>
 *
 * <h2>The donut must add up, and the categories must not overlap</h2>
 * Every product is placed in exactly one bucket:
 * <ul>
 *   <li><strong>out of stock</strong> - {@code onHand == 0}</li>
 *   <li><strong>below minimum</strong> - {@code 0 < onHand < minStockLevel}</li>
 *   <li><strong>healthy</strong> - {@code onHand >= minStockLevel}</li>
 * </ul>
 * so {@code outOfStock + belowMinimum + healthy == totalSkus} holds by construction, which is the
 * invariant the inventory analytics guarantees.
 *
 * <h2>Aging is a separate dimension, and is seeded to prove it</h2>
 * Aging products keep real stock - dead stock with nothing on the shelf is not the problem the
 * report exists to find. One of them is deliberately placed below its minimum as well, so the
 * dashboard demonstrates that a product can be both aging and short without the health slices
 * double-counting it. That overlap is the exact bug the three-bucket definition was written to
 * avoid.
 *
 * <h2>Quantities are spread wide on purpose</h2>
 * Stock value is quantity times price, so a catalogue where everything holds the same quantity
 * would rank purely by price and make {@code topStockValueProducts} a re-run of the price list.
 * Fast movers are held deep and cheap, specialty lines shallow and dear.
 */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class WarehouseStockDemoData implements DemoDataContributor {

    /** Products left with nothing on the shelf. */
    private static final int OUT_OF_STOCK_TARGET = 3;

    /** Products holding some stock but under their reorder minimum. */
    private static final int BELOW_MINIMUM_TARGET = 5;

    private final ProductRepository productRepository;
    private final WarehouseStockItemRepository warehouseStockItemRepository;
    private final VanInventoryItemRepository vanInventoryItemRepository;

    @Override
    public int order() {
        return SeedOrder.WAREHOUSE_STOCK;
    }

    @Override
    public String label() {
        return "warehouse and van stock";
    }

    @Override
    @Transactional
    public void contribute(SeedContext context) {
        // Every product in the catalogue, not only the ones this seeder created. A database that
        // already held products would otherwise leave them in whatever bucket their existing stock
        // row happened to fall into, and the donut would no longer show the intended distribution.
        List<SeedProduct> products = allCatalogueProducts(context);
        if (products.isEmpty()) {
            log.warn("Skipping stock: the product catalogue is empty");
            return;
        }

        Random random = context.random("stock");

        // Health buckets are assigned to concrete products first, so the counts are exact rather
        // than the outcome of a per-product dice roll.
        List<SeedProduct> outOfStock = new ArrayList<>();
        List<SeedProduct> belowMinimum = new ArrayList<>();

        // Out of stock: never an aging line - aging must keep stock to be a meaningful finding.
        for (SeedProduct product : products) {
            if (outOfStock.size() >= OUT_OF_STOCK_TARGET) {
                break;
            }
            if (product.movementClass() == MovementClass.SLOW
                    || product.movementClass() == MovementClass.MODERATE) {
                outOfStock.add(product);
            }
        }

        // Below minimum: fast movers drawn down by a strong week, plus exactly one aging line so
        // the two dimensions are visibly independent on the dashboard.
        SeedProduct agingShort = context.productsOf(MovementClass.AGING).stream().findFirst().orElse(null);
        if (agingShort != null) {
            belowMinimum.add(agingShort);
        }
        for (SeedProduct product : products) {
            if (belowMinimum.size() >= BELOW_MINIMUM_TARGET) {
                break;
            }
            if (outOfStock.contains(product) || belowMinimum.contains(product)) {
                continue;
            }
            if (product.movementClass() == MovementClass.FAST) {
                belowMinimum.add(product);
            }
        }
        // Top up from anything left, in case the catalogue mix shifts.
        for (SeedProduct product : products) {
            if (belowMinimum.size() >= BELOW_MINIMUM_TARGET) {
                break;
            }
            if (!outOfStock.contains(product) && !belowMinimum.contains(product)) {
                belowMinimum.add(product);
            }
        }

        int written = 0;
        int healthy = 0;
        for (SeedProduct seedProduct : products) {
            Product product = productRepository.findById(seedProduct.id()).orElse(null);
            if (product == null) {
                continue;
            }

            int quantity;
            if (outOfStock.contains(seedProduct)) {
                quantity = 0;
            } else if (belowMinimum.contains(seedProduct)) {
                // Strictly between zero and the minimum; guard the minimum-of-one case.
                int max = Math.max(2, product.getMinStockLevel());
                quantity = 1 + random.nextInt(Math.max(1, max - 1));
            } else {
                quantity = healthyQuantity(seedProduct, product.getMinStockLevel(), random);
                healthy++;
            }

            WarehouseStockItem row = warehouseStockItemRepository
                    .findByProductId(product.getId())
                    .orElseGet(() -> new WarehouseStockItem(product, 0));
            row.setQuantity(quantity);
            warehouseStockItemRepository.save(row);
            written++;
        }

        int vanRows = seedVanStock(context);

        context.count("warehouse stock rows", written);
        context.count("van stock rows", vanRows);
        log.info("Demo stock ready: {} warehouse rows ({} out of stock, {} below minimum, {} healthy), "
                        + "{} van rows",
                written, outOfStock.size(), belowMinimum.size(), healthy, vanRows);
    }

    /**
     * Clears the vans of the representatives this seeder manages.
     *
     * <p>Van rows are the one part of the stock picture that is <em>derived</em> - the closing
     * position is today's load minus today's sales - and a reset deletes and rebuilds both of those
     * documents. Leaving the old rows in place would strand a balance computed from paperwork that
     * no longer exists, and because {@link #seedVanStock} only writes rows for products loaded
     * today, nothing would ever overwrite the stragglers. They accumulated instead: a re-run after a
     * few resets left vans carrying stock traceable to no demand order at all.</p>
     *
     * <p><strong>The whole table, not a filtered subset.</strong> The obvious refinement - clear
     * only the reps this seeder manages - cannot work: reset runs before any contributor has
     * populated the {@link SeedContext}, so the rep list is empty at this point. Filtering by role
     * would add nothing either, since every van row belongs to a {@code SALES_REP} by construction
     * (the FK and {@code StockService.requireSalesRep} both see to that). Reset is already
     * documented as destructive and demo-only, and a van balance is derived rather than recorded,
     * so it is the one stock figure that can be rebuilt from the documents without losing
     * information.</p>
     *
     * <p>The warehouse rows are deliberately not touched here - {@link #contribute} overwrites
     * every one of them on each run, so they are self-healing, and deleting them would leave the
     * catalogue with no stock at all if a later contributor failed.</p>
     */
    @Override
    @Transactional
    public void resetSeededData(SeedContext context) {
        long cleared = vanInventoryItemRepository.count();
        vanInventoryItemRepository.deleteAll();
        log.warn("Reset: cleared {} van stock rows; they are rebuilt from today's loads and sales",
                cleared);
    }

    /**
     * The seeded products, plus any others already in the catalogue - the latter treated as moderate
     * movers, which is the neutral assumption for a line this seeder knows nothing about.
     */
    private List<SeedProduct> allCatalogueProducts(SeedContext context) {
        List<SeedProduct> known = context.products();
        List<SeedProduct> all = new ArrayList<>(known);
        for (Product product : productRepository.findAll()) {
            boolean alreadyKnown = known.stream().anyMatch(p -> p.id().equals(product.getId()));
            if (!alreadyKnown) {
                all.add(new SeedProduct(product.getId(), product.getSku(), product.getName(),
                        product.getPrice(), product.getMinStockLevel(), MovementClass.MODERATE));
            }
        }
        return all;
    }

    /**
     * Today's van contents: loaded this morning, minus sold since, floored at zero.
     *
     * <p>Both inputs come from the seed context rather than from a fresh dice roll, so the van
     * stock screen agrees with today's LOADED demand orders and today's invoices instead of merely
     * looking plausible beside them. A line that nets to zero is written as a zero row rather than
     * skipped: the rep loaded it and sold out of it, and an absent row would misreport that as
     * "never carried".</p>
     */
    private int seedVanStock(SeedContext context) {
        Map<String, Integer> net = new LinkedHashMap<>();
        for (SeedVanMovement load : context.vanLoadsToday()) {
            net.merge(vanKey(load), load.quantity(), Integer::sum);
        }
        if (net.isEmpty()) {
            log.info("No vans were loaded today, so no van stock rows were written");
            return 0;
        }
        for (SeedVanMovement sale : context.vanSalesToday()) {
            String key = vanKey(sale);
            if (net.containsKey(key)) {
                net.merge(key, -sale.quantity(), Integer::sum);
            }
        }

        int rows = 0;
        for (Map.Entry<String, Integer> entry : net.entrySet()) {
            String[] parts = entry.getKey().split(":");
            Long representativeId = Long.valueOf(parts[0]);
            Long productId = Long.valueOf(parts[1]);
            // Floored: seeded invoices bypass deductVanStock, so sales can outrun the load.
            int quantity = Math.max(0, entry.getValue());

            Product product = productRepository.findById(productId).orElse(null);
            if (product == null) {
                continue;
            }
            VanInventoryItem row = vanInventoryItemRepository
                    .findByRepresentativeIdAndProductId(representativeId, productId)
                    .orElseGet(() -> new VanInventoryItem(representativeId, product, 0));
            row.setQuantity(quantity);
            vanInventoryItemRepository.save(row);
            rows++;
        }
        return rows;
    }

    private String vanKey(SeedVanMovement movement) {
        return movement.representativeId() + ":" + movement.productId();
    }

    /**
     * A healthy quantity, spread wide so stock value ranks on more than price alone: fast lines are
     * held several times their minimum, specialty lines only just above it.
     */
    private int healthyQuantity(SeedProduct product, int minStockLevel, Random random) {
        int floor = Math.max(minStockLevel, 1);
        return switch (product.movementClass()) {
            case FAST -> floor * 3 + random.nextInt(floor * 2 + 1);
            case MODERATE -> floor * 2 + random.nextInt(floor + 1);
            case SLOW -> floor + random.nextInt(floor / 2 + 5);
            case AGING -> floor + random.nextInt(floor + 10);
        };
    }
}
