package com.salesmanagement.reporting.internal.support.ExportBuilder;

import com.salesmanagement.shared.exception.BusinessException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import com.salesmanagement.reporting.internal.support.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Renders a {@link ReportTable} to {@code .xlsx} bytes with Apache POI. Generic: it serves every
 * report, knowing nothing about the domain — just a title, headers, and string rows.
 *
 * <p>Stateless and thread-safe: each call builds its own {@link Workbook}. The workbook is closed in a
 * finally-equivalent (try-with-resources) so POI's temp buffers are always released, even on an IO
 * failure mid-write.</p>
 */
@Component
public class ExcelExporter {

    /** Excel caps a sheet name at 31 chars and forbids a handful of characters; POI's helper sanitises. */
    private static final int SHEET_NAME_MAX = 31;

    /**
     * Build an {@code .xlsx} for one table: a bold header row followed by the data rows, columns
     * auto-sized.
     *
     * @return the workbook as a byte array, ready to stream as an HTTP download
     * @throws BusinessException 500 if POI fails to serialise (should not happen for well-formed input)
     */
    public byte[] toXlsx(ReportTable table) {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            String safeName = WorkbookUtil.createSafeSheetName(
                    table.title().length() > SHEET_NAME_MAX
                            ? table.title().substring(0, SHEET_NAME_MAX)
                            : table.title());
            Sheet sheet = workbook.createSheet(safeName);

            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            List<String> headers = table.headers();
            Row headerRow = sheet.createRow(0);
            for (int c = 0; c < headers.size(); c++) {
                Cell cell = headerRow.createCell(c);
                cell.setCellValue(headers.get(c));
                cell.setCellStyle(headerStyle);
            }

            List<List<String>> rows = table.rows();
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<String> cells = rows.get(r);
                for (int c = 0; c < cells.size(); c++) {
                    row.createCell(c).setCellValue(cells.get(c) == null ? "" : cells.get(c));
                }
            }

            for (int c = 0; c < headers.size(); c++) {
                sheet.autoSizeColumn(c);
            }

            workbook.write(out);
            return out.toByteArray();

        } catch (IOException e) {
            throw BusinessException.unprocessable(
                    "Failed to generate Excel export for report '" + table.title() + "'",
                    "REPORT_EXCEL_EXPORT_FAILED");
        }
    }
}
