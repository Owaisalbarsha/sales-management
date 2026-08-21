package com.salesmanagement.systemconfig.internal.entity;

/**
 * The value types {@code system_config} currently supports.
 *
 * <p>{@code INT} only today — the single live key is an int. The enum exists so the write path can
 * validate a submitted value against a declared type instead of trusting a bare string, and so adding
 * {@code BOOLEAN} / {@code DURATION_ISO} later is an additive change (new enum member, new parse arm,
 * widen the DB CHECK) rather than a refactor. Kept in {@code internal} because no other module needs
 * to reference the type — the facade exposes typed getters, not the raw type.</p>
 */
public enum ValueType {

    /** A signed 32-bit integer, parsed with {@link Integer#parseInt(String)}. */
    INT
}
