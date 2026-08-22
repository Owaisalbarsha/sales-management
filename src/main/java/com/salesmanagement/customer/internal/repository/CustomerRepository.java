package com.salesmanagement.customer.internal.repository;

import com.salesmanagement.customer.internal.Customer;
import com.salesmanagement.customer.internal.enums.CustomerStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Spring Data repository for {@link Customer}.
 *
 * <p>Internal to the customer module. No other module injects this — they go
 * through {@code CustomerFacade}. {@code JpaRepository} supplies the CRUD and
 * pagination surface; the one addition is a filtered search used by the list
 * endpoint.</p>
 *
 * <p>Customer names are <em>not</em> unique (two "Corner Shop" outlets in
 * different territories are valid), so there is deliberately no
 * {@code existsByName} method.</p>
 */
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    /**
     * Page of customers optionally filtered by territory and/or status. A
     * {@code null} parameter disables that filter, so this single query backs
     * both the unfiltered list and any combination of the two filters — avoiding
     * a combinatorial explosion of derived query methods.
     *
     * @param territoryId restrict to this territory, or {@code null} for all
     * @param status      restrict to this status, or {@code null} for all
     * @param pageable    pagination and sort
     * @return the matching page of customers
     */
    @Query("""
        SELECT c FROM Customer c
        WHERE (:territoryId IS NULL OR c.territoryId = :territoryId)
          AND (:status      IS NULL OR c.status      = :status)
          AND (CAST(:q AS string) IS NULL OR LOWER(c.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
        """)
    Page<Customer> search(@Param("territoryId") Long territoryId,
                          @Param("status") CustomerStatus status,
                          @Param("q") String q,
                          Pageable pageable);

    /**
     * Active-customer count grouped by territory, in one query. Returns rows of
     * {@code [territoryId (Long), count (Long)]}; the facade maps them to a Map. Only the given status
     * is counted (the facade passes ACTIVE). Territories with no active customer simply do not appear.
     */
    @Query("""
            select c.territoryId, count(c)
            from Customer c
            where c.status = :status
            group by c.territoryId
            """)
    List<Object[]> countActiveByTerritory(@Param("status") CustomerStatus status);

    /**
     * All customers in a given status — used by the dormant-customers report to enumerate every active
     * customer. Derived query; Spring Data generates {@code where status = ?}.
     */
    List<Customer> findByStatus(CustomerStatus status);
}