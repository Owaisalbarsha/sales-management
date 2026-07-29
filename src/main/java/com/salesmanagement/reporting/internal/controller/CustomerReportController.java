package com.salesmanagement.reporting.internal.controller;

import com.salesmanagement.reporting.internal.dto.SalesReportDtos.CustomerPurchaseRow;
import com.salesmanagement.reporting.internal.dto.SalesReportDtos.ReportEnvelope;
import com.salesmanagement.reporting.internal.service.CustomerReportService;
import com.salesmanagement.reporting.internal.support.DateRangeResolver;
import com.salesmanagement.reporting.internal.support.DateRangeResolver.DateRange;
import com.salesmanagement.reporting.internal.support.ReportResponseFactory;
import com.salesmanagement.reporting.internal.support.ReportResponseFactory.Format;
import com.salesmanagement.shared.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Customer purchasing reports (FR-118 behaviour, FR-119 spend ranking). Restricted to ADMIN and
 * SALES_MANAGER — customer purchasing data is sales-domain, so warehouse managers do not see it.
 */
@RestController
@RequestMapping("/api/reports/customers")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
public class CustomerReportController {

    private final CustomerReportService customerReportService;
    private final ReportResponseFactory responseFactory;

    /** FR-118/119: per-customer purchase count and spend, ranked by spend. */
    @GetMapping("/purchases")
    public ResponseEntity<?> customerPurchases(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String format) {

        DateRange range = DateRangeResolver.resolve(from, to);
        Format fmt = responseFactory.parse(format);

        if (fmt == Format.JSON) {
            return ResponseEntity.ok(ApiResponse.ok(new ReportEnvelope<CustomerPurchaseRow>(
                    range.from(), range.to(), customerReportService.customerPurchases(range))));
        }
        return responseFactory.file(fmt, "customer-purchases",
                () -> customerReportService.customerPurchasesTable(range));
    }
}
