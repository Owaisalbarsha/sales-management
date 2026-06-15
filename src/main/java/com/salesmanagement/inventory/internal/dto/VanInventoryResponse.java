package com.salesmanagement.inventory.internal.dto;

import com.salesmanagement.inventory.internal.entity.VanInventoryItem;

/**
 * Response projection of a {@link VanInventoryItem} — one product line on a rep's van.
 *
 * @param id               van-inventory row id
 * @param representativeId owning sales rep's user id
 * @param productId        the product on the van
 * @param productName      product name (denormalised for display)
 * @param sku              product SKU (denormalised for display)
 * @param quantity         quantity currently on the van
 */
public record VanInventoryResponse(
        Long   id,
        Long   representativeId,
        Long   productId,
        String productName,
        String sku,
        int    quantity
) {
    /**
     * Maps a {@link VanInventoryItem} to its response projection. Must be called within
     * the service transaction so the lazy {@code product} association resolves.
     *
     * @param v the entity to map; must not be {@code null}
     * @return the corresponding response DTO
     */
    public static VanInventoryResponse from(VanInventoryItem v) {
        return new VanInventoryResponse(
                v.getId(),
                v.getRepresentativeId(),
                v.getProduct().getId(),
                v.getProduct().getName(),
                v.getProduct().getSku(),
                v.getQuantity()
        );
    }
}
