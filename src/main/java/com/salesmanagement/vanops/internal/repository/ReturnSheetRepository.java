package com.salesmanagement.vanops.internal.repository;

import com.salesmanagement.vanops.internal.entity.ReturnSheet;
import com.salesmanagement.vanops.internal.enums.ReturnSheetStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
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
}