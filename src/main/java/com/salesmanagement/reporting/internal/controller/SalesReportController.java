package com.salesmanagement.reporting.internal.controller;

import com.salesmanagement.reporting.internal.dto.SalesReportDtos.InvoiceRow;
import com.salesmanagement.reporting.internal.dto.SalesReportDtos.RepSalesRow;
import com.salesmanagement.reporting.internal.dto.SalesReportDtos.ReportEnvelope;
import com.salesmanagement.reporting.internal.service.SalesReportService;
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
import com.salesmanagement.reporting.internal.dto.SalesReportDtos.RepProductivityRow;
import java.time.LocalDate;

/**
 * Sales reports (FR-115 rep performance, FR-116/117 invoice list). Restricted to ADMIN and
 * SALES_MANAGER (locked decision) — {@code @PreAuthorize} on the controller only, no auth logic in the
 * service.
 *
 * <p>Each endpoint takes {@code ?format=json|xlsx|pdf} (default json) and a half-open {@code [from,to)}
 * date window as plain {@code LocalDate} params (dates, not instants — every underlying column is a
 * business date). JSON returns typed DTOs in the standard {@code ApiResponse} envelope; xlsx/pdf return
 * a file download.</p>
 */
@RestController
@RequestMapping("/api/reports/sales")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
public class SalesReportController {

    private final SalesReportService salesReportService;
    private final ReportResponseFactory responseFactory;

    /** FR-115: rep sales performance. */
    @GetMapping("/rep-performance")
    public ResponseEntity<?> repPerformance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String format) {

        DateRange range = DateRangeResolver.resolve(from, to);
        Format fmt = responseFactory.parse(format);

        if (fmt == Format.JSON) {
            return ResponseEntity.ok(ApiResponse.ok(new ReportEnvelope<RepSalesRow>(
                    range.from(), range.to(), salesReportService.repPerformance(range))));
        }
        return responseFactory.file(fmt, "rep-performance",
                () -> salesReportService.repPerformanceTable(range));
    }

    /** FR-116/117: invoice list, optionally filtered by rep and/or customer. */
    @GetMapping("/invoices")
    public ResponseEntity<?> invoiceList(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long representativeId,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String format) {

        DateRange range = DateRangeResolver.resolve(from, to);
        Format fmt = responseFactory.parse(format);

        if (fmt == Format.JSON) {
            return ResponseEntity.ok(ApiResponse.ok(new ReportEnvelope<InvoiceRow>(
                    range.from(), range.to(),
                    salesReportService.invoiceList(range, representativeId, customerId))));
        }
        return responseFactory.file(fmt, "invoice-list",
                () -> salesReportService.invoiceListTable(range, representativeId, customerId));
    }

    /** Rep productivity: sales + visit completion per rep over a window. */
    @GetMapping("/rep-productivity")
    public ResponseEntity<?> repProductivity(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String format) {

        DateRange range = DateRangeResolver.resolve(from, to);
        Format fmt = responseFactory.parse(format);
        if (fmt == Format.JSON) {
            return ResponseEntity.ok(ApiResponse.ok(new ReportEnvelope<RepProductivityRow>(
                    range.from(), range.to(), salesReportService.repProductivity(range))));
        }
        return responseFactory.file(fmt, "rep-productivity",
                () -> salesReportService.repProductivityTable(range));
    }
}
