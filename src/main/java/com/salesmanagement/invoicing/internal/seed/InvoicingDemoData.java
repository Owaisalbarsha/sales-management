package com.salesmanagement.invoicing.internal.seed;

import com.salesmanagement.invoicing.internal.entity.Invoice;
import com.salesmanagement.invoicing.internal.entity.InvoiceLineItem;
import com.salesmanagement.invoicing.internal.repository.InvoiceRepository;
import com.salesmanagement.shared.seed.DemoDataContributor;
import com.salesmanagement.shared.seed.SeedContext;
import com.salesmanagement.shared.seed.SeedContext.MovementClass;
import com.salesmanagement.shared.seed.SeedContext.SeedCustomer;
import com.salesmanagement.shared.seed.SeedContext.SeedProduct;
import com.salesmanagement.shared.seed.SeedContext.SeedRep;
import com.salesmanagement.shared.seed.SeedWindow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Eight months of sales — the dataset every sales chart on the dashboard is computed from.
 *
 * <h2>Nothing here writes an answer</h2>
 * There is no rep total, no territory ranking, no monthly figure anywhere in this class. What it
 * writes is invoices: who sold, to whom, on what day, containing what. Every number the dashboard
 * shows is then derived by the reporting module from those rows. That separation is the whole point
 * of seeding through the domain — if the ranking comes out wrong, the data is wrong, and the chart
 * is telling the truth about it.
 *
 * <h2>The four dials that shape the charts</h2>
 * <ul>
 *   <li><strong>Seasonal curve</strong> — a per-month multiplier with a genuine dip and recovery, not
 *       a rising staircase. A monotonic line is the tell-tale of generated data.</li>
 *   <li><strong>Rep weight</strong> — how often a rep is drawn, and how big their baskets run. Makes
 *       {@code topRepresentatives} a ranking.</li>
 *   <li><strong>Customer tier</strong> — supermarkets buy often and deep, corner shops rarely and
 *       shallow. Makes {@code topCustomers} and the average-invoice-value tile meaningful.</li>
 *   <li><strong>Product movement class</strong> — which lines land in a basket, and when they stop.
 *       Slow lines go quiet weeks before the end; aging lines never appear at all, so the aging
 *       report has to find them by their own rule.</li>
 * </ul>
 *
 * <h2>Statuses walk the real state machine</h2>
 * Every invoice is built as a DRAFT and then moved with {@link Invoice#markSent()},
 * {@link Invoice#approve(Long)} and {@link Invoice#reject(Long, String)} — the entity's own guarded
 * transitions, which refuse an illegal jump. Nothing is forced into a status by assignment, so the
 * seeded data cannot contain a state the application itself could not produce. The mix is spread
 * across the whole period rather than parked on today: DRAFT and REJECTED invoices from March are
 * what make the status donut a history rather than a snapshot.
 *
 * <h2>Why the ePOD-carrying sync path is not used</h2>
 * {@code InvoiceFacade.createFromOfflineSync} would take a back-dated invoice happily, but it demands
 * two staged ePOD image files per invoice and writes them to disk — some four hundred files for this
 * dataset, none of which any dashboard reads. Historical invoices are therefore written through this
 * module's own repository, with the entity enforcing the totals, the line invariants and the status
 * machine. Only the physical side effects a backfill cannot honestly reproduce — the signature
 * capture and the van stock movement — are left out.
 *
 * <p>Idempotent on {@code clientUuid}: every seeded invoice carries a deterministic
 * {@code demo-…} key, which the schema already constrains to be unique.</p>
 */
@Component
@Profile({"local", "dev"})
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class InvoicingDemoData implements DemoDataContributor {

    /** Prefix that marks an invoice as seeded, and the basis of its idempotency key. */
    private static final String SEED_UUID_PREFIX = "demo-inv-";

    /**
     * Per-month activity multiplier (percent). Deliberately not monotonic: a strong March, an April
     * dip, recovery in May, a summer peak. Beyond the table the curve repeats.
     */
    private static final int[] MONTH_INTENSITY = {100, 112, 132, 118, 128, 146, 165, 158};

    /** Invoices attempted on a working day, before the monthly multiplier. */
    private static final int BASE_INVOICES_PER_WORKDAY = 100;

    private static final List<String> REJECTION_REASONS = List.of(
            "خطأ في الكميات المسجلة",
            "العميل أعاد البضاعة بالكامل",
            "الأسعار غير مطابقة للعرض المعتمد",
            "الفاتورة مكررة");

    private final InvoiceRepository invoiceRepository;

    @Override
    public int order() {
        return SeedOrder.INVOICES;
    }

    @Override
    public String label() {
        return "invoices";
    }

    @Override
    @Transactional
    public void contribute(SeedContext context) {
        SeedWindow window = context.window();
        List<SeedRep> reps = context.representatives();
        List<SeedCustomer> customers = context.customers();

        if (reps.isEmpty() || customers.isEmpty() || context.products().isEmpty()) {
            log.warn("Skipping invoices: representatives, customers or products missing");
            return;
        }

        Random random = context.random("invoices");
        Long reviewerId = context.staff().getOrDefault("SALES_MANAGER", context.staff().get("ADMIN"));

        Set<String> existingKeys = new HashSet<>();
        invoiceRepository.findAll().forEach(i -> {
            if (i.getClientUuid() != null) {
                existingKeys.add(i.getClientUuid());
            }
        });

        // Sellable lines: aging products never appear on an invoice, which is what lets the aging
        // report discover them independently.
        List<SeedProduct> sellable = context.products().stream()
                .filter(p -> p.movementClass() != MovementClass.AGING)
                .toList();

        List<Invoice> batch = new ArrayList<>();
        int draft = 0;
        int sent = 0;
        int approved = 0;
        int rejected = 0;

        for (LocalDate day : window.workDays()) {
            boolean isToday = day.equals(window.today());
            int intensity = intensityFor(window, day);

            // Expected invoices for the day, plus a coin-flip on the fractional remainder — this is
            // what produces busy days, quiet days and the occasional day with none at all.
            int expectedTenths = (BASE_INVOICES_PER_WORKDAY * intensity) / 100;
            int invoicesToday = expectedTenths / 100
                    + (random.nextInt(100) < (expectedTenths % 100) ? 1 : 0);

            // Today always trades: the dashboard's "today" tiles must show a real figure.
            if (isToday) {
                invoicesToday = Math.max(invoicesToday, 5);
            }

            for (int n = 0; n < invoicesToday; n++) {
                String clientUuid = SEED_UUID_PREFIX + day + "-" + n;
                if (existingKeys.contains(clientUuid)) {
                    continue;
                }
                SeedRep rep = pickRep(reps, random);
                SeedCustomer customer = pickCustomer(customers, day, window, random);

                Invoice invoice = new Invoice(
                        customer.id(), rep.userId(), null, day, clientUuid);

                for (SeedProduct product : basketFor(customer, rep, sellable, day, window, random)) {
                    int quantity = quantityFor(customer, product, random);
                    BigDecimal discount = discountFor(customer, product, quantity, random);
                    invoice.addLine(new InvoiceLineItem(
                            product.id(), quantity, product.price(), discount));
                }
                if (invoice.getLines().isEmpty()) {
                    continue;   // a basket that came out empty is not an invoice
                }

                switch (statusFor(isToday, random)) {
                    case DRAFT -> draft++;
                    case SENT -> {
                        invoice.markSent();
                        sent++;
                    }
                    case APPROVED -> {
                        invoice.markSent();
                        invoice.approve(reviewerId);
                        approved++;
                    }
                    case REJECTED -> {
                        invoice.markSent();
                        invoice.reject(reviewerId,
                                REJECTION_REASONS.get(random.nextInt(REJECTION_REASONS.size())));
                        rejected++;
                    }
                }

                batch.add(invoice);
            }
        }

        invoiceRepository.saveAll(batch);
        int lines = batch.stream().mapToInt(i -> i.getLines().size()).sum();

        context.count("invoices", batch.size());
        context.count("invoice lines", lines);
        log.info("Demo invoices ready: {} created with {} lines "
                        + "({} approved, {} sent, {} draft, {} rejected)",
                batch.size(), lines, approved, sent, draft, rejected);
    }

    @Override
    @Transactional
    public void resetSeededData(SeedContext context) {
        List<Invoice> seeded = invoiceRepository.findAll().stream()
                .filter(i -> i.getClientUuid() != null
                        && i.getClientUuid().startsWith(SEED_UUID_PREFIX))
                .toList();
        invoiceRepository.deleteAll(seeded);
        log.warn("Reset: deleted {} seeded invoices", seeded.size());
    }

    // ── the four dials ────────────────────────────────────────────────────────

    private int intensityFor(SeedWindow window, LocalDate day) {
        int index = window.monthIndex(YearMonth.from(day));
        return MONTH_INTENSITY[Math.floorMod(index, MONTH_INTENSITY.length)];
    }

    /** Weighted draw, so the strong reps genuinely bill more often. */
    private SeedRep pickRep(List<SeedRep> reps, Random random) {
        int total = reps.stream().mapToInt(SeedRep::salesWeight).sum();
        int roll = random.nextInt(Math.max(1, total));
        int cumulative = 0;
        for (SeedRep rep : reps) {
            cumulative += rep.salesWeight();
            if (roll < cumulative) {
                return rep;
            }
        }
        return reps.get(reps.size() - 1);
    }

    /**
     * Weighted by tier, and skipping customers who have gone dormant by this date. Dormancy is what
     * makes the churn report answerable: a customer who bought in February and never again is a real
     * finding, and only exists if the seeder stops billing them.
     */
    private SeedCustomer pickCustomer(List<SeedCustomer> customers, LocalDate day,
                                      SeedWindow window, Random random) {
        for (int attempt = 0; attempt < 12; attempt++) {
            SeedCustomer candidate = customers.get(random.nextInt(customers.size()));
            if (candidate.dormant() && day.isAfter(dormantFrom(window, candidate))) {
                continue;
            }
            // Tier 3 passes always, tier 2 usually, tier 1 rarely — frequency by size.
            int threshold = switch (candidate.tier()) {
                case 3 -> 100;
                case 2 -> 55;
                default -> 25;
            };
            if (random.nextInt(100) < threshold) {
                return candidate;
            }
        }
        return customers.get(random.nextInt(customers.size()));
    }

    /** A dormant customer's last active day: spread over the first half of the window. */
    private LocalDate dormantFrom(SeedWindow window, SeedCustomer customer) {
        long span = java.time.temporal.ChronoUnit.DAYS.between(window.start(), window.today());
        long cutoff = span / 4 + Math.floorMod(customer.id().intValue() * 37L, Math.max(1, span / 3));
        return window.start().plusDays(cutoff);
    }

    /**
     * The basket. Size follows the customer's tier and the rep's strength; contents follow the
     * products' movement classes — fast lines dominate, slow lines appear rarely and stop selling
     * six weeks before the end of the window so the slow-moving report has genuine subjects.
     */
    private List<SeedProduct> basketFor(SeedCustomer customer, SeedRep rep, List<SeedProduct> sellable,
                                        LocalDate day, SeedWindow window, Random random) {
        int size = switch (customer.tier()) {
            case 3 -> 3 + random.nextInt(4);      // supermarket: 3–6 lines
            case 2 -> 2 + random.nextInt(3);      // retailer: 2–4
            default -> 1 + random.nextInt(2);     // corner shop: 1–2
        };
        if (rep.salesWeight() > 70 && random.nextInt(100) < 35) {
            size++;                               // the strong reps upsell
        }

        boolean slowStillSelling = day.isBefore(window.today().minusWeeks(6));

        // LinkedHashSet: the schema forbids the same product twice on one invoice.
        Set<SeedProduct> basket = new LinkedHashSet<>();
        for (int attempt = 0; attempt < size * 4 && basket.size() < size; attempt++) {
            SeedProduct candidate = sellable.get(random.nextInt(sellable.size()));
            int chance = switch (candidate.movementClass()) {
                case FAST -> 100;
                case MODERATE -> 55;
                case SLOW -> slowStillSelling ? 18 : 0;
                case AGING -> 0;
            };
            if (chance > 0 && random.nextInt(100) < chance) {
                basket.add(candidate);
            }
        }
        return new ArrayList<>(basket);
    }

    /** Bigger shops buy deeper, and fast lines move by the case. */
    private int quantityFor(SeedCustomer customer, SeedProduct product, Random random) {
        int base = switch (product.movementClass()) {
            case FAST -> 8 + random.nextInt(18);
            case MODERATE -> 3 + random.nextInt(9);
            default -> 1 + random.nextInt(4);
        };
        return switch (customer.tier()) {
            case 3 -> base * 2;
            case 2 -> base;
            default -> Math.max(1, base / 2);
        };
    }

    /**
     * An occasional line discount, capped well inside the schema's
     * {@code discount <= price * quantity} rule.
     */
    private BigDecimal discountFor(SeedCustomer customer, SeedProduct product,
                                   int quantity, Random random) {
        if (customer.tier() < 3 || random.nextInt(100) >= 18) {
            return BigDecimal.ZERO;
        }
        BigDecimal gross = product.price().multiply(BigDecimal.valueOf(quantity));
        return gross.multiply(BigDecimal.valueOf(5L * (1 + random.nextInt(2))))
                .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Status mix: roughly 62% approved, 22% sent, 9% draft, 7% rejected.
     *
     * <p><strong>Drawn per invoice, not cycled by sequence.</strong> An earlier version walked a
     * fixed 100-slot cycle to guarantee exact proportions, and it produced a subtly broken dataset:
     * because the seeder generates invoices in date order, each contiguous band of the cycle landed
     * inside one stretch of the calendar, so every draft in the year fell in April and every
     * rejection in August. The status donut looked right for the whole period and was nonsense for
     * any month. Drawing from the seeded stream costs exactness in the proportions and buys
     * independence from the date, which is what a status distribution has to have. The stream is
     * still fixed-seed, so the result is still reproducible.</p>
     *
     * <p>Today skews to realised sales: the "today" tiles read realised invoices only, and a current
     * day made mostly of drafts would show a zero on a dashboard that is supposed to be alive.</p>
     */
    private InvoiceOutcome statusFor(boolean isToday, Random random) {
        if (isToday) {
            return random.nextInt(100) < 55 ? InvoiceOutcome.APPROVED : InvoiceOutcome.SENT;
        }
        int roll = random.nextInt(100);
        if (roll < 62) {
            return InvoiceOutcome.APPROVED;
        }
        if (roll < 84) {
            return InvoiceOutcome.SENT;
        }
        return roll < 93 ? InvoiceOutcome.DRAFT : InvoiceOutcome.REJECTED;
    }

    /** Local intent enum — deliberately not the internal {@code InvoiceStatus}, which is reached
     *  only through the entity's guarded transitions. */
    private enum InvoiceOutcome { DRAFT, SENT, APPROVED, REJECTED }
}
