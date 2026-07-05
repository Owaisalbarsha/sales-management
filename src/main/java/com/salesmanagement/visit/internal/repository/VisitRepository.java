package com.salesmanagement.visit.internal.repository;

import com.salesmanagement.visit.internal.entity.Visit;
import com.salesmanagement.visit.internal.enums.VisitStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for {@link Visit}. Internal to the {@code visit} module — other modules go
 * through {@code VisitFacade}, never this repository.
 */
public interface VisitRepository extends JpaRepository<Visit, Long> {

    /**
     * The single visit for a stop, if any. {@code (route_id, customer_id)} is unique, so this
     * returns at most one row. Used by check-out (BR-10) and the duplicate-check-in guard.
     */
    Optional<Visit> findByRouteIdAndCustomerId(Long routeId, Long customerId);

    /** All visits on a route — used to evaluate whether every stop is terminal. */
    List<Visit> findByRouteId(Long routeId);

    /** Visits on a route in a given status — used to find lingering {@code IN_PROGRESS} rows. */
    List<Visit> findByRouteIdAndStatus(Long routeId, VisitStatus status);

    /**
     * How many visits on a route are currently open ({@code IN_PROGRESS}). Backs the
     * "force check-out" rule: a rep may not start a new check-in, or end their day, while any
     * visit on the route is still open.
     */
    long countByRouteIdAndStatus(Long routeId, VisitStatus status);

    /**
     * Paged search with optional filters. A {@code null} filter is ignored. {@code representativeId}
     * is forced to the caller for a {@code SALES_REP} at the service layer (self-scoping).
     */
    @Query("""
            select v from Visit v
            where (:representativeId is null or v.representativeId = :representativeId)
              and (:routeId          is null or v.routeId          = :routeId)
              and (:customerId       is null or v.customerId       = :customerId)
              and (:status           is null or v.status           = :status)
            """)
    Page<Visit> search(@Param("representativeId") Long representativeId,
                       @Param("routeId") Long routeId,
                       @Param("customerId") Long customerId,
                       @Param("status") VisitStatus status,
                       Pageable pageable);
}
