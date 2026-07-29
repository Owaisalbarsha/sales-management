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
}
