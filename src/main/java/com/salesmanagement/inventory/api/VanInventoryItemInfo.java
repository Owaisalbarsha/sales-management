package com.salesmanagement.inventory.api;

/**
 * Immutable public projection of one van-inventory line — what other modules see when
 * they ask "what is on this rep's van right now?". Safe to pass across module boundaries.
 *
 * <p>Mirrors {@link ProductInfo} in shape and purpose: a small record containing only what
 * downstream modules need, never the internal {@code VanInventoryItem} entity or its
 * response DTO. Used by {@code vanops} (auto-generated return sheets) and reserved for any
 * future module that needs a read-only snapshot of a rep's loaded stock.</p>
 *
 * @param productId the product loaded on the van
 * @param quantity  current on-van quantity (always {@code > 0} — empty rows are not surfaced)
 */
public record VanInventoryItemInfo(
        Long productId,
        int  quantity
) {}
