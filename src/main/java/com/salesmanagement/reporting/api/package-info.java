/**
 * Public API surface of the {@code reporting} module.
 *
 * <p>Empty by design, for now. Reporting is a pure <em>consumer</em>: it reads through every other
 * module's facade and exposes its results only over HTTP (its controllers), not as a Java API other
 * modules import. Nothing depends on reporting, so there is no published type here yet. The package
 * and its {@code @NamedInterface} exist so the module has a declared, if empty, boundary — and so the
 * day another module legitimately needs a reporting type (unlikely), there is an obvious home for it
 * that does not mean exposing {@code internal}.</p>
 *
 * <p>Reporting owns no schema and no entity: it is table-less, holds no Flyway migration, and never
 * writes. See the module's services for how it composes facade reads into reports.</p>
 */
@org.springframework.modulith.NamedInterface("api")
package com.salesmanagement.reporting.api;
