package com.salesmanagement.vanops.internal.seed;

import com.salesmanagement.shared.seed.DemoDataContributor;
import com.salesmanagement.shared.seed.SeedContext;
import com.salesmanagement.shared.seed.SeedContext.MovementClass;
import com.salesmanagement.shared.seed.SeedContext.SeedProduct;
import com.salesmanagement.shared.seed.SeedContext.SeedRep;
import com.salesmanagement.shared.seed.SeedWindow;
import com.salesmanagement.vanops.internal.entity.DemandOrder;
import com.salesmanagement.vanops.internal.entity.DemandOrderLine;
import com.salesmanagement.vanops.internal.entity.ReturnSheet;
import com.salesmanagement.vanops.internal.entity.ReturnSheetLine;
import com.salesmanagement.vanops.internal.enums.DemandOrderStatus;
import com.salesmanagement.vanops.internal.enums.ReturnSheetStatus;
import com.salesmanagement.vanops.internal.repository.DemandOrderRepository;
import com.salesmanagement.vanops.internal.repository.ReturnSheetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Morning van loading and end-of-day returns, across the seeded window.
 *
 * <h2>These two documents are the entire inventory-movement chart</h2>
 * {@code loadedToVans} comes from LOADED demand orders and {@code returnedFromVans} from COMPLETED
 * return sheets - the analytics derive both from the terminal states of these documents, and there
 * is no movement ledger behind them. So the shape of the movement trend is decided here, and
 * nowhere else.
 *
 * <h2>The status of an order describes where it is, and nothing else</h2>
 * The three states are a position in the workflow, not a quality grade:
 * {@code SUBMITTED} means "the manager asked and the warehouse can cover it", {@code ADJUSTED}
 * means "the manager asked and the warehouse had to trim at least one line", and {@code LOADED} -
 * terminal - means "the stock has physically moved onto the van". An earlier version rolled a die
 * to decide whether a trimmed order was called ADJUSTED, which made the two labels mean nothing:
 * the same order could be either, and the label disagreed with its own lines. The rule here is the
 * one the enum documents:
 *
 * <ul>
 *   <li>Past loading days - {@code LOADED}. The van went out; the shortfall, if there was one,
 *       survives in the difference between each line's requested and fulfilled quantities, which is
 *       where the fill-rate report reads it from anyway.</li>
 *   <li>Today - the orders still waiting at the loading bay, where the pre-load distinction is
 *       live: {@code ADJUSTED} <em>if and only if</em> some line was trimmed, {@code SUBMITTED}
 *       otherwise. Plus the orders for the reps already out on the road, which are LOADED.</li>
 * </ul>
 *
 * <h2>Fill rate is seeded as two quantities, never as a percentage</h2>
 * Each line records what the manager asked for and what the warehouse could actually give. The
 * reports divide the sums; this seeder never computes a rate. That distinction matters more than it
 * looks: the dashboard's fill rate is a <em>weighted</em> aggregate, so seeding "92%" directly
 * would be seeding an answer to a question the backend is supposed to answer. Weekly shortfall
 * pressure varies through the window, which gives the fill-rate trend a readable shape instead of a
 * flat 100% line.
 *
 * <h2>You cannot return what was never loaded</h2>
 * A return sheet is drawn only from the products on that rep's own load, and its quantities never
 * exceed what was fulfilled. A sheet listing stock the van never carried would be a document the
 * warehouse would reject on sight, and it would push {@code returnedFromVans} above
 * {@code loadedToVans} on the movement chart - a physically impossible reading that looks like an
 * analytics bug rather than a data one.
 *
 * <h2>Why these are written as records rather than driven through the service</h2>
 * {@code DemandOrderService.load} moves real stock: it transfers warehouse to van and enforces the
 * warehouse floor. Replaying two months of loading through it would drain the warehouse to zero
 * within weeks and then start throwing, because the seeded warehouse holds today's stock, not the
 * stock of two months ago. Historical loading is therefore recorded as the completed paperwork it
 * is, and the current van and warehouse position is set once, at the end, by the inventory
 * contributor - which reads today's loads from the seed context so the two agree by construction
 * rather than by coincidence.
 *
 * <p>Idempotent on (representative, order date) and (representative, return date).</p>
 */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class VanopsDemoData implements DemoDataContributor {

    /** Loading days per week: reps restock at the start of the week and mid-week. */
    private static final List<DayOfWeek> LOADING_DAYS = List.of(
            DayOfWeek.SUNDAY, DayOfWeek.WEDNESDAY);

    /**
     * Per-week shortfall pressure, as a percentage of lines that get trimmed. Index 0 is the first
     * week of the window. Deliberately uneven: two tight weeks, a comfortable one, easing towards
     * the present. Beyond the table the pattern repeats.
     */
    private static final int[] SHORTFALL_PRESSURE = {14, 26, 9, 33, 18, 7, 24, 12, 16};

    /** Share of today's reps whose van has already been loaded by the time the demo is viewed. */
    private static final int LOADED_BY_NOW_PERCENT = 62;

    /** Chance a load comes back with unsold stock the following day. */
    private static final int RETURN_CHANCE_PERCENT = 40;

    private final DemandOrderRepository demandOrderRepository;
    private final ReturnSheetRepository returnSheetRepository;

    @Override
    public int order() {
        return SeedOrder.VAN_OPERATIONS;
    }

    @Override
    public String label() {
        return "van operations";
    }

    @Override
    @Transactional
    public void contribute(SeedContext context) {
        SeedWindow window = context.window();
        List<SeedRep> reps = context.representatives();
        Long salesManagerId = context.staff().get("SALES_MANAGER");

        if (reps.isEmpty() || salesManagerId == null || context.products().isEmpty()) {
            log.warn("Skipping van operations: representatives, a sales manager or products missing");
            return;
        }

        Set<String> existingOrders = new HashSet<>();
        demandOrderRepository.findAll().forEach(
                d -> existingOrders.add(key(d.getRepresentativeId(), d.getOrderDate())));
        Set<String> existingReturns = new HashSet<>();
        returnSheetRepository.findAll().forEach(
                r -> existingReturns.add(key(r.getRepresentativeId(), r.getReturnDate())));

        // Aging products are excluded from loading entirely: a product that keeps leaving the
        // warehouse is not dead stock, and the aging report counts loading as movement.
        List<SeedProduct> loadable = context.products().stream()
                .filter(p -> p.movementClass() != MovementClass.AGING)
                .toList();

        List<DemandOrder> orders = new ArrayList<>();
        List<ReturnSheet> returns = new ArrayList<>();
        long requestedTotal = 0;
        long fulfilledTotal = 0;
        int loaded = 0;
        int submitted = 0;
        int adjusted = 0;

        for (LocalDate day : window.workDays()) {
            boolean isToday = window.isToday(day);
            if (!isToday && !LOADING_DAYS.contains(day.getDayOfWeek())) {
                continue;
            }

            int pressure = pressureFor(window, day);

            for (SeedRep rep : reps) {
                if (existingOrders.contains(key(rep.userId(), day))) {
                    continue;
                }
                // A rep loads on a loading day only if they are actually out that day. On a past
                // day that is a coin weighted by the roster; today everybody has an order.
                // One stream per (rep, date): the same load is generated for this rep on this
                // day on every run, whatever else is already present.
                Random random = context.randomFor("vanops", rep.userId(), day);

                if (!isToday && random.nextInt(100) >= 72) {
                    continue;
                }

                DemandOrder order = new DemandOrder(salesManagerId, rep.userId(), day);

                List<SeedProduct> basket = basketFor(loadable, random, 5 + random.nextInt(4));
                boolean trimmed = false;
                List<Fulfilled> fulfilledLines = new ArrayList<>();

                for (SeedProduct product : basket) {
                    int requested = requestedQuantity(product, random);   // rule D3: always > 0
                    int fulfilled = requested;

                    if (random.nextInt(100) < pressure) {
                        // Short-ship somewhere between a nibble and a third of the ask, never
                        // below zero and never above the ask (chk_demand_order_lines_fulfilled).
                        int shortfall = 1 + random.nextInt(Math.max(1, requested / 3));
                        fulfilled = Math.max(0, requested - shortfall);
                        trimmed = true;
                    }

                    DemandOrderLine line = new DemandOrderLine(product.id(), requested);
                    line.setFulfilledQty(fulfilled);
                    order.addLine(line);
                    fulfilledLines.add(new Fulfilled(product, fulfilled));

                    requestedTotal += requested;
                    fulfilledTotal += fulfilled;
                }

                // Rule D2. On a past day the van demonstrably went out, so the order is LOADED and
                // the pre-load distinction has been superseded. Today, the label must agree with
                // the lines: trimmed means ADJUSTED, untrimmed means SUBMITTED.
                boolean alreadyLoaded = !isToday || random.nextInt(100) < LOADED_BY_NOW_PERCENT;
                if (alreadyLoaded) {
                    order.setStatus(DemandOrderStatus.LOADED);
                    loaded++;
                } else if (trimmed) {
                    order.setStatus(DemandOrderStatus.ADJUSTED);
                    adjusted++;
                } else {
                    order.setStatus(DemandOrderStatus.SUBMITTED);
                    submitted++;
                }
                orders.add(order);

                // Today's loaded stock is what the vans are physically carrying now. The inventory
                // contributor closes the van position from this, minus what today's invoices sold.
                if (isToday && alreadyLoaded) {
                    for (Fulfilled f : fulfilledLines) {
                        if (f.quantity() > 0) {
                            context.vanLoadsToday().add(new SeedContext.SeedVanMovement(
                                    rep.userId(), f.product().id(), f.quantity()));
                        }
                    }
                }

                // Roughly two loads in five come back with unsold stock the next day. Only stock
                // this van actually received can come back, and never more of it than it received.
                LocalDate returnDate = day.plusDays(1);
                boolean returnable = alreadyLoaded
                        && !returnDate.isAfter(window.today())
                        && random.nextInt(100) < RETURN_CHANCE_PERCENT
                        && !existingReturns.contains(key(rep.userId(), returnDate));

                if (returnable) {
                    List<Fulfilled> carriedLines = fulfilledLines.stream()
                            .filter(f -> f.quantity() > 0)
                            .toList();
                    if (!carriedLines.isEmpty()) {
                        ReturnSheet sheet = new ReturnSheet(rep.userId(), returnDate);
                        // A sheet dated today has not been through the warehouse counter yet.
                        sheet.setStatus(window.isToday(returnDate)
                                ? ReturnSheetStatus.DRAFT
                                : ReturnSheetStatus.COMPLETED);

                        List<Fulfilled> shuffled = new ArrayList<>(carriedLines);
                        Collections.shuffle(shuffled, random);
                        int lineCount = Math.min(shuffled.size(), 1 + random.nextInt(3));
                        for (int i = 0; i < lineCount; i++) {
                            Fulfilled f = shuffled.get(i);
                            // A quarter of the load at most comes back, and at least one unit -
                            // chk_return_sheet_lines_quantity forbids a zero-quantity line (D5).
                            int max = Math.max(1, f.quantity() / 4);
                            sheet.addLine(new ReturnSheetLine(
                                    f.product().id(), 1 + random.nextInt(max)));
                        }
                        returns.add(sheet);
                        existingReturns.add(key(rep.userId(), returnDate));
                    }
                }
            }
        }

        demandOrderRepository.saveAll(orders);
        returnSheetRepository.saveAll(returns);

        int orderLines = orders.stream().mapToInt(o -> o.getLines().size()).sum();
        int returnLines = returns.stream().mapToInt(r -> r.getLines().size()).sum();

        context.count("demand orders", orders.size());
        context.count("demand order lines", orderLines);
        context.count("return sheets", returns.size());
        context.count("return sheet lines", returnLines);

        log.info("Demo van operations ready: {} demand orders ({} loaded, {} submitted, {} adjusted; "
                        + "{} lines), {} return sheets ({} lines); requested {} units, fulfilled {}",
                orders.size(), loaded, submitted, adjusted, orderLines,
                returns.size(), returnLines, requestedTotal, fulfilledTotal);
    }

    /**
     * Deletes the van paperwork up to and including today.
     *
     * <p><strong>The lower bound is deliberately absent.</strong> An earlier version clipped this to
     * the seed window at both ends, which was correct only while the window never moved. It is a
     * rolling window now, so anything dated before it - including everything left over from the day
     * the window was shortened - would be stranded: no future run would regenerate those dates, and
     * no future reset would look at them. The result was a database that accumulated documents the
     * seeder had produced and could no longer account for.</p>
     *
     * <p>Neither document carries a nullable column to stamp with a seed marker the way invoices and
     * visits do, so ownership is inferred from the date range. Nothing references a demand order or
     * a return sheet, so unlike a visit these cannot orphan another module's rows. The upper bound
     * at today is kept for exactly one reason: a demand order dated tomorrow is a real order a
     * manager placed for tomorrow's loading, and the seeder never creates one.</p>
     */
    @Override
    @Transactional
    public void resetSeededData(SeedContext context) {
        LocalDate to = context.window().today();

        List<DemandOrder> orders = demandOrderRepository.findAll().stream()
                .filter(d -> !d.getOrderDate().isAfter(to))
                .toList();
        List<ReturnSheet> sheets = returnSheetRepository.findAll().stream()
                .filter(r -> !r.getReturnDate().isAfter(to))
                .toList();

        demandOrderRepository.deleteAll(orders);
        returnSheetRepository.deleteAll(sheets);
        log.warn("Reset: deleted {} demand orders and {} return sheets dated on or before {}",
                orders.size(), sheets.size(), to);
    }

    /** Shortfall pressure for the week containing this day, cycling past the end of the table. */
    private int pressureFor(SeedWindow window, LocalDate day) {
        return SHORTFALL_PRESSURE[Math.floorMod(window.weekIndex(day), SHORTFALL_PRESSURE.length)];
    }

    /** Fast movers go out by the crate, slow ones by the handful. Always > 0 (rule D3). */
    private int requestedQuantity(SeedProduct product, Random random) {
        return switch (product.movementClass()) {
            case FAST -> 60 + random.nextInt(120);
            case MODERATE -> 20 + random.nextInt(50);
            case SLOW -> 5 + random.nextInt(15);
            case AGING -> 1;   // unreachable: aging lines are filtered out before loading
        };
    }

    /** A distinct set of products - rule D6 forbids the same product twice on one order. */
    private List<SeedProduct> basketFor(List<SeedProduct> pool, Random random, int size) {
        List<SeedProduct> copy = new ArrayList<>(pool);
        Collections.shuffle(copy, random);
        return copy.subList(0, Math.min(copy.size(), size));
    }

    private String key(Long repId, LocalDate date) {
        return repId + "@" + date;
    }

    /** What one line of a load actually delivered, so a return can be bounded by it. */
    private record Fulfilled(SeedProduct product, int quantity) {}
}
