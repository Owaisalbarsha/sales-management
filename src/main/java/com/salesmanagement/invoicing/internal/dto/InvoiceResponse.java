package com.salesmanagement.invoicing.internal.dto;

import com.salesmanagement.inventory.api.ProductInfo;
import com.salesmanagement.invoicing.internal.entity.EpodArtifact;
import com.salesmanagement.invoicing.internal.entity.Invoice;
import com.salesmanagement.invoicing.internal.entity.InvoiceLineItem;
import com.salesmanagement.invoicing.internal.enums.EpodArtifactType;
import com.salesmanagement.invoicing.internal.enums.InvoiceStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Response projection of an {@link Invoice}, returned by every invoice endpoint.
 *
 * <p>Each line carries the product's {@code name} and {@code sku} (enriched from
 * {@code InventoryFacade}) so the client needs no second call per product. Customer and user
 * names are resolved once by the service and passed in. The static {@link #from} builds the
 * whole projection; the service supplies the lookup maps/names.</p>
 *
 * <p>ePOD artifacts are surfaced as metadata only — {@code type}, {@code url}, {@code hash},
 * coordinates, {@code capturedAt}. The file bytes are fetched separately via the {@code url}.</p>
 */
public record InvoiceResponse(
        Long              id,
        Long              customerId,
        String            customerName,
        Long              representativeId,
        String            representativeName,
        Long              visitId,
        LocalDate         invoiceDate,
        BigDecimal        totalAmount,
        InvoiceStatus     status,
        String            rejectionReason,
        Long              reviewedById,
        String            reviewedByName,
        String            clientUuid,
        List<Line>        lines,
        List<Epod>        epodArtifacts,
        Instant           createdAt,
        Instant           updatedAt
) {
    /**
     * One line, enriched with product display info.
     *
     * @param productId   cross-module product id
     * @param productName enriched product name (may be {@code null} if the product vanished)
     * @param sku         enriched product SKU (may be {@code null})
     * @param quantity    units sold
     * @param price       captured unit price
     * @param discount    line-level fixed discount
     * @param subtotal    quantity*price - discount
     */
    public record Line(
            Long       productId,
            String     productName,
            String     sku,
            int        quantity,
            BigDecimal price,
            BigDecimal discount,
            BigDecimal subtotal
    ) {}

    /**
     * One ePOD artifact's metadata (the file itself is fetched via {@code url}).
     *
     * @param type       SIGNATURE or DELIVERY_PHOTO
     * @param url        pointer to the stored file
     * @param hash       tamper-detection hash
     * @param latitude   capture latitude (may be {@code null})
     * @param longitude  capture longitude (may be {@code null})
     * @param capturedAt UTC instant of capture
     */
    public record Epod(
            EpodArtifactType type,
            String           url,
            String           hash,
            BigDecimal       latitude,
            BigDecimal       longitude,
            Instant          capturedAt
    ) {}

    /**
     * Maps an {@link Invoice} entity to its response projection.
     *
     * @param invoice            the invoice to map; must not be {@code null}
     * @param productInfos       product-id → {@link ProductInfo} for every line's product
     * @param customerName       resolved customer name (may be {@code null} if unknown)
     * @param representativeName resolved rep name (may be {@code null} if unknown)
     * @param reviewedByName     resolved reviewer name (may be {@code null}; also null if unreviewed)
     */
    public static InvoiceResponse from(Invoice invoice,
                                       Map<Long, ProductInfo> productInfos,
                                       String customerName,
                                       String representativeName,
                                       String reviewedByName) {
        List<Line> lines = invoice.getLines().stream()
                .map(l -> toLine(l, productInfos))
                .toList();

        List<Epod> epod = invoice.getEpodArtifacts().stream()
                .map(InvoiceResponse::toEpod)
                .toList();

        return new InvoiceResponse(
                invoice.getId(),
                invoice.getCustomerId(),
                customerName,
                invoice.getRepresentativeId(),
                representativeName,
                invoice.getVisitId(),
                invoice.getInvoiceDate(),
                invoice.getTotalAmount(),
                invoice.getStatus(),
                invoice.getRejectionReason(),
                invoice.getReviewedById(),
                reviewedByName,
                invoice.getClientUuid(),
                lines,
                epod,
                invoice.getCreatedAt(),
                invoice.getUpdatedAt()
        );
    }

    private static Line toLine(InvoiceLineItem l, Map<Long, ProductInfo> productInfos) {
        ProductInfo p = productInfos.get(l.getProductId());
        return new Line(
                l.getProductId(),
                p != null ? p.name() : null,
                p != null ? p.sku()  : null,
                l.getQuantity(),
                l.getPrice(),
                l.getDiscount(),
                l.getSubtotal()
        );
    }

    private static Epod toEpod(EpodArtifact a) {
        return new Epod(
                a.getType(),
                a.getUrl(),
                a.getHash(),
                a.getLatitude(),
                a.getLongitude(),
                a.getCapturedAt()
        );
    }
}
