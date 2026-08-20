package com.salesmanagement.inventory.internal.repository;

import com.salesmanagement.inventory.internal.entity.StockCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link StockCount}.
 *
 * <p>Internal to the inventory module. {@code JpaRepository} supplies CRUD; the additions
 * fetch-join the lines (and each line's product) so response and variance mapping never
 * triggers a lazy N+1 — the same shape {@code DemandOrderRepository.findWithLinesById} uses.
 * Stock counts are occasional (a warehouse is not counted many times a day), so the
 * fetch-everything list query is comfortably within budget and avoids per-row product loads.</p>
 */
public interface StockCountRepository extends JpaRepository<StockCount, Long> {

    /** One count with its lines and each line's product, in a single query. */
    @Query("""
        SELECT DISTINCT c FROM StockCount c
        LEFT JOIN FETCH c.lines l
        LEFT JOIN FETCH l.product
        WHERE c.id = :id
        """)
    Optional<StockCount> findWithLinesById(@Param("id") Long id);

    /** All counts, newest first, with lines and products pre-fetched. */
    @Query("""
        SELECT DISTINCT c FROM StockCount c
        LEFT JOIN FETCH c.lines l
        LEFT JOIN FETCH l.product
        ORDER BY c.countDate DESC, c.id DESC
        """)
    List<StockCount> findAllWithLines();
}
