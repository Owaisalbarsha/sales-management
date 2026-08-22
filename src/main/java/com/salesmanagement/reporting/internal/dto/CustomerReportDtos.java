package com.salesmanagement.reporting.internal.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public class CustomerReportDtos {

    /**
     * A dormant customer: no realised invoice in the configured window, or never any realised invoice.
     * lastInvoiceDate is null when neverPurchased is true.
     */
    public record DormantCustomerRow(
            Long      customerId,
            String    customerName,
            LocalDate lastInvoiceDate,
            boolean   neverPurchased
    ) {}

    /**
     * Average order value per customer over a window: totalSpent / invoiceCount.
     */
    public record AvgOrderValueRow(
            Long       customerId,
            String     customerName,
            long       invoiceCount,
            BigDecimal totalSpent,
            BigDecimal avgOrderValue
    ) {}

}
