/**
 * Public API surface of the {@code systemconfig} module — the only package other modules may import
 * from systemconfig.
 *
 * <p>Contains exactly {@code ConfigFacade} (read-only, the cross-module entry point) and
 * {@code ConfigKey} (the shared key-name constants). Everything else — entity, repository, service,
 * controller, DTOs — lives under {@code systemconfig.internal} and is invisible to other modules,
 * enforced by Spring Modulith's {@code ApplicationModules.verify()}.</p>
 *
 * <p><strong>Writes are not here.</strong> Unlike {@code TrackingFacade}, this facade is read-only.
 * Config is written by an admin over HTTP (controller → service), never by another module, so there
 * is no write method to expose across a module boundary. The asymmetry is deliberate: tracking must
 * expose {@code ingest} because {@code sync} calls it; nothing calls systemconfig to change a value.</p>
 */
@org.springframework.modulith.NamedInterface("api")
package com.salesmanagement.systemconfig.api;
