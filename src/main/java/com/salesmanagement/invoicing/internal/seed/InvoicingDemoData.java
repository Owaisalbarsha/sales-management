package com.salesmanagement.invoicing.internal.seed;

import com.salesmanagement.invoicing.internal.entity.EpodArtifact;
import com.salesmanagement.invoicing.internal.entity.Invoice;
import com.salesmanagement.invoicing.internal.entity.InvoiceLineItem;
import com.salesmanagement.invoicing.internal.enums.EpodArtifactType;
import com.salesmanagement.invoicing.internal.repository.InvoiceRepository;
import com.salesmanagement.invoicing.internal.service.EpodStorageService;
import com.salesmanagement.shared.seed.DemoDataContributor;
import com.salesmanagement.shared.seed.SeedContext;
import com.salesmanagement.shared.seed.SeedContext.MovementClass;
import com.salesmanagement.shared.seed.SeedContext.SeedCustomer;
import com.salesmanagement.shared.seed.SeedContext.SeedProduct;
import com.salesmanagement.shared.seed.SeedContext.SeedRep;
import com.salesmanagement.shared.seed.SeedContext.SeedVisit;
import com.salesmanagement.shared.seed.SeedWindow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Two months of sales - the dataset every sales chart on the dashboard is computed from.
 *
 * <h2>Nothing here writes an answer</h2>
 * There is no rep total, no territory ranking, no monthly figure anywhere in this class. What it
 * writes is invoices: who sold, to whom, on what day, containing what. Every number the dashboard
 * shows is then derived by the reporting module from those rows. That separation is the whole point
 * of seeding through the domain - if the ranking comes out wrong, the data is wrong, and the chart
 * is telling the truth about it.
 *
 * <h2>Most invoices are raised against the visit that produced them</h2>
 * A sale in this business happens at a stop on a route. So the primary generator here is not a
 * date loop at all: it walks the <em>completed visits</em> the visit contributor published and asks
 * whether that call resulted in an order. The invoice then inherits its customer, its
 * representative and its date from the visit, which is the only way to satisfy D13 -
 * {@code InvoiceService.validateVisitIfPresent} rejects an invoice whose {@code visitId} points at
 * a visit belonging to a different customer or a different rep, and a seeder writing through the
 * repository would sail straight past that check into data the application would never have
 * accepted. Deriving the three fields from the visit makes the mismatch unrepresentable.
 *
 * <p>A minority of invoices are deliberately left {@code visitId = null}: an ad-hoc sale, a phone
 * order, a delivery outside the route. D13 permits it explicitly, and having both shapes in the
 * data is what proves the nullable column is handled on every screen that renders it.</p>
 *
 * <h2>Submitted invoices carry real proof of delivery</h2>
 * Both ePOD artifact types are mandatory from SENT onwards ({@code requireBothEpodTypes}, D16), and
 * every invoice this seeder marks SENT gets both - with a hash computed by the production
 * {@link EpodStorageService#hash} over the real file bytes and the frozen invoice total, exactly as
 * {@code InvoiceService.submit} computes it. A DRAFT gets none, because artifacts are attached at
 * the submit transition and a draft has not reached it.
 *
 * <p><strong>The image bytes are shared between invoices, and that is deliberate.</strong> Writing
 * a distinct signature and photo per invoice would put a couple of thousand files on disk, and
 * because {@code EpodStorageService.store} is write-once it would strand every one of them on each
 * reset run. Two fixture files are written once instead and every artifact points at them. The
 * hash is still per-invoice - it folds in the invoice id, customer, total and capture metadata -
 * so the fingerprint is a genuine one and the download endpoint serves a genuine image. What is
 * not reproduced is that each real signature is a different drawing, which no report reads.</p>
 *
 * <h2>The dials that shape the charts</h2>
 * <ul>
 *   <li><strong>Weekly curve</strong> - a per-week multiplier with a genuine dip and recovery, not
 *       a rising staircase. A monotonic line is the tell-tale of generated data.</li>
 *   <li><strong>Rep weight</strong> - how often a rep converts a call, and how big their baskets
 *       run. Makes {@code topRepresentatives} a ranking.</li>
 *   <li><strong>Customer tier</strong> - supermarkets buy on nearly every call and buy deep, corner
 *       shops rarely and shallow. Makes {@code topCustomers} and the average-invoice-value tile
 *       meaningful.</li>
 *   <li><strong>Product movement class</strong> - which lines land in a basket, and when they stop.
 *       Slow lines go quiet weeks before the end; aging lines never appear at all, so the aging
 *       report has to find them by its own rule.</li>
 * </ul>
 *
 * <h2>Statuses walk the real state machine</h2>
 * Every invoice is built as a DRAFT and then moved with {@link Invoice#markSent()},
 * {@link Invoice#approve(Long)} and {@link Invoice#reject(Long, String)} - the entity's own guarded
 * transitions, which refuse an illegal jump. Nothing is forced into a status by assignment, so the
 * seeded data cannot contain a state the application itself could not produce: APPROVED and
 * REJECTED are only ever reached by passing through SENT, a rejection always carries its mandatory
 * reason, and only a reviewed invoice has a reviewer.
 *
 * <p>Idempotent on {@code clientUuid}: every seeded invoice carries a deterministic
 * {@code demo-inv-} key, which the schema already constrains to be unique.</p>
 */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true")
@Slf4j
public class InvoicingDemoData implements DemoDataContributor {

    /** Prefix that marks an invoice as seeded, and the basis of its idempotency key. */
    private static final String SEED_UUID_PREFIX = "demo-inv-";

    /**
     * Per-week activity multiplier (percent), indexed from the start of the window. Deliberately
     * not monotonic: a soft opening, a strong third week, a dip, a recovery into the present.
     * Beyond the table the curve repeats, so a longer window still varies.
     */
    private static final int[] WEEK_INTENSITY = {88, 104, 126, 97, 112, 134, 118, 141, 128};

    /** Share of invoices raised outside a route call: phone orders, deliveries, walk-ins. */
    private static final int AD_HOC_SHARE_PERCENT = 14;

    private static final List<String> REJECTION_REASONS = List.of(
            "خطأ في الكميات المسجلة",
            "العميل أعاد البضاعة بالكامل",
            "الأسعار غير مطابقة للعرض المعتمد",
            "الفاتورة مكررة");

    private final InvoiceRepository invoiceRepository;
    private final EpodStorageService epodStorage;
    private final Path storageDir;

    public InvoicingDemoData(InvoiceRepository invoiceRepository,
                             EpodStorageService epodStorage,
                             @Value("${epod.storage-dir:./epod-storage}") String storageDir) {
        this.invoiceRepository = invoiceRepository;
        this.epodStorage = epodStorage;
        this.storageDir = Path.of(storageDir);
    }

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

        Long reviewerId = context.staff().getOrDefault("SALES_MANAGER", context.staff().get("ADMIN"));

        Set<String> existingKeys = new HashSet<>();
        invoiceRepository.findAll().forEach(i -> {
            if (i.getClientUuid() != null) {
                existingKeys.add(i.getClientUuid());
            }
        });

        Map<Long, SeedCustomer> customerById = new HashMap<>();
        customers.forEach(c -> customerById.put(c.id(), c));
        Map<Long, SeedRep> repById = new HashMap<>();
        reps.forEach(r -> repById.put(r.userId(), r));

        // Sellable lines: aging products never appear on an invoice, which is what lets the aging
        // report discover them independently rather than being told.
        List<SeedProduct> sellable = context.products().stream()
                .filter(p -> p.movementClass() != MovementClass.AGING)
                .toList();

        EpodFixture fixture = prepareEpodFixture();

        List<Draft> drafts = new ArrayList<>();

        // -- 1. Sales that came out of a real call -----------------------------
        for (SeedVisit visit : context.completedVisits()) {
            SeedCustomer customer = customerById.get(visit.customerId());
            SeedRep rep = repById.get(visit.representativeId());
            if (customer == null || rep == null) {
                continue;   // a visit against master data this run did not publish
            }
            // One stream per call. Whether this visit converted is a property of the visit, not
            // of how many visits happened to be skipped before it in the loop.
            if (!ordersOnThisCall(customer, rep, visit.date(), window,
                    context.randomFor("invoices", "call", visit.id()))) {
                continue;
            }
            drafts.add(new Draft(
                    SEED_UUID_PREFIX + "v" + visit.id(),
                    customer, rep, visit.date(), visit.id(),
                    // The proof is captured as the rep leaves the shop.
                    visit.checkOutTime() != null ? visit.checkOutTime() : visit.checkInTime()));
        }

        // -- 2. Ad-hoc sales, off-route (D13 permits a null visit) -------------
        int adHocTarget = (drafts.size() * AD_HOC_SHARE_PERCENT) / 100;
        List<LocalDate> workDays = window.workDays();
        for (int n = 0; n < adHocTarget && !workDays.isEmpty(); n++) {
            Random random = context.randomFor("invoices", "adhoc", n);
            LocalDate day = workDays.get(random.nextInt(workDays.size()));
            SeedRep rep = pickRep(reps, random);
            SeedCustomer customer = pickCustomer(customers, day, window, random);
            if (customer == null) {
                continue;
            }
            drafts.add(new Draft(
                    SEED_UUID_PREFIX + "a" + day + "-" + n,
                    customer, rep, day, null, afternoonOf(day, random)));
        }

        // -- 3. Build them ------------------------------------------------------
        List<Invoice> batch = new ArrayList<>();
        List<Draft> accepted = new ArrayList<>();
        int draftCount = 0;
        int sent = 0;
        int approved = 0;
        int rejected = 0;

        for (Draft d : drafts) {
            if (!existingKeys.add(d.clientUuid())) {
                continue;   // already seeded by an earlier run
            }
            // One stream per invoice, keyed on its own idempotency key: the basket, quantities,
            // discounts and status of this invoice do not depend on which other invoices this run
            // happened to skip.
            Random random = context.randomFor("invoices", d.clientUuid());

            Invoice invoice = new Invoice(
                    d.customer().id(), d.rep().userId(), d.visitId(), d.date(), d.clientUuid());

            for (SeedProduct product : basketFor(d.customer(), d.rep(), sellable, d.date(), window, random)) {
                int quantity = quantityFor(d.customer(), product, random);
                BigDecimal discount = discountFor(d.customer(), product, quantity, random);
                invoice.addLine(new InvoiceLineItem(product.id(), quantity, product.price(), discount));
            }
            if (invoice.getLines().isEmpty()) {
                continue;   // a basket that came out empty is not an invoice
            }

            Outcome outcome = statusFor(window.isToday(d.date()), random);
            if (outcome != Outcome.DRAFT) {
                // Freeze the total before hashing, exactly as InvoiceService.submit does: the
                // fingerprint binds the proof to the amount the customer signed for.
                invoice.recomputeTotal();
                attachEpod(invoice, d, fixture, random);
                invoice.markSent();
            }
            switch (outcome) {
                case DRAFT -> draftCount++;
                case SENT -> sent++;
                case APPROVED -> {
                    invoice.approve(reviewerId);
                    approved++;
                }
                case REJECTED -> {
                    invoice.reject(reviewerId,
                            REJECTION_REASONS.get(random.nextInt(REJECTION_REASONS.size())));
                    rejected++;
                }
            }

            batch.add(invoice);
            accepted.add(d);
        }

        List<Invoice> saved = invoiceRepository.saveAll(batch);

        // Today's realised sales came off the reps' vans. Publishing them lets the inventory
        // contributor close the van position as loaded-minus-sold instead of inventing a number.
        for (int i = 0; i < saved.size(); i++) {
            Invoice invoice = saved.get(i);
            if (!window.isToday(accepted.get(i).date()) || invoice.isDraft()) {
                continue;
            }
            for (InvoiceLineItem line : invoice.getLines()) {
                context.vanSalesToday().add(new SeedContext.SeedVanMovement(
                        invoice.getRepresentativeId(), line.getProductId(), line.getQuantity()));
            }
        }

        int lines = saved.stream().mapToInt(i -> i.getLines().size()).sum();
        int withVisit = (int) accepted.stream().filter(a -> a.visitId() != null).count();

        context.count("invoices", saved.size());
        context.count("invoice lines", lines);
        context.count("epod artifacts", (sent + approved + rejected) * 2);
        log.info("Demo invoices ready: {} created with {} lines ({} approved, {} sent, {} draft, "
                        + "{} rejected); {} raised against a visit, {} ad-hoc",
                saved.size(), lines, approved, sent, draftCount, rejected,
                withVisit, saved.size() - withVisit);
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

    // -- ePOD ------------------------------------------------------------------

    /**
     * Attaches both mandatory artifact types, hashed over the frozen total.
     *
     * <p>Attached while the invoice is still DRAFT because {@link Invoice#addEpodArtifact} refuses
     * to touch a submitted invoice - the same ordering {@code InvoiceService.submit} uses: capture,
     * then flip.</p>
     */
    private void attachEpod(Invoice invoice, Draft draft, EpodFixture fixture, Random random) {
        BigDecimal latitude = coordinate(33.49 + random.nextDouble() * 0.07);
        BigDecimal longitude = coordinate(36.22 + random.nextDouble() * 0.11);
        BigDecimal frozenTotal = invoice.getTotalAmount();

        for (EpodArtifactType type : EpodArtifactType.values()) {
            byte[] bytes = fixture.bytesOf(type);
            String url = fixture.urlOf(type);
            // The invoice has no id yet (it is saved in one batch at the end), so the hash folds in
            // the clientUuid rather than the surrogate key. Same construction, same guarantee: the
            // fingerprint is unique to this invoice, its customer, its total and its capture
            // metadata, and breaks if any of them is edited afterwards.
            String hash = hashFor(draft.clientUuid(), bytes, invoice.getCustomerId(),
                    frozenTotal, draft.capturedAt(), latitude, longitude);
            invoice.addEpodArtifact(new EpodArtifact(
                    type, url, hash, latitude, longitude, draft.capturedAt()));
        }
    }

    /**
     * The production hash, with the seeded invoice's stable business key standing in for the
     * not-yet-assigned surrogate id. Delegates to {@link EpodStorageService#hash} for the customer,
     * total and metadata folding, then re-folds the business key so two invoices for the same
     * customer and amount on the same second still fingerprint differently.
     */
    private String hashFor(String clientUuid, byte[] bytes, Long customerId, BigDecimal total,
                           Instant capturedAt, BigDecimal latitude, BigDecimal longitude) {
        String base = epodStorage.hash(bytes, 0L, customerId, total, capturedAt, latitude, longitude);
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update((clientUuid + "|" + base).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Writes the two shared proof images, once, and returns their bytes and paths.
     *
     * <p>The files are minimal but genuine PNGs: a 1x1 image, valid enough that
     * {@code Files.probeContentType} reports {@code image/png} and the download endpoint serves it
     * as an image rather than an octet stream.</p>
     */
    private EpodFixture prepareEpodFixture() {
        Map<EpodArtifactType, byte[]> bytes = new HashMap<>();
        Map<EpodArtifactType, String> urls = new HashMap<>();
        try {
            Files.createDirectories(storageDir);
            for (EpodArtifactType type : EpodArtifactType.values()) {
                byte[] content = onePixelPng(type);
                Path target = storageDir.resolve("demo_epod_" + type.name().toLowerCase() + ".png");
                if (!Files.isRegularFile(target)) {
                    Files.write(target, content);
                }
                bytes.put(type, content);
                urls.put(type, target.toString());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not prepare demo ePOD fixture files", e);
        }
        return new EpodFixture(bytes, urls);
    }

    /**
     * A valid 1x1 PNG. The two artifact types get a different pixel colour so their bytes - and
     * therefore their hashes - genuinely differ, rather than the signature and the photo of one
     * invoice fingerprinting identically.
     */
    private byte[] onePixelPng(EpodArtifactType type) {
        // Pre-encoded 1x1 PNGs: one black pixel (signature ink), one grey (photo).
        String base64 = type == EpodArtifactType.SIGNATURE
                ? "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
                : "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";
        return java.util.Base64.getDecoder().decode(base64);
    }

    // -- the dials -------------------------------------------------------------

    private int intensityFor(SeedWindow window, LocalDate day) {
        return WEEK_INTENSITY[Math.floorMod(window.weekIndex(day), WEEK_INTENSITY.length)];
    }

    /**
     * Whether this call turned into an order. Tier decides the base rate, the rep's strength nudges
     * it, and the weekly curve moves the whole market up and down together - which is what makes
     * the daily sales trend have a shape rather than a level.
     */
    private boolean ordersOnThisCall(SeedCustomer customer, SeedRep rep, LocalDate day,
                                     SeedWindow window, Random random) {
        if (customer.dormant() && day.isAfter(dormantFrom(window, customer))) {
            return false;
        }
        int base = switch (customer.tier()) {
            case 3 -> 88;
            case 2 -> 66;
            default -> 42;
        };
        int adjusted = base
                + (rep.salesWeight() - 60) / 8          // a strong closer converts more calls
                + (intensityFor(window, day) - 110) / 4;
        return random.nextInt(100) < Math.max(10, Math.min(96, adjusted));
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
     * makes the churn report answerable: a customer who bought in the first fortnight and never
     * again is a real finding, and only exists if the seeder stops billing them.
     */
    private SeedCustomer pickCustomer(List<SeedCustomer> customers, LocalDate day,
                                      SeedWindow window, Random random) {
        for (int attempt = 0; attempt < 12; attempt++) {
            SeedCustomer candidate = customers.get(random.nextInt(customers.size()));
            if (candidate.dormant() && day.isAfter(dormantFrom(window, candidate))) {
                continue;
            }
            int threshold = switch (candidate.tier()) {
                case 3 -> 100;
                case 2 -> 55;
                default -> 25;
            };
            if (random.nextInt(100) < threshold) {
                return candidate;
            }
        }
        return null;
    }

    /** A dormant customer's last active day: spread over the first half of the window. */
    private LocalDate dormantFrom(SeedWindow window, SeedCustomer customer) {
        long span = java.time.temporal.ChronoUnit.DAYS.between(window.start(), window.today());
        long cutoff = span / 5 + Math.floorMod(customer.id() * 37L, Math.max(1, span / 3));
        return window.start().plusDays(cutoff);
    }

    /**
     * The basket. Size follows the customer's tier and the rep's strength; contents follow the
     * products' movement classes - fast lines dominate, slow lines appear rarely and stop selling
     * a fortnight before the end of the window so the slow-moving report has genuine subjects.
     */
    private List<SeedProduct> basketFor(SeedCustomer customer, SeedRep rep, List<SeedProduct> sellable,
                                        LocalDate day, SeedWindow window, Random random) {
        int size = switch (customer.tier()) {
            case 3 -> 3 + random.nextInt(4);      // supermarket: 3-6 lines
            case 2 -> 2 + random.nextInt(3);      // retailer: 2-4
            default -> 1 + random.nextInt(2);     // corner shop: 1-2
        };
        if (rep.salesWeight() > 70 && random.nextInt(100) < 35) {
            size++;                               // the strong reps upsell
        }

        boolean slowStillSelling = day.isBefore(window.today().minusWeeks(2));

        // LinkedHashSet: the schema forbids the same product twice on one invoice (C7).
        Set<SeedProduct> basket = new LinkedHashSet<>();
        for (int attempt = 0; attempt < size * 4 && basket.size() < size; attempt++) {
            SeedProduct candidate = sellable.get(random.nextInt(sellable.size()));
            int chance = switch (candidate.movementClass()) {
                case FAST -> 100;
                case MODERATE -> 55;
                case SLOW -> slowStillSelling ? 22 : 0;
                case AGING -> 0;
            };
            if (chance > 0 && random.nextInt(100) < chance) {
                basket.add(candidate);
            }
        }
        return new ArrayList<>(basket);
    }

    /** Bigger shops buy deeper, and fast lines move by the case. Always at least one (C6). */
    private int quantityFor(SeedCustomer customer, SeedProduct product, Random random) {
        int base = switch (product.movementClass()) {
            case FAST -> 8 + random.nextInt(18);
            case MODERATE -> 3 + random.nextInt(9);
            default -> 1 + random.nextInt(4);
        };
        int scaled = switch (customer.tier()) {
            case 3 -> base * 2;
            case 2 -> base;
            default -> base / 2;
        };
        return Math.max(1, scaled);
    }

    /**
     * An occasional line discount, capped well inside the schema's
     * {@code discount <= price * quantity} rule (C6).
     */
    private BigDecimal discountFor(SeedCustomer customer, SeedProduct product,
                                   int quantity, Random random) {
        if (customer.tier() < 3 || random.nextInt(100) >= 18) {
            return BigDecimal.ZERO;
        }
        BigDecimal gross = product.price().multiply(BigDecimal.valueOf(quantity));
        return gross.multiply(BigDecimal.valueOf(5L * (1 + random.nextInt(2))))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    /**
     * Status mix: roughly 60% approved, 22% sent, 8% draft, 10% rejected.
     *
     * <p><strong>Drawn per invoice, not cycled by sequence.</strong> An earlier version walked a
     * fixed 100-slot cycle to guarantee exact proportions, and it produced a subtly broken dataset:
     * because the seeder generates invoices in date order, each contiguous band of the cycle landed
     * inside one stretch of the calendar, so every draft in the window fell in one week and every
     * rejection in another. The status donut looked right for the whole period and was nonsense for
     * any single week. Drawing from the seeded stream costs exactness in the proportions and buys
     * independence from the date, which is what a status distribution has to have. The stream is
     * still fixed-seed, so the result is still reproducible.</p>
     *
     * <p>Today skews to realised sales, with a couple of drafts a rep is still typing: the "today"
     * tiles read realised invoices only, and a current day made mostly of drafts would show a zero
     * on a dashboard that is supposed to be alive.</p>
     */
    private Outcome statusFor(boolean isToday, Random random) {
        int roll = random.nextInt(100);
        if (isToday) {
            if (roll < 44) {
                return Outcome.APPROVED;
            }
            return roll < 88 ? Outcome.SENT : Outcome.DRAFT;
        }
        if (roll < 60) {
            return Outcome.APPROVED;
        }
        if (roll < 82) {
            return Outcome.SENT;
        }
        return roll < 90 ? Outcome.DRAFT : Outcome.REJECTED;
    }

    /** Mid-afternoon on the given business day - when an off-route delivery typically lands. */
    private Instant afternoonOf(LocalDate day, Random random) {
        return day.atTime(LocalTime.of(13 + random.nextInt(4), random.nextInt(60)))
                .atZone(SeedWindow.BUSINESS_ZONE)
                .toInstant();
    }

    private BigDecimal coordinate(double raw) {
        return BigDecimal.valueOf(raw).setScale(6, RoundingMode.HALF_UP);
    }

    /** One invoice to build, with everything it inherits from its originating call. */
    private record Draft(String clientUuid, SeedCustomer customer, SeedRep rep,
                         LocalDate date, Long visitId, Instant capturedAt) {}

    /** The shared proof images: bytes for hashing, paths for the artifact url column. */
    private record EpodFixture(Map<EpodArtifactType, byte[]> bytes,
                               Map<EpodArtifactType, String> urls) {

        byte[] bytesOf(EpodArtifactType type) {
            return bytes.get(type);
        }

        String urlOf(EpodArtifactType type) {
            return urls.get(type);
        }
    }

    /** Local intent enum - deliberately not the internal {@code InvoiceStatus}, which is reached
     *  only through the entity's guarded transitions. */
    private enum Outcome { DRAFT, SENT, APPROVED, REJECTED }
}
