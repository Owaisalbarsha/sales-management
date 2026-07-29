package com.salesmanagement.reporting.internal.support;

import com.salesmanagement.shared.exception.BusinessException;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Renders a {@link ReportTable} to PDF bytes with openhtmltopdf. Generic across all reports; builds a
 * strict-XHTML document as a string and hands it to the renderer.
 *
 * <p><strong>Arabic RTL (mandatory, and the one landmine).</strong> The document is {@code dir="rtl"}
 * and embeds an Arabic-capable TrueType font from {@code resources/fonts/}. openhtmltopdf has NO
 * OpenType support, so the font must be a {@code .ttf}, never {@code .otf} — Noto Naskh Arabic ships as
 * TTF. Without the embedded font, Arabic glyphs render as blank boxes silently (no exception), so this
 * is verified visually, not by a passing build.</p>
 *
 * <p><strong>Strict XHTML.</strong> openhtmltopdf parses as XML, not lenient HTML: every tag closes,
 * and every text value is XML-escaped ({@code &amp; &lt; &gt; &quot;}). A raw {@code &} in a product
 * name would abort rendering. All dynamic content passes through {@link #escape} — this is also the
 * injection guard, since cell content originates from user-entered product/customer names.</p>
 */
@Component
public class PdfExporter {

    /**
     * Classpath location of the embedded Arabic TTF. Place the file here; it is loaded per render and
     * registered with the renderer under the family used by the stylesheet below.
     */
    private static final String ARABIC_FONT_PATH = "fonts/NotoNaskhArabic-Regular.ttf";
    private static final String FONT_FAMILY = "Noto Naskh Arabic";

    /**
     * Build a PDF for one table: an RTL A4 document with a heading and a bordered table.
     *
     * @return the PDF as a byte array, ready to stream as an HTTP download
     * @throws BusinessException 500 if the font is missing or the renderer fails
     */
    public byte[] toPdf(ReportTable table) {
        String html = buildXhtml(table);

        // openhtmltopdf needs the font as a file it can read; copy the classpath TTF to a temp file.
        Path fontFile = materialiseFont();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFont(fontFile.toFile(), FONT_FAMILY);
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (IOException e) {
            throw BusinessException.unprocessable(
                    "Failed to generate PDF export for report '" + table.title() + "'",
                    "REPORT_PDF_EXPORT_FAILED");
        } finally {
            try {
                Files.deleteIfExists(fontFile);
            } catch (IOException ignored) {
                // temp file cleanup is best-effort; the OS reaps /tmp regardless
            }
        }
    }

    private Path materialiseFont() {
        try (InputStream fontStream = new ClassPathResource(ARABIC_FONT_PATH).getInputStream()) {
            Path tmp = Files.createTempFile("report-font-", ".ttf");
            Files.copy(fontStream, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        } catch (IOException e) {
            throw BusinessException.unprocessable(
                    "Arabic report font not found on classpath at '" + ARABIC_FONT_PATH
                            + "' — PDF export cannot render Arabic without it",
                    "REPORT_PDF_FONT_MISSING");
        }
    }

    private String buildXhtml(ReportTable table) {
        StringBuilder body = new StringBuilder();
        body.append("<h1>").append(escape(table.title())).append("</h1>\n");
        body.append("<table>\n<thead>\n<tr>");
        for (String h : table.headers()) {
            body.append("<th>").append(escape(h)).append("</th>");
        }
        body.append("</tr>\n</thead>\n<tbody>\n");

        List<List<String>> rows = table.rows();
        if (rows.isEmpty()) {
            body.append("<tr><td colspan=\"").append(table.headers().size())
                .append("\" class=\"empty\">لا توجد بيانات</td></tr>\n"); // "no data"
        } else {
            for (List<String> row : rows) {
                body.append("<tr>");
                for (String cell : row) {
                    body.append("<td>").append(escape(cell == null ? "" : cell)).append("</td>");
                }
                body.append("</tr>\n");
            }
        }
        body.append("</tbody>\n</table>");

        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" lang="ar" dir="rtl">
                <head>
                <meta charset="UTF-8"/>
                <style>
                  @page { size: A4; margin: 1.5cm; }
                  body { font-family: "%s", sans-serif; direction: rtl; font-size: 11px; color: #1a1a1a; }
                  h1 { font-size: 16px; margin: 0 0 12px 0; }
                  table { width: 100%%; border-collapse: collapse; }
                  th, td { border: 1px solid #999; padding: 5px 7px; text-align: right; }
                  thead th { background: #f0f0f0; font-weight: bold; }
                  td.empty { text-align: center; color: #777; }
                </style>
                </head>
                <body>
                %s
                </body>
                </html>
                """.formatted(FONT_FAMILY, body);
    }

    /** XML-escape for strict-XHTML text nodes; also the injection guard for user-entered cell content. */
    private String escape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
