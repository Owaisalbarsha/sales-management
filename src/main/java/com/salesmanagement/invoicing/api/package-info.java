/**
 * Public API surface of the {@code invoicing} module — the only package other modules may
 * import from invoicing.
 *
 * <p>Named interface {@code "api"} so Spring Modulith allows cross-module imports from here
 * while keeping {@code invoicing.internal} closed (verified by
 * {@code ApplicationModules.verify()}).</p>
 *
 * <p><strong>Contents (decision D19/D20):</strong> the three domain events invoicing publishes
 * on state transitions — {@link com.salesmanagement.invoicing.api.InvoiceSubmittedEvent},
 * {@link com.salesmanagement.invoicing.api.InvoiceApprovedEvent},
 * {@link com.salesmanagement.invoicing.api.InvoiceRejectedEvent}. These are declared here (not
 * in {@code internal}) so a future {@code notification} module can listen without depending on
 * invoicing internals.</p>
 *
 * <p>There is deliberately <em>no</em> {@code InvoicingFacade} yet: no module needs to call into
 * invoicing today. One will be added here when {@code reporting} needs read access — adding a
 * facade with zero consumers now would be dead code.</p>
 */
@org.springframework.modulith.NamedInterface("api")
package com.salesmanagement.invoicing.api;
