package com.salesmanagement.inventory.internal.repository;

import com.salesmanagement.inventory.api.WarehouseStockInfo;
import com.salesmanagement.inventory.internal.entity.WarehouseStockItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link WarehouseStockItem}.
 *
 * <p>The two {@code @Modifying} statements are the heart of safe stock movement. They
 * perform the check and the write in a <strong>single atomic SQL statement</strong>, so
 * concurrent calls cannot race (see {@code StockService} for the full rationale).
 * {@code flushAutomatically} flushes pending context changes before the bulk update;
 * {@code clearAutomatically} evicts now-stale managed entities afterwards.</p>
 *
 * <p>Reads {@code JOIN FETCH} the product so response mapping does not trigger N+1
 * selects. A to-one fetch join is safe to paginate (unlike a to-many fetch), and the
 * paginated query carries an explicit {@code countQuery}.</p>
 */
public interface WarehouseStockItemRepository extends JpaRepository<WarehouseStockItem, Long> {

    @Query("SELECT w FROM WarehouseStockItem w JOIN FETCH w.product WHERE w.product.id = :productId")
    Optional<WarehouseStockItem> findByProductId(@Param("productId") Long productId);

    boolean existsByProductId(Long productId);

    /**
     * Page of warehouse stock, optionally filtered to one product and/or to rows that
     * are below their product's {@code minStockLevel} (FR-32 low-stock view).
     *
     * @param lowStock when {@code true}, only rows where {@code quantity < minStockLevel}
     */
    @Query(value = """
            SELECT w FROM WarehouseStockItem w JOIN FETCH w.product p
            WHERE (:productId IS NULL OR p.id = :productId)
              AND (:lowStock = false OR w.quantity < p.minStockLevel)
            """,
            countQuery = """
            SELECT COUNT(w) FROM WarehouseStockItem w
            WHERE (:productId IS NULL OR w.product.id = :productId)
              AND (:lowStock = false OR w.quantity < w.product.minStockLevel)
            """)
    Page<WarehouseStockItem> search(@Param("productId") Long productId,
                                    @Param("lowStock") boolean lowStock,
                                    Pageable pageable);

    /**
     * Atomic guarded decrement: subtracts {@code qty} from the product's warehouse row
     * <em>only if</em> at least {@code qty} is present. Returns the number of rows
     * changed — {@code 1} on success, {@code 0} if the row is missing or has too little.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE WarehouseStockItem w
           SET w.quantity = w.quantity - :qty
         WHERE w.product.id = :productId
           AND w.quantity  >= :qty
        """)
    int deductIfSufficient(@Param("productId") Long productId, @Param("qty") int qty);

    /**
     * Atomic increment of an existing warehouse row. Returns rows changed
     * ({@code 1} if the row exists, {@code 0} if there is no row yet — caller inserts).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE WarehouseStockItem w
           SET w.quantity = w.quantity + :qty
         WHERE w.product.id = :productId
        """)
    int increment(@Param("productId") Long productId, @Param("qty") int qty);

    /**
     * All products with their current warehouse on-hand and minimum — FR-120. LEFT JOIN keeps
     * products that have never been stocked (no {@code WarehouseStockItem} row) in the result at
     * {@code onHand = 0}; such a product is below-min whenever its minimum exceeds 0. One row per
     * product, ordered by name. Scalar projection into the api DTO, so a column rename breaks
     * compilation rather than failing at runtime.
     */
    @Query("""
            select new com.salesmanagement.inventory.api.WarehouseStockInfo(
                     p.id, p.name, p.sku,
                     coalesce(ws.quantity, 0),
                     p.minStockLevel,
                     coalesce(ws.quantity, 0) < p.minStockLevel)
            from Product p
            left join WarehouseStockItem ws on ws.product = p
            order by p.name asc
            """)
    List<WarehouseStockInfo> findAllWarehouseStock();

    /**
     * Only the products currently below minimum — FR-121 (low-stock / reorder list). Same shape as
     * {@link #findAllWarehouseStock()} with the below-min predicate applied, so a never-stocked
     * product with a positive minimum correctly appears here too.
     */
    @Query("""
            select new com.salesmanagement.inventory.api.WarehouseStockInfo(
                     p.id, p.name, p.sku,
                     coalesce(ws.quantity, 0),
                     p.minStockLevel,
                     true)
            from Product p
            left join WarehouseStockItem ws on ws.product = p
            where coalesce(ws.quantity, 0) < p.minStockLevel
            order by p.name asc
            """)
    List<WarehouseStockInfo> findWarehouseStockBelowMinimum();

    @Query("""
            select coalesce(sum(ws.quantity * ws.product.price), 0)
            from WarehouseStockItem ws
            """)
    java.math.BigDecimal totalStockValue();
}
