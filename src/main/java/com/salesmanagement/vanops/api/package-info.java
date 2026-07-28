/**
 * Public API surface of the {@code vanops} module — the only package other modules may import from
 * vanops.
 *
 * <p>vanops was a leaf module until reporting needed it: it drove stock movements through
 * {@code InventoryFacade} but exposed nothing itself. This package is its first published surface,
 * added so the {@code reporting} module can read movement and fulfilment aggregates (FR-122,
 * fill-rate) without touching vanops' entities or tables — the same boundary every other cross-module
 * read respects.</p>
 *
 * <p>Read-only: reporting only reads. Nothing here writes; demand orders and return sheets are still
 * mutated exclusively through vanops' own HTTP controllers.</p>
 */
@org.springframework.modulith.NamedInterface("api")
package com.salesmanagement.vanops.api;
