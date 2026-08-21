package com.salesmanagement.reporting.internal.support;

import com.salesmanagement.shared.exception.BusinessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import com.salesmanagement.reporting.internal.support.ExportBuilder.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.function.Supplier;

/**
 * Turns a report's {@code format} parameter into the right HTTP response, so every controller shares
 * one branching path instead of repeating it. Supports {@code json} (handled by the caller, since the
 * JSON body is a typed DTO), {@code xlsx}, and {@code pdf}.
 *
 * <p>The export builders are passed as suppliers so the exporter only runs for the format actually
 * requested — a JSON call never builds an Excel workbook.</p>
 */
@Component
public class ReportResponseFactory {

    private final ExcelExporter excelExporter;
    private final PdfExporter pdfExporter;

    public ReportResponseFactory(ExcelExporter excelExporter, PdfExporter pdfExporter) {
        this.excelExporter = excelExporter;
        this.pdfExporter = pdfExporter;
    }

    /** The three output formats a report endpoint accepts. */
    public enum Format { JSON, XLSX, PDF }

    /**
     * Parses the raw {@code ?format=} value, defaulting to JSON when absent.
     *
     * @throws BusinessException 400 on an unrecognised format
     */
    public Format parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Format.JSON;
        }
        try {
            return Format.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BusinessException.badRequest(
                    "Unsupported report format '" + raw + "'. Use json, xlsx, or pdf.",
                    "REPORT_FORMAT_UNSUPPORTED");
        }
    }

    /**
     * Builds a file-download {@link ResponseEntity} for an xlsx or pdf export. The caller resolves the
     * table lazily via {@code tableSupplier} so it is only built for a file format. The filename embeds
     * the report slug and today's date.
     *
     * @param format       must be {@code XLSX} or {@code PDF} (JSON is handled by the caller)
     * @param filenameSlug a short ascii slug for the download filename (e.g. {@code "rep-performance"})
     * @param tableSupplier produces the {@link ReportTable} to export
     */
    public ResponseEntity<byte[]> file(Format format, String filenameSlug, Supplier<ReportTable> tableSupplier) {
        ReportTable table = tableSupplier.get();
        return switch (format) {
            case XLSX -> download(
                    excelExporter.toXlsx(table),
                    filenameSlug + "-" + LocalDate.now() + ".xlsx",
                    MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
            case PDF -> download(
                    pdfExporter.toPdf(table),
                    filenameSlug + "-" + LocalDate.now() + ".pdf",
                    MediaType.APPLICATION_PDF);
            case JSON -> throw BusinessException.badRequest(
                    "JSON format must be returned by the controller directly, not the file factory",
                    "REPORT_FORMAT_JSON_NOT_FILE");
        };
    }

    private ResponseEntity<byte[]> download(byte[] body, String filename, MediaType type) {
        // RFC 5987 filename* so Arabic-in-filename (if ever used) and spaces survive; ascii fallback too.
        String disposition = "attachment; filename=\"" + filename + "\"; filename*=UTF-8''"
                + java.net.URLEncoder.encode(filename, StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(type)
                .body(body);
    }
}
