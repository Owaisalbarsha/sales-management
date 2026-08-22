package com.salesmanagement.reporting.internal.service;

import com.salesmanagement.customer.api.CustomerFacade;
import com.salesmanagement.invoicing.api.CustomerPurchaseAggregate;
import com.salesmanagement.invoicing.api.InvoiceFacade;
import com.salesmanagement.reporting.internal.dto.TerritoryReportDtos.TerritoryCustomersRow;
import com.salesmanagement.reporting.internal.dto.TerritoryReportDtos.TerritorySalesRow;
import com.salesmanagement.reporting.internal.support.DateRangeResolver.DateRange;
import com.salesmanagement.reporting.internal.support.ReportTable;
import com.salesmanagement.territory.api.TerritoryFacade;
import com.salesmanagement.territory.api.TerritoryInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds the territory reports (sales by territory, customers per territory) - the flagship of the
 * analytics batch. Sales-by-territory is the interesting one: invoices carry customerId, not
 * territoryId, so the service pulls per-customer sales, maps each customer to its territory, and
 * regroups. Both reports start from the FULL territory list so a silent or empty territory is visible
 * at zero rather than missing.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TerritoryReportService {

    private final InvoiceFacade invoiceFacade;
    private final CustomerFacade customerFacade;
    private final TerritoryFacade territoryFacade;

    // -- Sales by territory --------------------------------------------------

    /**
     * Realised sales grouped by territory over the window. Per-customer aggregates are resolved to
     * their territory (one batch map) and summed. Every territory appears, zero-filled if silent.
     */
    public List<TerritorySalesRow> salesByTerritory(DateRange range) {
        List<CustomerPurchaseAggregate> byCustomer =
                invoiceFacade.aggregateByCustomer(range.from(), range.to());

        // customerId -> territoryId, one batch.
        Map<Long, Long> custToTerr = customerFacade.getTerritoryIdsByIds(
                byCustomer.stream().map(CustomerPurchaseAggregate::customerId).collect(Collectors.toSet()));

        // Fold customer aggregates into territory buckets.
        Map<Long, long[]> counts = new LinkedHashMap<>();       // territoryId -> [invoiceCount]
        Map<Long, BigDecimal> sales = new LinkedHashMap<>();    // territoryId -> totalSales
        for (CustomerPurchaseAggregate a : byCustomer) {
            Long terr = custToTerr.get(a.customerId());
            if (terr == null) {
                continue; // customer with no resolvable territory - skip rather than bucket under null
            }
            counts.computeIfAbsent(terr, k -> new long[1])[0] += a.invoiceCount();
            sales.merge(terr, a.totalSpent(), BigDecimal::add);
        }

        // Start from ALL territories so empty ones show at zero.
        List<TerritoryInfo> all = territoryFacade.getAllTerritories();
        return all.stream()
                .map(t -> new TerritorySalesRow(
                        t.id(), t.name(),
                        counts.getOrDefault(t.id(), new long[1])[0],
                        sales.getOrDefault(t.id(), BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)))
                .sorted(Comparator.comparing(TerritorySalesRow::totalSales).reversed())
                .toList();
    }

    public ReportTable salesByTerritoryTable(DateRange range) {
        List<List<String>> rows = salesByTerritory(range).stream()
                .map(r -> List.of(
                        r.territoryName(),
                        String.valueOf(r.invoiceCount()),
                        r.totalSales().toPlainString()))
                .toList();
        return new ReportTable(
                "\u062a\u0642\u0631\u064a\u0631 \u0627\u0644\u0645\u0628\u064a\u0639\u0627\u062a \u062d\u0633\u0628 \u0627\u0644\u0645\u0646\u0637\u0642\u0629",
                List.of(
                        "\u0627\u0644\u0645\u0646\u0637\u0642\u0629",
                        "\u0639\u062f\u062f \u0627\u0644\u0641\u0648\u0627\u062a\u064a\u0631",
                        "\u0625\u062c\u0645\u0627\u0644\u064a \u0627\u0644\u0645\u0628\u064a\u0639\u0627\u062a"),
                rows);
    }

    // -- Customers per territory ---------------------------------------------

    /**
     * Active-customer count per territory (snapshot). Starts from all territories, zero-fills the ones
     * with no active customers. Ranked by count descending.
     */
    public List<TerritoryCustomersRow> customersByTerritory() {
        Map<Long, Long> countByTerr = customerFacade.countActiveCustomersByTerritory();
        return territoryFacade.getAllTerritories().stream()
                .map(t -> new TerritoryCustomersRow(
                        t.id(), t.name(), countByTerr.getOrDefault(t.id(), 0L)))
                .sorted(Comparator.comparingLong(TerritoryCustomersRow::customerCount).reversed())
                .toList();
    }

    public ReportTable customersByTerritoryTable() {
        List<List<String>> rows = customersByTerritory().stream()
                .map(r -> List.of(r.territoryName(), String.valueOf(r.customerCount())))
                .toList();
        return new ReportTable(
                "\u062a\u0642\u0631\u064a\u0631 \u0627\u0644\u0639\u0645\u0644\u0627\u0621 \u062d\u0633\u0628 \u0627\u0644\u0645\u0646\u0637\u0642\u0629",
                List.of(
                        "\u0627\u0644\u0645\u0646\u0637\u0642\u0629",
                        "\u0639\u062f\u062f \u0627\u0644\u0639\u0645\u0644\u0627\u0621"),
                rows);
    }
}
