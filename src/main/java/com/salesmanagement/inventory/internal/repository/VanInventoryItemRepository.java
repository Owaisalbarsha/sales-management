package com.salesmanagement.inventory.internal.repository;

import com.salesmanagement.inventory.internal.VanInventoryItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link VanInventoryItem}.
 *
 * <p>{@link #deductIfSufficient} is the literal implementation of BR-4: the
 * {@code AND v.quantity >= :qty} clause makes the check and the subtraction one
 * indivisible operation, so two concurrent sales of the same product on the same van
 * cannot both pass. {@code 0} rows changed means the deduction was refused (no such
 * row, or insufficient quantity); {@code StockService} translates that into a 422.</p>
 */
public interface VanInventoryItemRepository extends JpaRepository<VanInventoryItem, Long> {

    @Query("SELECT v FROM VanInventoryItem v JOIN FETCH v.product WHERE v.representativeId = :representativeId ORDER BY v.id")
    List<VanInventoryItem> findByRepresentative(@Param("representativeId") Long representativeId);

    Optional<VanInventoryItem> findByRepresentativeIdAndProductId(Long representativeId, Long productId);

    boolean existsByRepresentativeIdAndProductId(Long representativeId, Long productId);

    /**
     * BR-4 atomic guarded decrement: subtracts {@code qty} from the (rep, product) van
     * row <em>only if</em> at least {@code qty} is present. Returns rows changed —
     * {@code 1} on success, {@code 0} if the row is missing or has too little.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE VanInventoryItem v
           SET v.quantity = v.quantity - :qty
         WHERE v.representativeId = :representativeId
           AND v.product.id       = :productId
           AND v.quantity        >= :qty
        """)
    int deductIfSufficient(@Param("representativeId") Long representativeId,
                           @Param("productId") Long productId,
                           @Param("qty") int qty);

    /**
     * Atomic increment of an existing van row. Returns rows changed
     * ({@code 1} if the row exists, {@code 0} if there is no row yet — caller inserts).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE VanInventoryItem v
           SET v.quantity = v.quantity + :qty
         WHERE v.representativeId = :representativeId
           AND v.product.id       = :productId
        """)
    int increment(@Param("representativeId") Long representativeId,
                  @Param("productId") Long productId,
                  @Param("qty") int qty);
}
