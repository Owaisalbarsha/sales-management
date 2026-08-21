package com.salesmanagement.invoicing.internal.service;

import com.salesmanagement.invoicing.internal.dto.InvoicePdfView;
import com.salesmanagement.invoicing.internal.entity.EpodArtifact;
import com.salesmanagement.invoicing.internal.entity.Invoice;
import com.salesmanagement.invoicing.internal.entity.InvoiceLineItem;
import com.salesmanagement.shared.exception.BusinessException;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import com.openhtmltopdf.bidi.support.ICUBidiReorderer;
import com.openhtmltopdf.bidi.support.ICUBidiSplitter;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders a single invoice aggregate to a print-ready PDF for the customer.
 *
 * <p>Lives in invoicing (not reporting) because the PDF needs the full aggregate — header, all
 * line items, server-computed totals, and ePOD proof metadata — which is invoicing's internal
 * state. Reporting sees only flat aggregate DTOs across the facade boundary and deliberately
 * cannot reach these internals, so invoicing renders its own invoice.</p>
 *
 * <p><strong>Money:</strong> every amount is formatted here from the invoice's <em>stored</em>
 * subtotals and total ({@code BigDecimal}, {@code HALF_UP}, scale 2). Nothing is recomputed —
 * the PDF must show exactly what was persisted and what other endpoints return.</p>
 *
 * <p><strong>Rendering:</strong> a Thymeleaf HTML template is rendered to a string, then
 * openhtmltopdf converts it to PDF. The Arabic font is registered on the renderer so RTL Arabic
 * glyphs embed correctly; the {@code @font-face} family in the template must match
 * {@link #FONT_FAMILY}.</p>
 */
@Slf4j
@Service
public class InvoicePdfService {

    private static final int MONEY_SCALE = 2;

    /** Must match the family declared in the template's @font-face rule. */
    private static final String FONT_FAMILY = "NotoNaskhArabic";
    /** Classpath location of the embedded Arabic TTF. */
    private static final String FONT_PATH = "/fonts/NotoNaskhArabic-Regular.ttf";
    /** Base URI so the template's relative "fonts/..." url resolves on the classpath. */
    private static final String CLASSPATH_BASE_URI = "classpath:/";

    private static final String TEMPLATE = "invoicing/invoice-pdf";
    private static final String CURRENCY_SYMBOL = "";  // set to a currency string if required

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH);
    // Instant rendered with an explicit offset (project rule: never a bare Z-as-local).
    private static final DateTimeFormatter INSTANT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx", Locale.ENGLISH);
    /** Offset used to render proof/generation timestamps. Adjust to the deployment zone if needed. */
    private static final ZoneOffset RENDER_OFFSET = ZoneOffset.of("+03:00");

    private final TemplateEngine templateEngine;

    public InvoicePdfService() {
        // Self-contained Thymeleaf engine — offline HTML rendering, no Spring MVC view resolution.
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);

        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        this.templateEngine = engine;
    }

    /**
     * Renders the given invoice aggregate to PDF bytes. Names are resolved and passed in by the
     * caller (the invoice service already has the enrichment maps).
     *
     * @param invoice            the fully loaded aggregate (lines + ePOD already fetched)
     * @param productNames       product-id → display name for each line
     * @param productSkus        product-id → SKU for each line
     * @param customerName       resolved customer name (may be {@code null})
     * @param representativeName resolved rep name (may be {@code null})
     * @return the PDF as a byte array
     * @throws BusinessException 422 if rendering fails
     */
    public byte[] render(Invoice invoice,
                         Map<Long, String> productNames,
                         Map<Long, String> productSkus,
                         String customerName,
                         String representativeName) {
        InvoicePdfView view = toView(invoice, productNames, productSkus, customerName, representativeName);

        Context context = new Context(new Locale("ar"));
        context.setVariable("view", view);
        String html = templateEngine.process(TEMPLATE, context);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            // Register the Arabic font so RTL glyphs embed; family name must match the template.
            builder.useUnicodeBidiSplitter(new ICUBidiSplitter.ICUBidiSplitterFactory());
            builder.useUnicodeBidiReorderer(new ICUBidiReorderer());
            builder.defaultTextDirection(PdfRendererBuilder.TextDirection.RTL);
            builder.useFont(
                    () -> getClass().getResourceAsStream(FONT_PATH),
                    FONT_FAMILY);
            builder.withHtmlContent(html, CLASSPATH_BASE_URI);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Failed to render invoice PDF id={}", invoice.getId(), e);
            throw BusinessException.unprocessable(
                    "Could not generate invoice PDF", "INVOICE_PDF_RENDER_FAILED");
        }
    }

    // ── mapping ──────────────────────────────────────────────────────────────

    private InvoicePdfView toView(Invoice invoice,
                                  Map<Long, String> productNames,
                                  Map<Long, String> productSkus,
                                  String customerName,
                                  String representativeName) {
        List<InvoicePdfView.Line> lines = invoice.getLines().stream()
                .map(l -> new InvoicePdfView.Line(
                        nvl(productNames.get(l.getProductId()), "—"),
                        nvl(productSkus.get(l.getProductId()), "—"),
                        String.valueOf(l.getQuantity()),
                        money(l.getPrice()),
                        money(l.getDiscount()),
                        money(l.getSubtotal())))
                .toList();

        List<InvoicePdfView.EpodProof> proofs = invoice.getEpodArtifacts().stream()
                .map(this::toProof)
                .toList();

        return new InvoicePdfView(
                "INV-" + invoice.getId(),
                invoice.getStatus().name(),
                invoice.getInvoiceDate().format(DATE_FMT),
                nvl(customerName, "—"),
                nvl(representativeName, "—"),
                lines,
                money(invoice.getTotalAmount()),
                CURRENCY_SYMBOL,
                proofs,
                java.time.Instant.now().atOffset(RENDER_OFFSET).format(INSTANT_FMT));
    }

    private InvoicePdfView.EpodProof toProof(EpodArtifact a) {
        String coords = (a.getLatitude() != null && a.getLongitude() != null)
                ? a.getLatitude().toPlainString() + ", " + a.getLongitude().toPlainString()
                : "—";
        String hashShort = a.getHash() != null && a.getHash().length() >= 12
                ? a.getHash().substring(0, 12) + "…"
                : nvl(a.getHash(), "—");
        return new InvoicePdfView.EpodProof(
                a.getType().name(),
                a.getCapturedAt().atOffset(RENDER_OFFSET).format(INSTANT_FMT),
                coords,
                hashShort);
    }

    /** Formats stored money at scale 2, HALF_UP. Display-only; does not alter persisted values. */
    private static String money(BigDecimal v) {
        BigDecimal value = v == null ? BigDecimal.ZERO : v;
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    private static String nvl(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
