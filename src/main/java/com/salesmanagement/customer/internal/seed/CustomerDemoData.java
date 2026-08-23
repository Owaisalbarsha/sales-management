package com.salesmanagement.customer.internal.seed;

import com.salesmanagement.customer.internal.dto.CreateCustomerRequest;
import com.salesmanagement.customer.internal.enums.CustomerCategory;
import com.salesmanagement.customer.internal.repository.CustomerRepository;
import com.salesmanagement.customer.internal.service.CustomerService;
import com.salesmanagement.shared.seed.DemoDataContributor;
import com.salesmanagement.shared.seed.SeedContext;
import com.salesmanagement.shared.seed.SeedContext.SeedTerritory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The customer base: Arabic shop names spread across the ten territories, each with a real Damascus
 * coordinate and a purchasing profile.
 *
 * <h2>Three things are engineered here, everything else follows</h2>
 * <ul>
 *   <li><strong>Territory spread.</strong> A territory's customer count follows its sales weight, so
 *       the strong districts have more shops to bill and the territory ranking has a reason to come
 *       out the way it does.</li>
 *   <li><strong>Tiers.</strong> Every customer is a small shop, a mid retailer or a supermarket.
 *       The tier drives both how often the invoice seeder picks them and how large the basket is —
 *       which is what makes {@code topCustomers} a real ranking instead of eight equal bars.</li>
 *   <li><strong>Dormancy.</strong> Roughly one customer in seven stops buying part-way through the
 *       year. Without them the dormant-customer report is a permanently empty table, and an empty
 *       report is indistinguishable from a broken one.</li>
 * </ul>
 *
 * <p><strong>Coordinates are per-district, jittered.</strong> Each territory has a real centre in
 * Damascus and customers are scattered a few hundred metres around it from the deterministic random
 * stream. Identical coordinates for every customer would make the live map a single stacked pin, and
 * {@code 0,0} would put the whole company in the Atlantic.</p>
 *
 * <p>Idempotent on customer name, which is unique enough within this fabricated dataset to serve as
 * the business key (the schema does not constrain it, so the lookup is explicit).</p>
 */
