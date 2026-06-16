package com.salesmanagement.vanops.internal.repository;

import com.salesmanagement.vanops.internal.entity.DemandOrder;
import com.salesmanagement.vanops.internal.enums.DemandOrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
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
}