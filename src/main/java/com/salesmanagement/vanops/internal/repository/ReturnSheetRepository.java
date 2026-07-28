package com.salesmanagement.vanops.internal.repository;

import com.salesmanagement.vanops.api.ProductMovementAggregate;
import com.salesmanagement.vanops.internal.entity.ReturnSheet;
import com.salesmanagement.vanops.internal.enums.ReturnSheetStatus;
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
 * Spring Data repository for {@link ReturnSheet}. Same pattern as
 * {@link DemandOrderRepository}.
 */
public interface ReturnSheetRepository extends JpaRepository<ReturnSheet, Long> {

    @EntityGraph(attributePaths = "lines")
    @Query("SELECT r FROM ReturnSheet r WHERE r.id = :id")
    Optional<ReturnSheet> findWithLinesById(@Param("id") Long id);

    @Query("""
        SELECT r FROM ReturnSheet r
        WHERE (:representativeId IS NULL OR r.representativeId = :representativeId)
          AND (:status            IS NULL OR r.status            = :status)
          AND (:returnDate        IS NULL OR r.returnDate        = :returnDate)
        """)
    Page<ReturnSheet> search(@Param("representativeId") Long representativeId,
                             @Param("status") ReturnSheetStatus status,
                             @Param("returnDate") LocalDate returnDate,
                             Pageable pageable);

    /**
     * Per-product quantity returned IN from vans over a window — the inbound side of FR-122. Sums the
     * line {@code quantity} across return sheets whose status is in {@code statuses}; the facade passes
     * {@code {COMPLETED}} (the only state where stock physically moved van → warehouse). Half-open
     * {@code [from, to)} on {@code return_date}.
     */
    @Query("""
            select new com.salesmanagement.vanops.api.ProductMovementAggregate(
                       l.productId, coalesce(sum(l.quantity), 0))
            from ReturnSheet r
            join r.lines l
            where r.returnDate >= :from and r.returnDate < :to
              and r.status in :statuses
            group by l.productId
            """)
    List<ProductMovementAggregate> aggregateReturnedByProduct(@Param("from") LocalDate from,
                                                              @Param("to") LocalDate to,
                                                              @Param("statuses") Collection<ReturnSheetStatus> statuses);
}