package com.salesmanagement.reporting.internal.service;

import com.salesmanagement.customer.api.CustomerFacade;
import com.salesmanagement.invoicing.api.CustomerPurchaseAggregate;
import com.salesmanagement.invoicing.api.InvoiceFacade;
import com.salesmanagement.reporting.internal.dto.SalesReportDtos.CustomerPurchaseRow;
import com.salesmanagement.reporting.internal.support.DateRangeResolver.DateRange;
import com.salesmanagement.reporting.internal.support.ReportTable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.salesmanagement.customer.api.CustomerBasicInfo;
import com.salesmanagement.invoicing.api.CustomerPurchaseAggregate;
import com.salesmanagement.reporting.internal.config.ReportingConfig;
import com.salesmanagement.reporting.internal.dto.SalesReportDtos;
import com.salesmanagement.systemconfig.api.ConfigFacade;
import com.salesmanagement.reporting.internal.dto.CustomerReportDtos.DormantCustomerRow;
import com.salesmanagement.reporting.internal.dto.CustomerReportDtos.AvgOrderValueRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.salesmanagement.reporting.internal.dto.SalesReportDtos.UNRESOLVED_NAME;

/**
 * Builds the customer purchasing reports (FR-118 purchasing behaviour, FR-119 spend ranking) from the
 * per-customer invoice aggregate plus a single batch name resolution.
 *
 * <p><strong>Batch, not loop (D1).</strong> Customer cardinality is unbounded, so every name is
 * resolved in ONE {@code CustomerFacade.getNamesByIds} call over the distinct customer set — never a
 * per-row lookup. This is the case the batch facade method was added for.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomerReportService {

    private final InvoiceFacade invoiceFacade;
    private final CustomerFacade customerFacade;
    private final ConfigFacade configFacade;

    /**
     * Realised purchases per customer in the window, ordered by total spent descending (FR-119's "top
     * customers" ordering; FR-118's behaviour view is the same rows read differently).
     */
    public List<CustomerPurchaseRow> customerPurchases(DateRange range) {
        List<CustomerPurchaseAggregate> aggregates =
                invoiceFacade.aggregateByCustomer(range.from(), range.to());

        Map<Long, String> names = customerFacade.getNamesByIds(
                aggregates.stream().map(CustomerPurchaseAggregate::customerId).collect(Collectors.toSet()));

        return aggregates.stream()
                .map(a -> new CustomerPurchaseRow(
                        a.customerId(),
                        names.getOrDefault(a.customerId(), UNRESOLVED_NAME),
                        a.invoiceCount(),
                        a.totalSpent()))
                .sorted((x, y) -> y.totalSpent().compareTo(x.totalSpent()))
                .toList();
    }

    /** Customer purchases as an export table. */
    public ReportTable customerPurchasesTable(DateRange range) {
        List<List<String>> rows = customerPurchases(range).stream()
                .map(r -> List.of(
                        r.customerName(),
                        String.valueOf(r.invoiceCount()),
                        money(r.totalSpent())))
                .toList();
        return new ReportTable(
                "تقرير مشتريات العملاء",                              // "Customer purchases report"
                List.of("العميل", "عدد الفواتير", "إجمالي الإنفاق"),  // customer, invoice count, total spend
                rows);
    }

    private String money(BigDecimal v) {
        BigDecimal safe = (v == null ? BigDecimal.ZERO : v);
        return safe.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Customers with no realised invoice in the last N days (N from systemconfig, default 60), PLUS
     * customers who have never had a realised invoice at all - the strongest churn signal. Starts from
     * every ACTIVE customer, subtracts the recently-active ones. Ordered oldest-activity first (never-
     * purchased at the very top).
     */
    public List<DormantCustomerRow> dormantCustomers() {
        int days = configFacade.getInt(
                ReportingConfig.KEY_DORMANT_DAYS, ReportingConfig.DEFAULT_DORMANT_DAYS);
        LocalDate today = LocalDate.now(ReportingConfig.BUSINESS_ZONE);
        LocalDate cutoff = today.minusDays(days);

        Map<Long, LocalDate> lastDates = invoiceFacade.getLastInvoiceDates();
        List<CustomerBasicInfo> active = customerFacade.getActiveCustomers();

        return active.stream()
                .map(c -> {
                    LocalDate last = lastDates.get(c.id());
                    boolean never = (last == null);
                    boolean dormant = never || last.isBefore(cutoff);
                    return dormant
                            ? new DormantCustomerRow(c.id(), c.name(), last, never)
                            : null;
                })
                .filter(java.util.Objects::nonNull)
                // never-purchased first (null date), then oldest last-activity first
                .sorted(Comparator.comparing(
                        DormantCustomerRow::lastInvoiceDate,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    public ReportTable dormantCustomersTable() {
        List<List<String>> rows = dormantCustomers().stream()
                .map(r -> List.of(
                        r.customerName(),
                        r.neverPurchased() ? "\u0644\u0645 \u064a\u0634\u062a\u0631\u0650 \u0645\u0637\u0644\u0642\u0627\u064b"
                                : r.lastInvoiceDate().toString()))
                .toList();
        return new ReportTable(
                "\u062a\u0642\u0631\u064a\u0631 \u0627\u0644\u0639\u0645\u0644\u0627\u0621 \u0627\u0644\u062e\u0627\u0645\u0644\u064a\u0646",
                List.of(
                        "\u0627\u0644\u0639\u0645\u064a\u0644",
                        "\u0622\u062e\u0631 \u0634\u0631\u0627\u0621"),
                rows);
    }

    // -- Average order value per customer ------------------------------------

    /**
     * Average order value per customer over the window: totalSpent / invoiceCount. Reuses the existing
     * per-customer aggregate; just adds the division. Ranked by avg order value descending.
     */
    public List<AvgOrderValueRow> averageOrderValue(DateRange range) {
        List<CustomerPurchaseAggregate> aggregates =
                invoiceFacade.aggregateByCustomer(range.from(), range.to());

        Map<Long, String> names = customerFacade.getNamesByIds(
                aggregates.stream().map(CustomerPurchaseAggregate::customerId)
                        .collect(java.util.stream.Collectors.toSet()));

        return aggregates.stream()
                .map(a -> {
                    BigDecimal avg = a.invoiceCount() == 0
                            ? BigDecimal.ZERO
                            : a.totalSpent().divide(BigDecimal.valueOf(a.invoiceCount()), 2, RoundingMode.HALF_UP);
                    return new AvgOrderValueRow(
                            a.customerId(), names.getOrDefault(a.customerId(), "\u2014"),
                            a.invoiceCount(), a.totalSpent(), avg);
                })
                .sorted(Comparator.comparing(AvgOrderValueRow::avgOrderValue).reversed())
                .toList();
    }

    public ReportTable averageOrderValueTable(DateRange range) {
        List<List<String>> rows = averageOrderValue(range).stream()
                .map(r -> List.of(
                        r.customerName(),
                        String.valueOf(r.invoiceCount()),
                        r.totalSpent().toPlainString(),
                        r.avgOrderValue().toPlainString()))
                .toList();
        return new ReportTable(
                "\u0645\u062a\u0648\u0633\u0637 \u0642\u064a\u0645\u0629 \u0627\u0644\u0637\u0644\u0628 \u0644\u0644\u0639\u0645\u064a\u0644",
                List.of(
                        "\u0627\u0644\u0639\u0645\u064a\u0644",
                        "\u0639\u062f\u062f \u0627\u0644\u0641\u0648\u0627\u062a\u064a\u0631",
                        "\u0625\u062c\u0645\u0627\u0644\u064a \u0627\u0644\u0625\u0646\u0641\u0627\u0642",
                        "\u0645\u062a\u0648\u0633\u0637 \u0627\u0644\u0637\u0644\u0628"),
                rows);
    }
}
