package com.salesmanagement.vanops.internal.dto;

import com.salesmanagement.inventory.api.ProductInfo;
import com.salesmanagement.vanops.internal.entity.ReturnSheet;
import com.salesmanagement.vanops.internal.entity.ReturnSheetLine;
import com.salesmanagement.vanops.internal.enums.ReturnSheetStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Response projection of a {@link ReturnSheet}. Lines are enriched with product
 * {@code name} and {@code sku}, same pattern as {@link DemandOrderResponse}.
 */
public record ReturnSheetResponse(
        Long                   id,
        Long                   representativeId,
        LocalDate              returnDate,
        ReturnSheetStatus      status,
        List<Line>             lines,
        Instant                createdAt,
        Instant                updatedAt
) {
    public record Line(
            Long   productId,
            String productName,
            String sku,
            int    quantity
    ) {}

    public static ReturnSheetResponse from(ReturnSheet sheet, Map<Long, ProductInfo> productInfos) {
        List<Line> lines = sheet.getLines().stream()
                .map(l -> toLine(l, productInfos))
                .toList();

        return new ReturnSheetResponse(
                sheet.getId(),
                sheet.getRepresentativeId(),
                sheet.getReturnDate(),
                sheet.getStatus(),
                lines,
                sheet.getCreatedAt(),
                sheet.getUpdatedAt()
        );
    }

    private static Line toLine(ReturnSheetLine l, Map<Long, ProductInfo> productInfos) {
        ProductInfo p = productInfos.get(l.getProductId());
        return new Line(
                l.getProductId(),
                p != null ? p.name() : null,
                p != null ? p.sku()  : null,
                l.getQuantity()
        );
    }
}