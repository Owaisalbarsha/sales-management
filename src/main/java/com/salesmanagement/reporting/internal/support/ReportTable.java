package com.salesmanagement.reporting.internal.support;

import java.util.List;

/**
 * The generic tabular shape every report is reduced to before export. One title, a header row, and
 * data rows of stringified cells.
 *
 * <p><strong>Why one shape (structural decision).</strong> All 15 reports export through a single
 * Excel exporter and a single PDF exporter rather than 15 bespoke ones. Each report service converts
 * its own typed DTOs into this flat table; the exporters know nothing about sales vs inventory vs
 * routes. None of the FRs need charts or multi-section layouts in the export — they need tabular
 * data — so a flat table is sufficient and keeps the export code at O(1) instead of O(reports).</p>
 *
 * <p>Cells are pre-formatted strings: the service decides how a date, a money value, or a percentage
 * should read (locale, decimals, {@code %} sign), because only the service knows the column's meaning.
 * The exporters render text faithfully and do not reinterpret it. Numeric alignment in Excel is a
 * known, accepted limitation of stringifying — if a committee wants true numeric cells for a specific
 * report, that report can graduate to a typed exporter later; it is not worth it for the MVP.</p>
 *
 * @param title   report title, shown as the sheet name / PDF heading
 * @param headers column headers, in order
 * @param rows    data rows; each inner list must match {@code headers} in length and order
 */
public record ReportTable(
        String             title,
        List<String>       headers,
        List<List<String>> rows
) {
    public ReportTable {
        if (headers == null || headers.isEmpty()) {
            throw new IllegalArgumentException("ReportTable requires at least one header column");
        }
        rows = (rows == null) ? List.of() : rows;
    }
}
