/**
 * Shared kernel — not a domain module, but a dependency of all modules.
 *
 * Marked as OPEN so Spring Modulith exposes all types within this package
 * and its sub-packages (api/, domain/, exception/, security/, config/) to
 * every other module without violation.
 *
 * Without this annotation, Spring Modulith treats shared as a regular module
 * and flags every import of ApiResponse, BusinessException, BaseEntity,
 * UserRole, etc. as a dependency on a "non-exposed type" — which is incorrect.
 * shared is intentionally a shared kernel, not an encapsulated module.
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
package com.salesmanagement.shared;

import org.springframework.modulith.ApplicationModule;