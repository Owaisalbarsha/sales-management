package com.salesmanagement.routing.internal.repository;

import com.salesmanagement.routing.internal.entity.Route;
import com.salesmanagement.routing.internal.enums.RouteStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Persistence for {@link Route}. The {@code RouteCustomerAssignment} stops have no
 * repository of their own — they are managed through the {@code Route} aggregate via
 * cascade + orphan-removal (same as vanops' order lines).
 *
 * <p>Two flavours of read: a paged {@code search} for the management list (no fetch
 * join — paginating a fetch join would page in memory), and {@code findWithAssignments*}
 * fetch-join lookups for the single-route reads where the stops are always needed.</p>
 */
public interface RouteRepository extends JpaRepository<Route, Long> {

    /**
     * Paged search with optional filters. A {@code null} filter is ignored.
     * Stops are loaded lazily when each row is mapped to a response (inside the same tx).
     */
    @Query("""
            select r from Route r
            where (:representativeId is null or r.representativeId = :representativeId)
              and (:status          is null or r.status           = :status)
              and (:routeDate       is null or r.routeDate         = :routeDate)
            """)
    Page<Route> search(@Param("representativeId") Long representativeId,
                       @Param("status") RouteStatus status,
                       @Param("routeDate") LocalDate routeDate,
                       Pageable pageable);

    /** Single route with its stops eagerly fetched. */
    @Query("select distinct r from Route r left join fetch r.assignments where r.id = :id")
    Optional<Route> findWithAssignmentsById(@Param("id") Long id);

    /** A rep's route for a given day, stops eagerly fetched. At most one (see the unique constraint). */
    @Query("""
            select distinct r from Route r
            left join fetch r.assignments
            where r.representativeId = :representativeId
              and r.routeDate = :routeDate
            """)
    Optional<Route> findWithAssignmentsByRepresentativeIdAndRouteDate(
            @Param("representativeId") Long representativeId,
            @Param("routeDate") LocalDate routeDate);

    /** One-route-per-rep-per-day guard. */
    boolean existsByRepresentativeIdAndRouteDate(Long representativeId, LocalDate routeDate);
}
