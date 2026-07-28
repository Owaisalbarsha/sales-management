package com.salesmanagement.vanops.internal.repository;

import com.salesmanagement.vanops.api.FulfillmentAggregate;
import com.salesmanagement.vanops.api.ProductMovementAggregate;
import com.salesmanagement.vanops.internal.entity.DemandOrder;
import com.salesmanagement.vanops.internal.enums.DemandOrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link DemandOrder}.
 *
 * <p>{@link #findWithLinesById} eagerly loads the lines so the service/response mapper does
 * not trigger N+1 selects when projecting the order. The single-row search query backs the
 * list endpoint and accepts {@code null} parameters to disable individual filters — same
 * pattern as {@code CustomerRepository.search}.</p>
 */
public interface DemandOrderRepository extends JpaRepository<DemandOrder, Long> {

    /** Load an order with its lines in one query (avoids N+1 on the response mapper). */
    @EntityGraph(attributePaths = "lines")
    @Query("SELECT d FROM DemandOrder d WHERE d.id = :id")
    Optional<DemandOrder> findWithLinesById(@Param("id") Long id);

    /**
     * Page of demand orders, filterable by representative, status, and date.
     * Any {@code null} parameter disables that filter.
     */
    @Query("""
        SELECT d FROM DemandOrder d
        WHERE (:representativeId IS NULL OR d.representativeId = :representativeId)
          AND (:status            IS NULL OR d.status            = :status)
          AND (:orderDate         IS NULL OR d.orderDate         = :orderDate)
        """)
    Page<DemandOrder> search(@Param("representativeId") Long representativeId,
                             @Param("status") DemandOrderStatus status,
                             @Param("orderDate") LocalDate orderDate,
                             Pageable pageable);

    /**
     * Per-product quantity loaded OUT to vans over a window — the outbound side of FR-122. Sums
     * {@code fulfilled_qty} (what actually moved, not what was requested) across demand orders whose
     * status is in {@code statuses}; the facade passes {@code {LOADED}} (the only terminal state where
     * stock physically left the warehouse). Half-open {@code [from, to)} on {@code order_date}.
     */
    @Query("""
            select new com.salesmanagement.vanops.api.ProductMovementAggregate(
                       l.productId, coalesce(sum(l.fulfilledQty), 0))
            from DemandOrder d
            join d.lines l
            where d.orderDate >= :from and d.orderDate < :to
              and d.status in :statuses
            group by l.productId
            """)
    List<ProductMovementAggregate> aggregateLoadedByProduct(@Param("from") LocalDate from,
                                                            @Param("to") LocalDate to,
                                                            @Param("statuses") Collection<DemandOrderStatus> statuses);

    /**
     * Per-product requested vs fulfilled over a window — the fill-rate report. Same LOADED-only scope
     * as {@link #aggregateLoadedByProduct}: only orders with a final fulfilled figure count.
     */
    @Query("""
            select new com.salesmanagement.vanops.api.FulfillmentAggregate(
                       l.productId,
                       coalesce(sum(l.requestedQty), 0),
                       coalesce(sum(l.fulfilledQty), 0))
            from DemandOrder d
            join d.lines l
            where d.orderDate >= :from and d.orderDate < :to
              and d.status in :statuses
            group by l.productId
            """)
    List<FulfillmentAggregate> aggregateFulfillment(@Param("from") LocalDate from,
                                                    @Param("to") LocalDate to,
                                                    @Param("statuses") Collection<DemandOrderStatus> statuses);

}