package com.salesmanagement.reporting.internal.service;

import com.salesmanagement.customer.api.CustomerFacade;
import com.salesmanagement.identity.api.UserFacade;
import com.salesmanagement.invoicing.api.InvoiceFacade;
import com.salesmanagement.invoicing.api.InvoiceSummary;
import com.salesmanagement.invoicing.api.RepSalesAggregate;
import com.salesmanagement.reporting.internal.dto.SalesReportDtos.InvoiceRow;
import com.salesmanagement.reporting.internal.dto.SalesReportDtos.RepSalesRow;
import com.salesmanagement.reporting.internal.support.DateRangeResolver.DateRange;
import com.salesmanagement.reporting.internal.support.ReportTable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.salesmanagement.reporting.internal.dto.SalesReportDtos.RepProductivityRow;
import com.salesmanagement.routing.api.RouteInfo;
import com.salesmanagement.routing.api.RoutingFacade;
import com.salesmanagement.visit.api.VisitFacade;
import com.salesmanagement.visit.api.VisitInfo;
import java.util.Comparator;
import java.util.Map;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.salesmanagement.reporting.internal.dto.SalesReportDtos.UNRESOLVED_NAME;

/**
 * Builds the sales reports (FR-115 rep performance, FR-116/117 invoice list) by composing the invoice
 * aggregates with name resolution from the identity and customer modules.
 *
 * <p><strong>N+1 discipline (D1) in practice.</strong> Rep names are resolved per row on purpose — rep
 * cardinality is tiny (a handful per company), so a loop of {@code UserFacade.getNameById} is cheaper
 * than a new batch facade method. Customer names, unbounded, are resolved in ONE
 * {@code CustomerFacade.getNamesByIds} batch. That asymmetry is the whole point of the earlier facade
 * decision: batch where cardinality is unbounded, loop where it is bounded and small.</p>
 *
 * <p><strong>Read-only snapshot (D8).</strong> Class-level {@code @Transactional(readOnly = true)}.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SalesReportService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final InvoiceFacade invoiceFacade;
    private final UserFacade userFacade;
    private final CustomerFacade customerFacade;
    private final RoutingFacade routingFacade;
    private final VisitFacade visitFacade;


    // ── FR-115: rep sales performance ─────────────────────────────────────────

    /**
     * Realised sales per rep in the window. Reps resolved per-row (bounded cardinality). Ordered by
     * total sales descending — the report's natural "who sold most" ordering.
     */
    public List<RepSalesRow> repPerformance(DateRange range) {
        List<RepSalesAggregate> aggregates =
                invoiceFacade.aggregateByRep(range.from(), range.to());

        return aggregates.stream()
                .map(a -> new RepSalesRow(
                        a.representativeId(),
                        safeUserName(a.representativeId()),
                        a.invoiceCount(),
                        a.totalSales()))
                .sorted((x, y) -> y.totalSales().compareTo(x.totalSales()))
                .toList();
    }

    /** Rep performance as an export table. */
    public ReportTable repPerformanceTable(DateRange range) {
        List<List<String>> rows = repPerformance(range).stream()
                .map(r -> List.of(
                        r.representativeName(),
                        String.valueOf(r.invoiceCount()),
                        money(r.totalSales())))
                .toList();
        return new ReportTable(
                "تقرير أداء المندوبين",                          // "Rep performance report"
                List.of("المندوب", "عدد الفواتير", "إجمالي المبيعات"), // rep, invoice count, total sales
                rows);
    }

    // ── FR-116/117: invoice list ──────────────────────────────────────────────

    /**
     * Flat invoice list for the window, optionally filtered by rep and/or customer (FR-117 filters).
     * Customer and rep names batch-resolved: customers via one {@code getNamesByIds}, reps via one
     * (over the distinct rep set, which is small) — no per-invoice lookups.
     */
    public List<InvoiceRow> invoiceList(DateRange range, Long representativeId, Long customerId) {
        List<InvoiceSummary> summaries =
                invoiceFacade.findSummaries(range.from(), range.to(), representativeId, customerId);

        Map<Long, String> customerNames = customerFacade.getNamesByIds(
                summaries.stream().map(InvoiceSummary::customerId).collect(Collectors.toSet()));

        // Distinct reps in the result — resolved once each, not per invoice.
        Map<Long, String> repNames = summaries.stream()
                .map(InvoiceSummary::representativeId)
                .collect(Collectors.toSet())
                .stream()
                .collect(Collectors.toMap(id -> id, this::safeUserName));

        return summaries.stream()
                .map(s -> new InvoiceRow(
                        s.invoiceId(),
                        s.invoiceDate(),
                        s.customerId(),
                        customerNames.getOrDefault(s.customerId(), UNRESOLVED_NAME),
                        s.representativeId(),
                        repNames.getOrDefault(s.representativeId(), UNRESOLVED_NAME),
                        s.totalAmount(),
                        s.status()))
                .toList();
    }

    /** Invoice list as an export table. */
    public ReportTable invoiceListTable(DateRange range, Long representativeId, Long customerId) {
        List<List<String>> rows = invoiceList(range, representativeId, customerId).stream()
                .map(r -> List.of(
                        String.valueOf(r.invoiceId()),
                        r.invoiceDate().format(DATE_FMT),
                        r.customerName(),
                        r.representativeName(),
                        money(r.totalAmount()),
                        r.status()))
                .toList();
        return new ReportTable(
                "قائمة الفواتير",                                                  // "Invoice list"
                List.of("رقم الفاتورة", "التاريخ", "العميل", "المندوب", "الإجمالي", "الحالة"),
                rows);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * Resolves a user's name, degrading to the placeholder rather than throwing if the id is orphaned
     * — the {@code safeUserName} pattern used across the facades, so one bad reference never blows up a
     * whole report.
     */
    private String safeUserName(Long userId) {
        if (userId == null) {
            return UNRESOLVED_NAME;
        }
        try {
            String name = userFacade.getNameById(userId);
            return (name == null || name.isBlank()) ? UNRESOLVED_NAME : name;
        } catch (RuntimeException e) {
            return UNRESOLVED_NAME;
        }
    }

    /** Formats money for export cells: 2dp HALF_UP, plain decimal (the service owns column meaning). */
    private String money(BigDecimal v) {
        BigDecimal safe = (v == null ? BigDecimal.ZERO : v);
        return safe.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Rep productivity over a window: sales side (invoice count, total, avg invoice value) joined with
     * the field side (planned stops vs completed visits -> completion rate). One row per rep who either
     * sold or had a route in the window. Sales come from aggregateByRep; the field side from the routes
     * in the window and their visits (one batch), same composition as the route performance report.
     */
    public List<RepProductivityRow> repProductivity(DateRange range) {
        // Sales side.
        Map<Long, RepSalesAggregate> salesByRep = invoiceFacade.aggregateByRep(range.from(), range.to())
                .stream().collect(Collectors.toMap(RepSalesAggregate::representativeId, a -> a));

        // Field side: routes in window -> their visits (one batch).
        List<RouteInfo> routes = routingFacade.findRoutesInRange(range.from(), range.to(), null);
        Map<Long, List<VisitInfo>> visitsByRoute = routes.isEmpty()
                ? Map.of()
                : visitFacade.findVisitsByRouteIds(routes.stream().map(RouteInfo::id).toList())
                .stream().collect(Collectors.groupingBy(VisitInfo::routeId));

        // Fold planned stops and completed visits per rep.
        Map<Long, int[]> field = new java.util.LinkedHashMap<>(); // repId -> [planned, completed]
        for (RouteInfo r : routes) {
            int[] slot = field.computeIfAbsent(r.representativeId(), k -> new int[2]);
            slot[0] += (r.stops() == null ? 0 : r.stops().size());
            List<VisitInfo> vs = visitsByRoute.getOrDefault(r.id(), List.of());
            slot[1] += (int) vs.stream().filter(v -> "COMPLETED".equals(v.status())).count();
        }

        // Union of rep ids from both sides.
        java.util.Set<Long> repIds = new java.util.HashSet<>();
        repIds.addAll(salesByRep.keySet());
        repIds.addAll(field.keySet());

        return repIds.stream()
                .map(repId -> {
                    RepSalesAggregate s = salesByRep.get(repId);
                    long invoiceCount = (s == null ? 0 : s.invoiceCount());
                    BigDecimal totalSales = (s == null ? BigDecimal.ZERO : s.totalSales());
                    BigDecimal avg = invoiceCount == 0
                            ? BigDecimal.ZERO
                            : totalSales.divide(BigDecimal.valueOf(invoiceCount), 2, RoundingMode.HALF_UP);

                    int[] f = field.getOrDefault(repId, new int[2]);
                    int planned = f[0];
                    int completed = f[1];
                    BigDecimal completion = planned == 0
                            ? BigDecimal.ZERO
                            : BigDecimal.valueOf(completed)
                            .multiply(BigDecimal.valueOf(100))
                            .divide(BigDecimal.valueOf(planned), 1, RoundingMode.HALF_UP);

                    return new RepProductivityRow(
                            repId, safeUserName(repId),
                            invoiceCount, totalSales.setScale(2, RoundingMode.HALF_UP), avg,
                            planned, completed, completion);
                })
                .sorted(Comparator.comparing(RepProductivityRow::totalSales).reversed())
                .toList();
    }

    public ReportTable repProductivityTable(DateRange range) {
        List<List<String>> rows = repProductivity(range).stream()
                .map(r -> List.of(
                        r.representativeName(),
                        String.valueOf(r.invoiceCount()),
                        r.totalSales().toPlainString(),
                        r.avgInvoiceValue().toPlainString(),
                        String.valueOf(r.plannedStops()),
                        String.valueOf(r.completedVisits()),
                        r.visitCompletionPercent().toPlainString() + "%"))
                .toList();
        return new ReportTable(
                "\u062a\u0642\u0631\u064a\u0631 \u0625\u0646\u062a\u0627\u062c\u064a\u0629 \u0627\u0644\u0645\u0646\u062f\u0648\u0628\u064a\u0646",
                List.of(
                        "\u0627\u0644\u0645\u0646\u062f\u0648\u0628",
                        "\u0639\u062f\u062f \u0627\u0644\u0641\u0648\u0627\u062a\u064a\u0631",
                        "\u0625\u062c\u0645\u0627\u0644\u064a \u0627\u0644\u0645\u0628\u064a\u0639\u0627\u062a",
                        "\u0645\u062a\u0648\u0633\u0637 \u0627\u0644\u0641\u0627\u062a\u0648\u0631\u0629",
                        "\u0627\u0644\u0632\u064a\u0627\u0631\u0627\u062a \u0627\u0644\u0645\u062e\u0637\u0637\u0629",
                        "\u0627\u0644\u0632\u064a\u0627\u0631\u0627\u062a \u0627\u0644\u0645\u0646\u062c\u0632\u0629",
                        "\u0646\u0633\u0628\u0629 \u0627\u0644\u0625\u0646\u062c\u0627\u0632"),
                rows);
    }
}
