package com.salesmanagement.inventory.internal.dto;

import com.salesmanagement.inventory.internal.entity.StockCount;
import com.salesmanagement.inventory.internal.entity.StockCountLine;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Internal REST response for a stock count — the full view returned to the ADMIN/WAREHOUSE_MANAGER
 * who owns it (distinct from the cross-module {@code StockVarianceInfo} the facade hands to
 * {@code reporting}).
 *
 * <p>While the count is DRAFT, each line's {@code recordedQuantity} and {@code variance} are
 * {@code null} — there is no recorded snapshot yet. Once FINALIZED, both are populated;
 * {@code variance = countedQuantity - recordedQuantity}, computed here (never stored).</p>
 */
public record StockCountResponse(
        Long        id,
        Long        countedById,
        LocalDate   countDate,
        String      status,
        Instant     finalizedAt,
        List<Line>  lines
) {
    public record Line(
            Long    productId,
            String  productName,
            String  sku,
            int     countedQuantity,
            Integer recordedQuantity,
            Integer variance
    ) {}

    public static StockCountResponse from(StockCount count) {
        List<Line> lines = count.getLines().stream()
                .map(StockCountResponse::toLine)
                .toList();
        return new StockCountResponse(
                count.getId(),
                count.getCountedById(),
                count.getCountDate(),
                count.getStatus().name(),
                count.getFinalizedAt(),
                lines);
    }

    private static Line toLine(StockCountLine l) {
        Integer recorded = l.getRecordedQuantity();
        Integer variance = (recorded == null) ? null : l.getCountedQuantity() - recorded;
        return new Line(
                l.getProduct().getId(),
                l.getProduct().getName(),
                l.getProduct().getSku(),
                l.getCountedQuantity(),
                recorded,
                variance);
    }
}
