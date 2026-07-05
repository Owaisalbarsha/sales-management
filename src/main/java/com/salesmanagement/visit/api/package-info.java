/**
 * Public API of the {@code visit} module — the only package other modules may depend on.
 *
 * <p>Exposes {@link com.salesmanagement.visit.api.VisitFacade} and its read model
 * {@link com.salesmanagement.visit.api.VisitInfo}. The {@code @NamedInterface} annotation marks
 * this package as the module's published interface so that
 * {@code ApplicationModules.verify()} allows cross-module access here and forbids it against
 * {@code visit.internal}.</p>
 *
 * <p>Note: the events {@code RouteExecutionStarted} and {@code RouteVisitsFinalized} that
 * {@code visit} publishes are declared in {@code routing.api}, not here, because they are facts
 * about a route and doing so keeps the module dependency one-directional (visit -&gt; routing).</p>
 */
@org.springframework.modulith.NamedInterface("api")
package com.salesmanagement.visit.api;
