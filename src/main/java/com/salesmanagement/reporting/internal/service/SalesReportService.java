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
}
