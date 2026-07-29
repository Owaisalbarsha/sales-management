package com.salesmanagement.reporting.internal.controller;

import com.salesmanagement.reporting.internal.dto.RouteReportDtos.MissedVisitRow;
import com.salesmanagement.reporting.internal.dto.RouteReportDtos.RouteEnvelope;
import com.salesmanagement.reporting.internal.dto.RouteReportDtos.RoutePerformanceRow;
import com.salesmanagement.reporting.internal.service.RouteReportService;
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
 * Route performance reports (FR-125 planned-vs-actual, FR-126 completion rate, FR-127 missed visits).
 * Restricted to ADMIN and SALES_MANAGER — route execution is sales-domain supervision.
 */
@RestController
@RequestMapping("/api/reports/routes")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
public class RouteReportController {

    private final RouteReportService routeReportService;
    private final ReportResponseFactory responseFactory;

    /** FR-125/126: per-route planned-vs-actual with completion rate. Optional rep filter. */
    @GetMapping("/performance")
    public ResponseEntity<?> performance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long representativeId,
            @RequestParam(required = false) String format) {

        DateRange range = DateRangeResolver.resolve(from, to);
        Format fmt = responseFactory.parse(format);
        if (fmt == Format.JSON) {
            return ResponseEntity.ok(ApiResponse.ok(new RouteEnvelope<RoutePerformanceRow>(
                    range.from(), range.to(), routeReportService.routePerformance(range, representativeId))));
        }
        return responseFactory.file(fmt, "route-performance",
                () -> routeReportService.routePerformanceTable(range, representativeId));
    }

    /** FR-127: flat missed-visit list. Optional rep filter. */
    @GetMapping("/missed-visits")
    public ResponseEntity<?> missedVisits(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long representativeId,
            @RequestParam(required = false) String format) {

        DateRange range = DateRangeResolver.resolve(from, to);
        Format fmt = responseFactory.parse(format);
        if (fmt == Format.JSON) {
            return ResponseEntity.ok(ApiResponse.ok(new RouteEnvelope<MissedVisitRow>(
                    range.from(), range.to(), routeReportService.missedVisits(range, representativeId))));
        }
        return responseFactory.file(fmt, "missed-visits",
                () -> routeReportService.missedVisitsTable(range, representativeId));
    }
}
