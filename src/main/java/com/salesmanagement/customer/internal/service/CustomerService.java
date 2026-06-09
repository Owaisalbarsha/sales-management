package com.salesmanagement.customer.internal.service;

import com.salesmanagement.customer.internal.Customer;
import com.salesmanagement.customer.internal.CustomerStatus;
import com.salesmanagement.customer.internal.dto.CreateCustomerRequest;
import com.salesmanagement.customer.internal.dto.CustomerResponse;
import com.salesmanagement.customer.internal.dto.UpdateCustomerRequest;
import com.salesmanagement.customer.internal.repository.CustomerRepository;
import com.salesmanagement.shared.api.PageResponse;
import com.salesmanagement.shared.exception.BusinessException;
import com.salesmanagement.territory.api.TerritoryFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for the customer module.
 *
 * <p>Owns all customer operations and is the only writer of the {@link Customer}
 * entity. Mutating methods are transactional; reads are {@code readOnly}.</p>
 *
 * <p><strong>Cross-module dependency:</strong> {@link TerritoryFacade} is the only
 * thing this module imports from {@code territory}. It is used to verify that a
 * {@code territoryId} exists <em>before</em> insert/update, so a bad id produces a
 * clean domain error instead of a raw foreign-key violation. The reverse direction
 * (territory deletion) is handled in {@code TerritoryService} by letting the FK
 * reject the delete — neither module asks the other to count rows.</p>
 *
 * <p>Error contract (all via {@link BusinessException}, mapped to HTTP by
 * {@code GlobalExceptionHandler}):</p>
 * <ul>
 *   <li>400 BAD_REQUEST — empty update, or {@code territoryId} that does not exist</li>
 *   <li>404 NOT_FOUND — customer id does not exist</li>
 *   <li>409 CONFLICT — redundant activate/deactivate, or delete of a customer
 *       still referenced by visits, invoices, or route assignments</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final TerritoryFacade territoryFacade;

    /**
     * Creates a customer after verifying the target territory exists.
     *
     * @param request validated creation request
     * @return the created customer as a response DTO
     * @throws BusinessException 400 if {@code territoryId} does not exist
     */
    @Transactional
    public CustomerResponse create(CreateCustomerRequest request) {
        requireTerritory(request.territoryId());

        Customer saved = customerRepository.save(new Customer(
                request.territoryId(),
                request.name(),
                request.address(),
                request.phone(),
                request.latitude(),
                request.longitude(),
                request.category()));

        log.info("Created customer id={} name='{}' territoryId={}",
                saved.getId(), saved.getName(), saved.getTerritoryId());
        return CustomerResponse.from(saved);
    }

    /**
     * Returns one customer by id.
     *
     * @throws BusinessException 404 if no customer has this id
     */
    public CustomerResponse getById(Long id) {
        return CustomerResponse.from(findOrThrow(id));
    }

    /**
     * Returns a page of customers, optionally filtered by territory and/or status.
     *
     * @param territoryId optional territory filter ({@code null} = all)
     * @param status      optional status filter ({@code null} = all)
     * @param pageable    pagination/sort built from the request's {@code PageRequest}
     * @return a page of customer response DTOs in the unified page envelope
     */
    public PageResponse<CustomerResponse> list(Long territoryId,
                                               CustomerStatus status,
                                               String q,
                                               Pageable pageable) {
        String normalized = (q == null || q.isBlank()) ? null : q.trim();
        return PageResponse.of(
                customerRepository.search(territoryId, status, normalized, pageable)
                        .map(CustomerResponse::from));
    }

    /**
     * Partially updates a customer. Only non-null fields are applied.
     *
     * @throws BusinessException 400 if the update is empty or the new
     *                           {@code territoryId} does not exist; 404 if the
     *                           customer id does not exist
     */
    @Transactional
    public CustomerResponse update(Long id, UpdateCustomerRequest request) {
        if (request.isEmpty()) {
            throw BusinessException.badRequest(
                    "At least one field must be provided", "EMPTY_UPDATE");
        }

        Customer customer = findOrThrow(id);

        if (request.territoryId() != null) {
            requireTerritory(request.territoryId());
            customer.setTerritoryId(request.territoryId());
        }
        if (request.name() != null)      customer.setName(request.name());
        if (request.address() != null)   customer.setAddress(request.address());
        if (request.phone() != null)     customer.setPhone(request.phone());
        if (request.latitude() != null)  customer.setLatitude(request.latitude());
        if (request.longitude() != null) customer.setLongitude(request.longitude());
        if (request.category() != null)  customer.setCategory(request.category());

        log.info("Updated customer id={}", id);
        return CustomerResponse.from(customer);
    }

    /**
     * Changes a customer's lifecycle status.
     *
     * @throws BusinessException 404 if the id does not exist;
     *                           409 if the customer is already in the target status
     */
    @Transactional
    public CustomerResponse changeStatus(Long id, CustomerStatus targetStatus) {
        Customer customer = findOrThrow(id);
        if (customer.getStatus() == targetStatus) {
            throw BusinessException.conflict(
                    "Customer is already " + targetStatus.name().toLowerCase(),
                    "CUSTOMER_STATUS_UNCHANGED");
        }
        customer.setStatus(targetStatus);
        log.info("Changed customer id={} status to {}", id, targetStatus);
        return CustomerResponse.from(customer);
    }

    /**
     * Hard-deletes a customer.
     *
     * <p>A customer referenced by visits, invoices, or route assignments cannot be
     * deleted — those foreign keys reject it at the database level. We let the DB
     * enforce integrity and translate the violation into a clean 409, exactly as
     * {@code TerritoryService} does. In normal operation you deactivate a customer
     * with history rather than delete it; delete is for outlets created in error.</p>
     *
     * @throws BusinessException 404 if the id does not exist;
     *                           409 if the customer is still referenced
     */
    @Transactional
    public void delete(Long id) {
        Customer customer = findOrThrow(id);
        try {
            customerRepository.delete(customer);
            customerRepository.flush(); // force the FK check now, inside this try
        } catch (DataIntegrityViolationException ex) {
            log.warn("Refused to delete customer id={} — still referenced by visits/invoices/route assignments", id);
            throw BusinessException.conflict(
                    "Cannot delete a customer that has visits, invoices, or route assignments. Deactivate it instead.",
                    "CUSTOMER_IN_USE");
        }
        log.info("Deleted customer id={}", id);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Loads a customer or throws a 404 — single source of the not-found message. */
    private Customer findOrThrow(Long id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound(
                        "Customer not found: " + id, "CUSTOMER_NOT_FOUND"));
    }

    /** Verifies a territory exists via the facade, or rejects with a 400. */
    private void requireTerritory(Long territoryId) {
        if (!territoryFacade.exists(territoryId)) {
            throw BusinessException.badRequest(
                    "Territory does not exist: " + territoryId, "TERRITORY_NOT_FOUND");
        }
    }
}