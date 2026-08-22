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

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Morning van loading and end-of-day returns, across the whole seeded period.
 *
 * <h2>These two documents are the entire inventory-movement chart</h2>
 * {@code loadedToVans} comes from LOADED demand orders and {@code returnedFromVans} from COMPLETED
 * return sheets — the analytics derive both from the terminal states of these documents, and there is
 * no movement ledger behind them. So the shape of the movement trend is decided here, and nowhere
 * else.
 *
 * <h2>Fill rate is seeded as two quantities, never as a percentage</h2>
 * Each line records what the manager asked for and what the warehouse could actually give. The
 * reports divide the sums; this seeder never computes a rate. That distinction matters more than it
 * looks: the dashboard's fill rate is a <em>weighted</em> aggregate, so seeding "92%" directly would
 * be seeding an answer to a question the backend is supposed to answer. Monthly shortfall pressure
 * varies through the year — a constrained spring, a comfortable summer — which is what gives the
 * fill-rate trend a readable shape instead of a flat 100% line.
 *
 * <h2>Why these are written as records rather than driven through the service</h2>
 * {@code DemandOrderService.load} moves real stock: it transfers warehouse → van and enforces the
 * warehouse floor. Replaying eight months of loading through it would drain the warehouse to zero
 * within weeks and then start throwing, because the seeded warehouse holds today's stock, not
 * January's. Historical loading is therefore recorded as the completed paperwork it is, and the
 * current warehouse position is set once, at the end, by the inventory contributor. Today's van
 * contents are seeded there too, so the two agree.
 *
 * <p>Idempotent on (representative, order date) and (representative, return date).</p>
 */
@Component
@Profile({"local", "dev"})
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class VanopsDemoData implements DemoDataContributor {

    /** Loading days per week: reps restock at the start of the week and mid-week. */
    private static final List<java.time.DayOfWeek> LOADING_DAYS = List.of(
            java.time.DayOfWeek.SUNDAY, java.time.DayOfWeek.WEDNESDAY);

    /**
     * Per-month shortfall pressure, as a percentage of lines that get trimmed. Index 0 is the first
     * month of the window. Deliberately uneven: April and July were tight, June was comfortable.
     * Beyond the table the pattern repeats, so a seeder run in a later year still varies.
     */
    private static final int[] SHORTFALL_PRESSURE = {12, 22, 9, 36, 17, 6, 28, 24};

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

        Random random = context.random("vanops");

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
        int repCursor = 0;

        for (LocalDate day : window.workDays()) {
            if (!LOADING_DAYS.contains(day.getDayOfWeek())) {
                continue;
            }

            int pressure = pressureFor(window, day);

            // Three reps load on each loading day, rotating through the roster.
            for (int slot = 0; slot < 3; slot++) {
                SeedRep rep = reps.get(repCursor++ % reps.size());
                if (existingOrders.contains(key(rep.userId(), day))) {
                    continue;
                }

                DemandOrder order = new DemandOrder(salesManagerId, rep.userId(), day);
                order.setStatus(DemandOrderStatus.LOADED);

                List<SeedProduct> basket = basketFor(loadable, random, 5 + random.nextInt(4));
                boolean trimmed = false;

                for (SeedProduct product : basket) {
                    int requested = requestedQuantity(product, random);
                    int fulfilled = requested;

                    if (random.nextInt(100) < pressure) {
                        // Short-ship somewhere between a nibble and a third of the ask.
                        int shortfall = 1 + random.nextInt(Math.max(1, requested / 3));
                        fulfilled = Math.max(0, requested - shortfall);
                        trimmed = true;
                    }

                    DemandOrderLine line = new DemandOrderLine(product.id(), requested);
                    line.setFulfilledQty(fulfilled);
                    order.addLine(line);

                    requestedTotal += requested;
                    fulfilledTotal += fulfilled;
                }

                // An order the warehouse had to trim is ADJUSTED before it is LOADED in the real
                // workflow; the status the analytics care about is the terminal one either way.
                if (trimmed && random.nextInt(100) < 30) {
                    order.setStatus(DemandOrderStatus.ADJUSTED);
                }
                orders.add(order);

                // Roughly two loads in five come back with unsold stock a day later.
                LocalDate returnDate = day.plusDays(1);
                if (!returnDate.isAfter(window.today())
                        && random.nextInt(100) < 40
                        && !existingReturns.contains(key(rep.userId(), returnDate))) {

                    ReturnSheet sheet = new ReturnSheet(rep.userId(), returnDate);
                    sheet.setStatus(ReturnSheetStatus.COMPLETED);
                    for (SeedProduct product : basketFor(basket, random, 1 + random.nextInt(3))) {
                        sheet.addLine(new ReturnSheetLine(product.id(), 1 + random.nextInt(12)));
                    }
                    returns.add(sheet);
                    existingReturns.add(key(rep.userId(), returnDate));
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

        log.info("Demo van operations ready: {} demand orders ({} lines), {} return sheets ({} lines); "
                        + "requested {} units, fulfilled {}",
                orders.size(), orderLines, returns.size(), returnLines, requestedTotal, fulfilledTotal);
    }

    @Override
    @Transactional
    public void resetSeededData(SeedContext context) {
        LocalDate from = context.window().start();
        LocalDate to = context.window().today();

        List<DemandOrder> orders = demandOrderRepository.findAll().stream()
                .filter(d -> !d.getOrderDate().isBefore(from) && !d.getOrderDate().isAfter(to))
                .toList();
        List<ReturnSheet> sheets = returnSheetRepository.findAll().stream()
                .filter(r -> !r.getReturnDate().isBefore(from) && !r.getReturnDate().isAfter(to))
                .toList();

        demandOrderRepository.deleteAll(orders);
        returnSheetRepository.deleteAll(sheets);
        log.warn("Reset: deleted {} demand orders and {} return sheets inside the seeded window",
                orders.size(), sheets.size());
    }

    /** Shortfall pressure for the month containing this day, cycling past the end of the table. */
    private int pressureFor(SeedWindow window, LocalDate day) {
        int index = window.monthIndex(YearMonth.from(day));
        return SHORTFALL_PRESSURE[Math.floorMod(index, SHORTFALL_PRESSURE.length)];
    }

    /** Fast movers go out by the crate, slow ones by the handful. */
    private int requestedQuantity(SeedProduct product, Random random) {
        return switch (product.movementClass()) {
            case FAST -> 60 + random.nextInt(120);
            case MODERATE -> 20 + random.nextInt(50);
            case SLOW -> 5 + random.nextInt(15);
            case AGING -> 1;   // unreachable: aging lines are filtered out before loading
        };
    }

    private List<SeedProduct> basketFor(List<SeedProduct> pool, Random random, int size) {
        List<SeedProduct> copy = new ArrayList<>(pool);
        java.util.Collections.shuffle(copy, random);
        return copy.subList(0, Math.min(copy.size(), size));
    }

    private String key(Long repId, LocalDate date) {
        return repId + "@" + date;
    }
}
