package com.salesmanagement.customer.internal;

/**
 * Outlet classification of a {@code Customer}.
 *
 */
public enum CustomerCategory {

    /** Single-location store selling directly to end consumers. */
    RETAIL,

    /** Buys in bulk for resale to other businesses. */
    WHOLESALE,

    /** Large self-service grocery outlet. */
    SUPERMARKET,

    /** Pharmacy / drugstore. */
    PHARMACY,

    /** Restaurant, cafe, or other food-service outlet. */
    RESTAURANT,

    /** Anything that does not fit the categories above. */
    OTHER
}