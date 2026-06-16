package com.salesmanagement.vanops.internal.enums;

/**
 * Lifecycle status of a {@code DemandOrder}.
 *
 * <p>Three states, in order:</p>
 * <ul>
 *   <li>{@code SUBMITTED} — sales manager submitted, warehouse had enough of everything,
 *       no adjustment was needed. Ready to load.</li>
 *   <li>{@code ADJUSTED} — submitted, but at least one line's {@code fulfilledQty} was
 *       trimmed below {@code requestedQty} because the warehouse did not have enough.
 *       Ready to load with the adjusted quantities.</li>
 *   <li>{@code LOADED} — warehouse manager confirmed the van has been physically loaded;
 *       stock has been moved warehouse → van via {@code InventoryFacade}. Terminal.</li>
 * </ul>
 *
 * <p>There is no {@code REJECTED} state by design — the system auto-adjusts down to
 * available stock rather than rejecting (decision from workflow review). A line that
 * is fully short ({@code fulfilledQty = 0}) is still part of an {@code ADJUSTED}
 * order; it simply contributes nothing on load.</p>
 */
public enum DemandOrderStatus {

    /** Submitted and fully fulfillable — every line's requested quantity is available. */
    SUBMITTED,

    /** Submitted, but at least one line was trimmed down to available warehouse stock. */
    ADJUSTED,

    /** Loaded onto the van — stock has been transferred. Terminal. */
    LOADED
}