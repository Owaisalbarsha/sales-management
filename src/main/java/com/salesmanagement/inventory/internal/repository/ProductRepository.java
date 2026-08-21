package com.salesmanagement.inventory.internal.repository;

import com.salesmanagement.inventory.internal.entity.Product;
import com.salesmanagement.inventory.internal.enums.ProductStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link Product}.
 *
 * <p>Internal to the inventory module. No other module injects this — they go
 * through {@code InventoryFacade}. {@code JpaRepository} supplies CRUD and
 * pagination; the additions are uniqueness pre-checks (so the service can return a
 * clean 409 before hitting a DB constraint) and a filtered search for the list
 * endpoint, mirroring {@code CustomerRepository}.</p>
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

    boolean existsBySku(String sku);

    boolean existsByBarcode(String barcode);

    /**
     * Page of products optionally filtered by status and/or a name/SKU search term.
     * A {@code null} parameter disables that filter, so this single query backs both
     * the unfiltered list and any combination of filters.
     *
     * @param status restrict to this status, or {@code null} for all
     * @param q      case-insensitive substring matched against name OR sku, or {@code null}
     */
    @Query("""
        SELECT p FROM Product p
        WHERE (:status IS NULL OR p.status = :status)
          AND (CAST(:q AS string) IS NULL
               OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
               OR LOWER(p.sku)  LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
        """)
    Page<Product> search(@Param("status") ProductStatus status,
                         @Param("q") String q,
                         Pageable pageable);
}
