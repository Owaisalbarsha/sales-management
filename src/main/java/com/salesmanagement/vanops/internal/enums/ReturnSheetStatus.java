package com.salesmanagement.vanops.internal.enums;

/**
 * Lifecycle status of a {@code ReturnSheet}.
 *
 * <p>Two states:</p>
 * <ul>
 *   <li>{@code DRAFT} — created (by rep or WH manager), lines listed, but stock has not
 *       moved yet. Editable.</li>
 *   <li>{@code COMPLETED} — warehouse manager confirmed; stock has been moved
 *       van → warehouse via {@code InventoryFacade}, and empty van rows have been
 *       deleted. Terminal.</li>
 * </ul>
 */
public enum ReturnSheetStatus {

    /** Lines drafted but stock has not moved yet. */
    DRAFT,

    /** Confirmed and applied — van stock returned to the warehouse. Terminal. */
    COMPLETED
}