@Component
@Profile({"local", "dev"})
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class CustomerDemoData implements DemoDataContributor {

    /** Approximate district centres in Damascus — lat, lng. */
    private static final Map<String, double[]> DISTRICT_CENTRES = Map.ofEntries(
            Map.entry("المالكي", new double[]{33.5155, 36.2790}),
            Map.entry("المزة", new double[]{33.4950, 36.2420}),
            Map.entry("أبو رمانة", new double[]{33.5185, 36.2860}),
            Map.entry("كفرسوسة", new double[]{33.4880, 36.2640}),
            Map.entry("الميدان", new double[]{33.4880, 36.2980}),
            Map.entry("باب توما", new double[]{33.5115, 36.3160}),
            Map.entry("مشروع دمر", new double[]{33.5310, 36.2210}),
            Map.entry("ركن الدين", new double[]{33.5340, 36.2900}),
            Map.entry("القابون", new double[]{33.5420, 36.3300}),
            Map.entry("ضاحية قدسيا", new double[]{33.5600, 36.2100})
    );

    /** Shop-name building blocks; combined per territory into distinct, natural business names. */
    private static final List<String> SUPERMARKET_NAMES = List.of(
            "سوبر ماركت الياسمين", "سوبر ماركت النخيل", "سوبر ماركت البركة",
            "سوبر ماركت النور", "سوبر ماركت السعادة", "سوبر ماركت القمة",
            "سوبر ماركت الفردوس", "سوبر ماركت الشام الكبير");

    private static final List<String> MARKET_NAMES = List.of(
            "ماركت الشام", "ماركت الفيحاء", "ماركت الأصيل", "ماركت الربيع",
            "ماركت المدينة", "ماركت الواحة", "ماركت الأمانة", "ماركت الرحمة",
            "ماركت الزهراء", "ماركت السلام");

    private static final List<String> SHOP_NAMES = List.of(
            "بقالية الندى", "متجر الروضة", "بقالية أبو خالد", "متجر الأمانة",
            "بقالية الجوهرة", "متجر النرجس", "بقالية الوفاء", "متجر الصفا",
            "بقالية الخير", "متجر الهدى", "بقالية النسيم", "متجر البستان",
            "بقالية الرياض", "متجر الكرامة", "بقالية العائلة", "متجر المروج");

    private static final List<String> STREETS = List.of(
            "شارع الجلاء", "شارع بغداد", "شارع الثورة", "شارع فارس الخوري",
            "شارع النصر", "شارع خالد بن الوليد", "شارع الحمراء", "شارع المتنبي",
            "شارع 29 أيار", "شارع الملك فيصل");

    private final CustomerService customerService;
    private final CustomerRepository customerRepository;

    @Override
    public int order() {
        return SeedOrder.CUSTOMERS;
    }

    @Override
    public String label() {
        return "customers";
    }

    @Override
    @Transactional
    public void contribute(SeedContext context) {
        Random random = context.random("customers");
        Map<String, Long> existingByName = new LinkedHashMap<>();
        customerRepository.findAll().forEach(c -> existingByName.putIfAbsent(c.getName(), c.getId()));

        int created = 0;
        int supermarketCursor = 0;
        int marketCursor = 0;
        int shopCursor = 0;
        int dormantCursor = 0;

        for (SeedTerritory territory : context.territories()) {
            // 3–7 customers per territory, scaled by commercial strength.
            int customerCount = 3 + (territory.salesWeight() * 4) / 100;

            for (int i = 0; i < customerCount; i++) {
                // Every third customer is a supermarket, every second of the rest a mid retailer.
                int tier = (i % 3 == 0) ? 3 : (i % 3 == 1) ? 2 : 1;

                String baseName = switch (tier) {
                    case 3 -> SUPERMARKET_NAMES.get(supermarketCursor++ % SUPERMARKET_NAMES.size());
                    case 2 -> MARKET_NAMES.get(marketCursor++ % MARKET_NAMES.size());
                    default -> SHOP_NAMES.get(shopCursor++ % SHOP_NAMES.size());
                };
                // Qualify with the district so names stay distinct across ten territories.
                String name = baseName + " - " + territory.name();

                // Roughly one in seven goes quiet later in the year.
                boolean dormant = (++dormantCursor % 7 == 0);

                Long id = existingByName.get(name);
                if (id == null) {
                    double[] centre = DISTRICT_CENTRES.getOrDefault(
                            territory.name(), new double[]{33.5138, 36.2765});
                    id = create(territory, name, tier, centre, random);
                    created++;
                }

                context.customers().add(new SeedContext.SeedCustomer(
                        id, territory.id(), name, tier, dormant));
            }
        }

        context.count("customers", created);
        log.info("Demo customers ready: {} total across {} territories, {} newly created",
                context.customers().size(), context.territories().size(), created);
    }

    private Long create(SeedTerritory territory, String name, int tier,
                        double[] centre, Random random) {
        // ±0.006° ≈ ±650 m — visibly scattered on a map, still inside the district.
        BigDecimal latitude = coordinate(centre[0] + (random.nextDouble() - 0.5) * 0.012);
        BigDecimal longitude = coordinate(centre[1] + (random.nextDouble() - 0.5) * 0.012);

        CustomerCategory category = switch (tier) {
            case 3 -> CustomerCategory.SUPERMARKET;
            case 2 -> CustomerCategory.RETAIL;
            default -> CustomerCategory.WHOLESALE;
        };

        String street = STREETS.get(random.nextInt(STREETS.size()));
        String address = territory.name() + " - " + street + " - بناء رقم " + (random.nextInt(80) + 1);
        String phone = "+9639" + (30 + random.nextInt(69)) + String.format("%06d", random.nextInt(1_000_000));

        return customerService.create(new CreateCustomerRequest(
                territory.id(), name, address, phone, latitude, longitude, category)).id();
    }

    private BigDecimal coordinate(double raw) {
        return BigDecimal.valueOf(raw).setScale(6, RoundingMode.HALF_UP);
    }
}
