package com.salesmanagement.invoicing.internal.enums;

/**
 * The kinds of Electronic Proof of Delivery artifact an invoice can carry (decision D16).
 *
 * <ul>
 *   <li>{@code SIGNATURE} — the customer's signature, drawn on the rep's touchscreen and
 *       captured as an image. Proof the customer received the goods.</li>
 *   <li>{@code DELIVERY_PHOTO} — a photo of the delivered goods (Amazon-style). Second
 *       independent proof artifact.</li>
 * </ul>
 *
 * <p>Both are mandatory at submit in the MVP. The enum is intentionally extensible —
 * a {@code GOODS_PHOTO} or similar can be added later with no schema change, because
 * artifacts live in their own child table ({@code epod_artifacts}) rather than as columns
 * on the invoice. The DB {@code chk_epod_artifacts_type} CHECK mirrors this set.</p>
 */
public enum EpodArtifactType {
    SIGNATURE,
    DELIVERY_PHOTO
}
