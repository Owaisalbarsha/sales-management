package com.salesmanagement.inventory.internal.seed;

import com.salesmanagement.inventory.internal.dto.CreateProductRequest;
import com.salesmanagement.inventory.internal.entity.Product;
import com.salesmanagement.inventory.internal.repository.ProductRepository;
import com.salesmanagement.inventory.internal.service.ProductService;
import com.salesmanagement.shared.seed.DemoDataContributor;
import com.salesmanagement.shared.seed.SeedContext;
import com.salesmanagement.shared.seed.SeedContext.MovementClass;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The product catalogue: 28 Arabic FMCG lines at realistic Syrian pound prices.
 *
 * <h2>Prices and minimums are chosen, not generated</h2>
 * A random price generator produces a catalogue where a bottle of water and a sack of rice cost
 * about the same, which makes every downstream chart subtly nonsensical — the stock-value ranking in
 * particular, since it is price times quantity. So each line carries a plausible retail price and a
 * minimum stock level appropriate to how fast it turns: a fast-moving soft drink is reordered at 100
 * units, a slow specialty item at 15.
 *
 * <h2>Movement classes</h2>
 * Each product is tagged {@link MovementClass}, which the invoice and van-operation seeders read to
 * decide how often it appears in a basket. The classes are seeding instructions only — no report
 * reads them. {@code fastMovingProducts} and the aging report each re-derive their own verdict from
 * the sales and loading history that results, which is the point: the analytics must be able to
 * disagree with the intent if the history does not support it.
 *
 * <p>The {@link MovementClass#AGING} lines are the interesting case. They are deliberately excluded
 * from every invoice and every demand order inside the aging threshold, so the aging report finds
 * them by their own rule — absence of movement — rather than by a flag.</p>
 *
 * <p>Idempotent on SKU, the schema's unique business key for a product.</p>
 */
@Component
@Profile({"local", "dev"})
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class ProductDemoData implements DemoDataContributor {

    private record ProductSpec(String name, String sku, String barcode, String price,
                               String unit, int minStockLevel, MovementClass movementClass) {}

    private static final List<ProductSpec> PRODUCTS = List.of(
            // ── fast movers: the everyday basket, on most invoices all year ──
            new ProductSpec("كوكا كولا ٣٣٠ مل", "BEV-001", "6281036001001", "1500", "علبة", 120, MovementClass.FAST),
            new ProductSpec("بيبسي ٣٣٠ مل", "BEV-002", "6281036001002", "1400", "علبة", 120, MovementClass.FAST),
            new ProductSpec("مياه معدنية ١.٥ لتر", "BEV-010", "6281036001003", "700", "عبوة", 200, MovementClass.FAST),
            new ProductSpec("مياه معدنية ٥٠٠ مل", "BEV-011", "6281036001004", "400", "عبوة", 250, MovementClass.FAST),
            new ProductSpec("نسكافيه ٣×١ ظرف", "BEV-003", "6281036001005", "500", "ظرف", 300, MovementClass.FAST),
            new ProductSpec("شيبس ليز ١٦٠ غ", "SNK-001", "6281036001006", "2000", "كيس", 90, MovementClass.FAST),
            new ProductSpec("خبز عربي كبير", "FOD-010", "6281036001007", "1200", "ربطة", 100, MovementClass.FAST),

            // ── moderate movers: regular but unremarkable ──
            new ProductSpec("عصير برتقال ١ لتر", "BEV-012", "6281036001008", "3500", "عبوة", 60, MovementClass.MODERATE),
            new ProductSpec("عصير تفاح ١ لتر", "BEV-013", "6281036001009", "3500", "عبوة", 60, MovementClass.MODERATE),
            new ProductSpec("عصير راني مانجا ١ ل", "BEV-004", "6281036001010", "3500", "عبوة", 60, MovementClass.MODERATE),
            new ProductSpec("حليب نيدو ٩٠٠ غ", "DAI-002", "6281036001011", "18000", "علبة", 35, MovementClass.MODERATE),
            new ProductSpec("لبن بلدي ١ كغ", "DAI-003", "6281036001012", "4500", "عبوة", 50, MovementClass.MODERATE),
            new ProductSpec("أرز مصري ١ كغ", "FOD-011", "6281036001013", "6500", "كيس", 70, MovementClass.MODERATE),
            new ProductSpec("سكر ١ كغ", "FOD-012", "6281036001014", "5000", "كيس", 80, MovementClass.MODERATE),
            new ProductSpec("زيت دوار الشمس ١.٥ ل", "FOD-002", "6281036001015", "15000", "عبوة", 40, MovementClass.MODERATE),
            new ProductSpec("معكرونة ٥٠٠ غ", "FOD-013", "6281036001016", "2800", "كيس", 90, MovementClass.MODERATE),
            new ProductSpec("اندومي ٥ أظرف", "FOD-003", "6281036001017", "2500", "عبوة", 100, MovementClass.MODERATE),
            new ProductSpec("طحينة الدرة ٩٠٠ غ", "FOD-001", "6281036001018", "8000", "علبة", 30, MovementClass.MODERATE),
            new ProductSpec("مسحوق تايد ٣ كغ", "CLN-001", "6281036001019", "12000", "عبوة", 45, MovementClass.MODERATE),
            new ProductSpec("سائل جلي فيري ١ ل", "CLN-002", "6281036001020", "6000", "عبوة", 55, MovementClass.MODERATE),
            new ProductSpec("مناديل ورقية علبة", "CLN-003", "6281036001021", "2200", "علبة", 80, MovementClass.MODERATE),

            // ── slow movers: occasional sales, quiet lately ──
            new ProductSpec("بسكويت بالشوكولا", "SNK-002", "6281036001022", "1800", "كيس", 40, MovementClass.SLOW),
            new ProductSpec("تونة معلبة ١٦٠ غ", "FOD-014", "6281036001023", "7500", "علبة", 25, MovementClass.SLOW),
            new ProductSpec("حمص معلب ٤٠٠ غ", "FOD-015", "6281036001024", "3200", "علبة", 30, MovementClass.SLOW),
            new ProductSpec("مربى فراولة ٤٥٠ غ", "FOD-016", "6281036001025", "5500", "مرطبان", 20, MovementClass.SLOW),

            // ── aging: stocked, but untouched for longer than the aging threshold ──
            new ProductSpec("شاي أسود ٥٠٠ غ", "BEV-020", "6281036001026", "9000", "علبة", 25, MovementClass.AGING),
            new ProductSpec("قهوة مطحونة ٢٠٠ غ", "BEV-021", "6281036001027", "11000", "علبة", 20, MovementClass.AGING),
            new ProductSpec("عدس أحمر ١ كغ", "FOD-017", "6281036001028", "7000", "كيس", 20, MovementClass.AGING)
    );

    private final ProductService productService;
    private final ProductRepository productRepository;

    @Override
    public int order() {
        return SeedOrder.PRODUCTS;
    }

    @Override
    public String label() {
        return "products";
    }

    @Override
    @Transactional
    public void contribute(SeedContext context) {
        Map<String, Product> existingBySku = new LinkedHashMap<>();
        productRepository.findAll().forEach(p -> existingBySku.putIfAbsent(p.getSku(), p));

        int created = 0;
        for (ProductSpec spec : PRODUCTS) {
            Product existing = existingBySku.get(spec.sku());
            Long id;
            BigDecimal price;
            int minStockLevel;

            if (existing != null) {
                // Adopt the row already there; the SQL demo seed uses the same SKUs for its 15 lines.
                id = existing.getId();
                price = existing.getPrice();
                minStockLevel = existing.getMinStockLevel();
            } else {
                // The barcode is decoration here, and it is globally unique in the schema. If some
                // other catalogue row already owns this one, seed the product without a barcode
                // rather than aborting: the seeder does not own the database it runs against, and a
                // demo product is still perfectly usable without a barcode.
                String barcode = productRepository.existsByBarcode(spec.barcode())
                        ? null
                        : spec.barcode();

                var response = productService.create(new CreateProductRequest(
                        spec.name(), spec.sku(), barcode, new BigDecimal(spec.price()),
                        spec.unit(), spec.minStockLevel()));
                id = response.id();
                price = response.price();
                minStockLevel = response.minStockLevel();
                created++;
            }

            context.products().add(new SeedContext.SeedProduct(
                    id, spec.sku(), spec.name(), price, minStockLevel, spec.movementClass()));
        }

        context.count("products", created);
        log.info("Demo products ready: {} total ({} fast, {} moderate, {} slow, {} aging), {} newly created",
                context.products().size(),
                context.productsOf(MovementClass.FAST).size(),
                context.productsOf(MovementClass.MODERATE).size(),
                context.productsOf(MovementClass.SLOW).size(),
                context.productsOf(MovementClass.AGING).size(),
                created);
    }
}
