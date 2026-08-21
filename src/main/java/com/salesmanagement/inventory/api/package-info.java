/**
 * Public API of the {@code inventory} module.
 *
 * <p>Declared as a Spring Modulith {@link org.springframework.modulith.NamedInterface}
 * named {@code "api"} so that other modules may depend on the types here
 * ({@code InventoryFacade}, {@code ProductInfo}) while everything under
 * {@code inventory.internal} stays encapsulated and is rejected by
 * {@code ApplicationModules.verify()}. This matches how the identity, territory, and
 * customer modules expose their own {@code api} packages.</p>
 */
@NamedInterface("api")
package com.salesmanagement.inventory.api;

import org.springframework.modulith.NamedInterface;
