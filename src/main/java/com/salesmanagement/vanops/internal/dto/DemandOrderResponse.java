package com.salesmanagement.vanops.internal.dto;

import com.salesmanagement.inventory.api.ProductInfo;
import com.salesmanagement.vanops.internal.entity.DemandOrder;
import com.salesmanagement.vanops.internal.entity.DemandOrderLine;
import com.salesmanagement.vanops.internal.enums.DemandOrderStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Response projection of a {@link DemandOrder}, returned by every demand-order endpoint.
 *
 * <p>Each line carries the product's {@code name} and {@code sku} (enriched from
 * {@code InventoryFacade}) so the client does not need to make a second call per product.
 * {@code productInfos} is supplied as a map by the service — the static {@code from()}
 * builds the response by looking up each line's product in it.</p>
 */
public record DemandOrderResponse(
        Long                   id,
        Long                   salesManagerId,
        String                 salesManagerName,
        Long                   representativeId,
        String                 representativeName,
        LocalDate              orderDate,
        DemandOrderStatus      status,
        List<Line>             lines,
        Instant                createdAt,
        Instant                updatedAt
) {
    /**
     * One line, enriched with product display info.
     *
     * @param productId    cross-module product id
     * @param productName  enriched product name
     * @param sku          enriched product SKU
     * @param requestedQty quantity asked for
     * @param fulfilledQty quantity actually fulfilled (≤ requested)
     * @param shortBy      {@code requestedQty - fulfilledQty} (≥ 0; computed)
     */
    public record Line(
            Long   productId,
            String productName,
            String sku,
            int    requestedQty,
            int    fulfilledQty,
            int    shortBy
    ) {}

    /**
     * Maps a {@link DemandOrder} entity to its response projection.
     *
     * @param order              the order to map; must not be {@code null}
     * @param productInfos       product-id → {@link ProductInfo} for every line's product
     * @param salesManagerName   resolved name of the submitter (may be {@code null} if unknown)
     * @param representativeName resolved name of the target rep (may be {@code null} if unknown)
     */
    public static DemandOrderResponse from(DemandOrder order,
                                           Map<Long, ProductInfo> productInfos,
                                           String salesManagerName,
                                           String representativeName) {
        List<Line> lines = order.getLines().stream()
                .map(l -> toLine(l, productInfos))
                .toList();

        return new DemandOrderResponse(
                order.getId(),
                order.getSalesManagerId(),
                salesManagerName,
                order.getRepresentativeId(),
                representativeName,
                order.getOrderDate(),
                order.getStatus(),
                lines,
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }

    private static Line toLine(DemandOrderLine l, Map<Long, ProductInfo> productInfos) {
        ProductInfo p = productInfos.get(l.getProductId());
        return new Line(
                l.getProductId(),
                p != null ? p.name() : null,
                p != null ? p.sku()  : null,
                l.getRequestedQty(),
                l.getFulfilledQty(),
                l.getRequestedQty() - l.getFulfilledQty()
        );
    }
}