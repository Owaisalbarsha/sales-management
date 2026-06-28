/**
 * Public contract of the {@code routing} module. Everything other modules are allowed to
 * import lives here: {@link com.salesmanagement.routing.api.RoutingFacade} and its read-only
 * projections. Everything else is in {@code routing.internal} and is invisible across the
 * module boundary (Spring Modulith enforces this in the ApplicationModules.verify() test).
 */
@NamedInterface("api")
package com.salesmanagement.routing.api;

import org.springframework.modulith.NamedInterface;
