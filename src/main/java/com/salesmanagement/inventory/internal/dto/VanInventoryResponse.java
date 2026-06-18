package com.salesmanagement.inventory.internal.dto;

import com.salesmanagement.inventory.internal.entity.VanInventoryItem;

/**
 * Response projection of a {@link VanInventoryItem} — one product line on a rep's van.
 *
 * @param id                 van-inventory row id
 * @param representativeId   owning sales rep's user id
 * @param representativeName owning sales rep's display name (resolved via {@code UserFacade})
 * @param productId          the product on the van
 * @param productName        product name (denormalised for display)
 * @param sku                product SKU (denormalised for display)
 * @param quantity           quantity currently on the van
 */
public record VanInventoryResponse(
        Long   id,
        Long   representativeId,
        String representativeName,
        Long   productId,
        String productName,
        String sku,
        int    quantity
) {
    /**
     * Maps a {@link VanInventoryItem} to its response projection. Must be called within
     * the service transaction so the lazy {@code product} association resolves.
     *
     * @param v                  the entity to map; must not be {@code null}
     * @param representativeName resolved rep name (may be {@code null} if lookup failed)
     */
    public static VanInventoryResponse from(VanInventoryItem v, String representativeName) {
        return new VanInventoryResponse(
                v.getId(),
                v.getRepresentativeId(),
                representativeName,
                v.getProduct().getId(),
                v.getProduct().getName(),
                v.getProduct().getSku(),
                v.getQuantity()
        );
    }
}