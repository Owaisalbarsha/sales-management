package com.salesmanagement.inventory.internal.repository;

import com.salesmanagement.inventory.api.ProductStockValueInfo;
import com.salesmanagement.inventory.api.StockHealthSummary;
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
                     case when coalesce(ws.quantity, 0) < p.minStockLevel then true else false end)
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

    /**
     * The three mutually exclusive stock-health buckets in ONE aggregate query
     * (see {@link StockHealthSummary}). LEFT JOIN from {@code Product} so never-received products are
     * counted at {@code onHand = 0} — the same population {@link #findAllWarehouseStock()} reports,
     * which is what keeps {@code totalSkus} consistent between the donut and the KPI tile.
     *
     * <p>The three {@code case} arms are written to be disjoint and exhaustive rather than derived
     * from one another, so the slices provably sum to {@code totalSkus}. Note the {@code > 0} guard on
     * the healthy arm: without it a product with {@code minStockLevel = 0} and nothing on hand would
     * be counted both out-of-stock and healthy, and the donut would over-count.</p>
     */
    @Query("""
            select new com.salesmanagement.inventory.api.StockHealthSummary(
                     count(p),
                     coalesce(sum(case when coalesce(ws.quantity, 0) = 0 then 1L else 0L end), 0L),
                     coalesce(sum(case when coalesce(ws.quantity, 0) > 0
                                        and coalesce(ws.quantity, 0) < p.minStockLevel
                                       then 1L else 0L end), 0L),
                     coalesce(sum(case when coalesce(ws.quantity, 0) > 0
                                        and coalesce(ws.quantity, 0) >= p.minStockLevel
                                       then 1L else 0L end), 0L))
            from Product p
            left join WarehouseStockItem ws on ws.product = p
            """)
    StockHealthSummary stockHealth();

    /**
     * Products ranked by the value of the stock sitting on them (see {@link ProductStockValueInfo}),
     * highest first. The multiplication, the ordering and the top-N cut all happen in the database —
     * the caller passes a {@code Pageable} of size N and receives at most N rows, so the cost does not
     * grow with the catalogue.
     *
     * <p>Ties break on {@code p.id} ascending so repeated calls return the same rows in the same
     * order; a ranking whose order wobbles between refreshes reads as data churn. Rows with no stock
     * are excluded (zero value, no information).</p>
     */
    @Query("""
            select new com.salesmanagement.inventory.api.ProductStockValueInfo(
                     p.id, p.name, p.sku, ws.quantity, p.price, ws.quantity * p.price)
            from WarehouseStockItem ws
            join ws.product p
            where ws.quantity > 0
            order by ws.quantity * p.price desc, p.id asc
            """)
    List<ProductStockValueInfo> findTopByStockValue(Pageable pageable);
}
