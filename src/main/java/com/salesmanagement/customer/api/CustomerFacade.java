package com.salesmanagement.customer.api;

import com.salesmanagement.customer.internal.Customer;
import com.salesmanagement.customer.internal.enums.CustomerStatus;
import com.salesmanagement.customer.internal.repository.CustomerRepository;
import com.salesmanagement.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Public API surface of the customer module — the only type other modules may
 * import from customer. Mirrors {@code TerritoryFacade} and {@code UserFacade}.
 *
 * <p>Read-only by design: {@code routing}, {@code visit}, and {@code invoicing}
 * need to resolve and validate customers, never to mutate them. All writes go
 * through the REST API. The facade reaches into the module's own
 * {@code internal} package (allowed within a module); it never leaks the
 * {@link Customer} entity or the internal enums outward — callers receive a
 * {@link CustomerInfo} or a primitive.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomerFacade {

    private final CustomerRepository customerRepository;

    /**
     * Returns the public projection of a customer.
     *
     * @param customerId the customer id
     * @return the customer info
     * @throws BusinessException 404 if no customer has this id
     */
    public CustomerInfo getCustomerInfo(Long customerId) {
        Customer c = findOrThrow(customerId);
        return new CustomerInfo(
                c.getId(),
                c.getName(),
                c.getTerritoryId(),
                c.getAddress(),
                c.getPhone(),
                c.getLatitude(),
                c.getLongitude(),
                c.getStatus() == CustomerStatus.ACTIVE);
    }

    /**
     * Whether a customer with the given id exists. Used by {@code routing} and
     * {@code visit} to reject a bad {@code customerId} with their own domain error
     * rather than failing on a foreign-key violation at insert time.
     *
     * @param customerId the customer id to check
     * @return {@code true} if the customer exists
     */
    public boolean exists(Long customerId) {
        return customerRepository.existsById(customerId);
    }

    /**
     * Whether the customer is currently ACTIVE. {@code invoicing} calls this to
     * enforce FR-95 — invoices for a deactivated customer are rejected.
     *
     * @param customerId the customer id
     * @return {@code true} if the customer is ACTIVE
     * @throws BusinessException 404 if no customer has this id
     */
    public boolean isActive(Long customerId) {
        return findOrThrow(customerId).getStatus() == CustomerStatus.ACTIVE;
    }

    private Customer findOrThrow(Long customerId) {
        return customerRepository.findById(customerId)
                .orElseThrow(() -> BusinessException.notFound(
                        "Customer not found: " + customerId, "CUSTOMER_NOT_FOUND"));
    }

    /**
     * Resolves many customer ids to their names in ONE query — the batch the reporting module uses to
     * label customer-grouped reports (FR-118/119) without an N+1 loop. Ids with no matching customer
     * are simply absent from the returned map; the caller renders those as a placeholder rather than
     * dropping the row.
     *
     * @param customerIds the ids to resolve
     * @return id → name for every id that exists; empty map if {@code customerIds} is empty
     */
    @Transactional(readOnly = true)
    public Map<Long, String> getNamesByIds(Collection<Long> customerIds) {
        if (customerIds == null || customerIds.isEmpty()) {
            return Map.of();
        }
        return customerRepository.findAllById(customerIds).stream()
                .collect(Collectors.toMap(Customer::getId, Customer::getName));
    }

    /**
     * Batch-resolves customer names for a set of ids. Returns a map of id -> name.
     * Ids not found are absent from the map (no exception).
     */
    public Map<Long, String> getCustomerNames(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return customerRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Customer::getId, Customer::getName));
    }

    /**
     * Batch-resolves customer ids to their owning territory ids in ONE query — the map the reporting
     * module uses to regroup customer-level sales into territory-level sales (invoices carry customerId,
     * not territoryId, so the report resolves the link here). Ids with no matching customer are absent.
     *
     * @param customerIds the ids to resolve
     * @return customerId → territoryId for every id that exists; empty map if input is empty
     */
    @Transactional(readOnly = true)
    public Map<Long, Long> getTerritoryIdsByIds(Collection<Long> customerIds) {
        if (customerIds == null || customerIds.isEmpty()) {
            return Map.of();
        }
        return customerRepository.findAllById(customerIds).stream()
                .collect(Collectors.toMap(Customer::getId, Customer::getTerritoryId));
    }

    /**
     * Count of ACTIVE customers per territory, computed in ONE grouped query — backs the
     * customers-per-territory report. Territories with zero active customers are absent from this map;
     * the report supplies them as zero by starting from the full territory list.
     *
     * @return territoryId → active-customer count
     */
    @Transactional(readOnly = true)
    public Map<Long, Long> countActiveCustomersByTerritory() {
        return customerRepository.countActiveByTerritory(CustomerStatus.ACTIVE).stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (Long) row[1]));
    }

    /**
     * All ACTIVE customers as (id, name, territoryId) — used by the dormant-customers report, which
     * must start from every active customer (including those who have never invoiced) and then subtract
     * the recently-active ones. Ordered by name.
     */
    @Transactional(readOnly = true)
    public List<CustomerBasicInfo> getActiveCustomers() {
        return customerRepository.findByStatus(CustomerStatus.ACTIVE).stream()
                .map(c -> new CustomerBasicInfo(c.getId(), c.getName(), c.getTerritoryId()))
                .sorted(java.util.Comparator.comparing(CustomerBasicInfo::name,
                        java.util.Comparator.nullsLast(String::compareTo)))
                .toList();
    }
}