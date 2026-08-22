package com.salesmanagement.reporting.internal.controller;

import com.salesmanagement.reporting.internal.dto.TerritoryReportDtos.TerritoryCustomersRow;
import com.salesmanagement.reporting.internal.dto.TerritoryReportDtos.TerritoryEnvelope;
import com.salesmanagement.reporting.internal.dto.TerritoryReportDtos.TerritorySalesRow;
import com.salesmanagement.reporting.internal.service.TerritoryReportService;
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
 * Territory reports (sales by territory, customers per territory). ADMIN and SALES_MANAGER - territory
 * performance is sales-domain supervision.
 */
@RestController
@RequestMapping("/api/reports/territories")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
public class TerritoryReportController {

    private final TerritoryReportService territoryReportService;
    private final ReportResponseFactory responseFactory;

    /** Sales grouped by territory over a window. */
    @GetMapping("/sales")
    public ResponseEntity<?> sales(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String format) {

        DateRange range = DateRangeResolver.resolve(from, to);
        Format fmt = responseFactory.parse(format);
        if (fmt == Format.JSON) {
            return ResponseEntity.ok(ApiResponse.ok(new TerritoryEnvelope<TerritorySalesRow>(
                    territoryReportService.salesByTerritory(range))));
        }
        return responseFactory.file(fmt, "territory-sales",
                () -> territoryReportService.salesByTerritoryTable(range));
    }

    /** Active-customer count per territory (snapshot, no date window). */
    @GetMapping("/customers")
    public ResponseEntity<?> customers(@RequestParam(required = false) String format) {
        Format fmt = responseFactory.parse(format);
        if (fmt == Format.JSON) {
            return ResponseEntity.ok(ApiResponse.ok(new TerritoryEnvelope<TerritoryCustomersRow>(
                    territoryReportService.customersByTerritory())));
        }
        return responseFactory.file(fmt, "territory-customers",
                territoryReportService::customersByTerritoryTable);
    }
}
