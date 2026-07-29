package com.salesmanagement.reporting.internal.controller;

import com.salesmanagement.reporting.internal.dto.InventoryReportDtos.AgingRow;
import com.salesmanagement.reporting.internal.dto.InventoryReportDtos.FillRateRow;
import com.salesmanagement.reporting.internal.dto.InventoryReportDtos.InventoryEnvelope;
import com.salesmanagement.reporting.internal.dto.InventoryReportDtos.MovementRow;
import com.salesmanagement.reporting.internal.dto.InventoryReportDtos.ProductMovementRow;
import com.salesmanagement.reporting.internal.dto.InventoryReportDtos.StockLevelRow;
import com.salesmanagement.reporting.internal.service.InventoryReportService;
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
import java.util.List;

/**
 * Inventory reports (FR-120/121/122/123 + aging + fill-rate). Restricted to ADMIN and
 * WAREHOUSE_MANAGER (locked decision) — warehouse managers see inventory reports, not sales/customer
 * ones, and the role split is enforced here at the controller.
 *
 * <p>FR-124 (stock variance) is intentionally absent: it depends on the not-yet-built inventory
 * stock-count feature. It will be added here once that upstream exists.</p>
 */
@RestController
@RequestMapping("/api/reports/inventory")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER')")
public class InventoryReportController {

    private final InventoryReportService inventoryReportService;
    private final ReportResponseFactory responseFactory;

    /** FR-120: all warehouse stock levels. */
    @GetMapping("/stock-levels")
    public ResponseEntity<?> stockLevels(@RequestParam(required = false) String format) {
        Format fmt = responseFactory.parse(format);
        if (fmt == Format.JSON) {
            return ResponseEntity.ok(ApiResponse.ok(
                    new InventoryEnvelope<StockLevelRow>(inventoryReportService.stockLevels(false))));
        }
        return responseFactory.file(fmt, "stock-levels",
                () -> inventoryReportService.stockLevelsTable(false));
    }

    /** FR-121: products below minimum (reorder list). */
    @GetMapping("/below-minimum")
    public ResponseEntity<?> belowMinimum(@RequestParam(required = false) String format) {
        Format fmt = responseFactory.parse(format);
        if (fmt == Format.JSON) {
            return ResponseEntity.ok(ApiResponse.ok(
                    new InventoryEnvelope<StockLevelRow>(inventoryReportService.stockLevels(true))));
        }
        return responseFactory.file(fmt, "below-minimum",
                () -> inventoryReportService.stockLevelsTable(true));
    }

    /** FR-122: stock movement summary (loaded / returned / sold / net) over a window. */
    @GetMapping("/movement")
    public ResponseEntity<?> movement(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String format) {

        DateRange range = DateRangeResolver.resolve(from, to);
        Format fmt = responseFactory.parse(format);
        if (fmt == Format.JSON) {
            return ResponseEntity.ok(ApiResponse.ok(
                    new InventoryEnvelope<MovementRow>(inventoryReportService.movementSummary(range))));
        }
        return responseFactory.file(fmt, "stock-movement",
                () -> inventoryReportService.movementSummaryTable(range));
    }

    /** FR-123: fast / slow-moving product classification over a window. */
    @GetMapping("/fast-slow-moving")
    public ResponseEntity<?> fastSlowMoving(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String format) {

        DateRange range = DateRangeResolver.resolve(from, to);
        Format fmt = responseFactory.parse(format);
        if (fmt == Format.JSON) {
            return ResponseEntity.ok(ApiResponse.ok(
                    new InventoryEnvelope<ProductMovementRow>(inventoryReportService.fastSlowMoving(range))));
        }
        return responseFactory.file(fmt, "fast-slow-moving",
                () -> inventoryReportService.fastSlowMovingTable(range));
    }

    /** Aging / dead-stock: stocked products with no movement in the configured window. */
    @GetMapping("/aging")
    public ResponseEntity<?> aging(@RequestParam(required = false) String format) {
        Format fmt = responseFactory.parse(format);
        if (fmt == Format.JSON) {
            return ResponseEntity.ok(ApiResponse.ok(
                    new InventoryEnvelope<AgingRow>(inventoryReportService.aging())));
        }
        return responseFactory.file(fmt, "aging-stock",
                inventoryReportService::agingTable);
    }

    /** Fill-rate: how well the warehouse met demand per product over a window. */
    @GetMapping("/fill-rate")
    public ResponseEntity<?> fillRate(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String format) {

        DateRange range = DateRangeResolver.resolve(from, to);
        Format fmt = responseFactory.parse(format);
        if (fmt == Format.JSON) {
            return ResponseEntity.ok(ApiResponse.ok(
                    new InventoryEnvelope<FillRateRow>(inventoryReportService.fillRate(range))));
        }
        return responseFactory.file(fmt, "fill-rate",
                () -> inventoryReportService.fillRateTable(range));
    }
}
