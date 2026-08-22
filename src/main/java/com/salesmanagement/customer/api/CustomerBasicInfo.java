package com.salesmanagement.customer.api;

/**
 * Minimal public projection of a customer — id, name, and owning territory — for consumers that need
 * to enumerate customers without the full {@link CustomerInfo} (address, coords, status). Used by the
 * reporting module's dormant-customers report, which starts from the full active-customer set.
 *
 * @param id          the customer id
 * @param name        the customer name
 * @param territoryId the owning territory id
 */
public record CustomerBasicInfo(
        Long   id,
        String name,
        Long   territoryId
) {}
