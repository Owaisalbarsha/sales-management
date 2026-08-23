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
import com.salesmanagement.shared.seed.SeedContext.SeedRep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Where the stock ended up: the current warehouse position, and what is sitting on the vans right now.
 *
 * <h2>Runs last, because it is the end state of everything else</h2>
 * Eight months of loading and selling happened before this contributor; the warehouse figure is what
 * remains after all of it. Trying to derive it by replaying every movement would be a different and
 * much worse thing than it sounds — the seeded history is a plausible story, not a ledger balanced to
 * the unit, and a derived warehouse would drift into negative quantities on the products that sold
 * best. So the closing position is set deliberately, which is also the only way to guarantee the
 * stock-health donut has all three of its slices.
 *
 * <h2>The donut must add up, and the categories must not overlap</h2>
 * Every product is placed in exactly one bucket:
 * <ul>
 *   <li><strong>out of stock</strong> — {@code onHand == 0}</li>
 *   <li><strong>below minimum</strong> — {@code 0 < onHand < minStockLevel}</li>
 *   <li><strong>healthy</strong> — {@code onHand >= minStockLevel}</li>
 * </ul>
 * so {@code outOfStock + belowMinimum + healthy == totalSkus} holds by construction, which is the
 * invariant the inventory analytics guarantees and which a test asserts against the live figures.
 *
 * <h2>Aging is a separate dimension, and is seeded to prove it</h2>
 * Aging products keep real stock — dead stock with nothing on the shelf is not the problem the report
 * exists to find. One of them is deliberately placed below its minimum as well, so the dashboard
 * demonstrates that a product can be both aging and short without the health slices double-counting
 * it. That overlap is the exact bug the three-bucket definition was written to avoid.
 *
 * <h2>Quantities are spread wide on purpose</h2>
 * Stock value is quantity times price, so a catalogue where everything holds the same quantity would
 * rank purely by price and make {@code topStockValueProducts} a re-run of the price list. Fast movers
 * are held deep and cheap, specialty lines shallow and dear.
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

        // Out of stock: never an aging line — aging must keep stock to be a meaningful finding.
        for (SeedProduct product : products) {
            if (outOfStock.size() >= OUT_OF_STOCK_TARGET) {
                break;
            }
            if (product.movementClass() == MovementClass.SLOW
                    || product.movementClass() == MovementClass.MODERATE) {
                outOfStock.add(product);
            }
        }

        // Below minimum: fast movers drawn down by a strong month, plus exactly one aging line so
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

        int vanRows = seedVanStock(context, random);

        context.count("warehouse stock rows", written);
        context.count("van stock rows", vanRows);
        log.info("Demo stock ready: {} warehouse rows ({} out of stock, {} below minimum, {} healthy), "
                        + "{} van rows",
                written, outOfStock.size(), belowMinimum.size(), healthy, vanRows);
    }

    /**
     * The seeded products, plus any others already in the catalogue — the latter treated as moderate
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
     * Today's van contents for the reps who are out in the field, so the van-stock views and the
     * end-of-day return flow have something to work with. Only a few products each — a van is not a
     * second warehouse.
     */
    private int seedVanStock(SeedContext context, Random random) {
        List<SeedProduct> loadable = context.products().stream()
                .filter(p -> p.movementClass() == MovementClass.FAST
                        || p.movementClass() == MovementClass.MODERATE)
                .toList();
        if (loadable.isEmpty()) {
            return 0;
        }

        int rows = 0;
        for (SeedRep rep : context.representatives()) {
            for (int i = 0; i < 4; i++) {
                SeedProduct seedProduct = loadable.get(random.nextInt(loadable.size()));
                if (vanInventoryItemRepository
                        .findByRepresentativeIdAndProductId(rep.userId(), seedProduct.id())
                        .isPresent()) {
                    continue;
                }
                Product product = productRepository.findById(seedProduct.id()).orElse(null);
                if (product == null) {
                    continue;
                }
                vanInventoryItemRepository.save(new VanInventoryItem(
                        rep.userId(), product, 5 + random.nextInt(40)));
                rows++;
            }
        }
        return rows;
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
