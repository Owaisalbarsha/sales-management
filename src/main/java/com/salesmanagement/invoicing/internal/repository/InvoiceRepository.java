package com.salesmanagement.invoicing.internal.repository;

import com.salesmanagement.invoicing.internal.entity.Invoice;
import com.salesmanagement.invoicing.internal.enums.InvoiceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Persistence for {@link Invoice}. Line items and ePOD artifacts have no repository of their
 * own — they are managed through the {@code Invoice} aggregate via cascade + orphan-removal
 * (same pattern as routing's stops and vanops' order lines).
 *
 * <p>Two flavours of read: a paged {@code search} for the manager list (FR-82; no fetch join,
 * because paginating a fetch join pages in memory), and {@code findWithChildrenById} for a
 * single invoice where lines and ePOD are always needed. Lines are also loaded lazily when a
 * search row is mapped to a response inside the same transaction.</p>
 */
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    /**
     * Paged search with optional filters (FR-82: by date, customer, rep, status). A {@code null}
     * filter is ignored. Each filter is wrapped in {@code cast(:param as type)} to avoid the
     * Postgres type-inference error the null-guard pattern otherwise triggers on typed params.
     */
    @Query("""
    select i from Invoice i
    where (cast(:representativeId as long)      is null or i.representativeId = :representativeId)
      and (cast(:customerId       as long)      is null or i.customerId       = :customerId)
      and (cast(:status           as string)    is null or i.status           = :status)
      and (cast(:invoiceDate      as localdate) is null or i.invoiceDate      = :invoiceDate)
    """)
    Page<Invoice> search(@Param("representativeId") Long representativeId,
                         @Param("customerId") Long customerId,
                         @Param("status") InvoiceStatus status,
                         @Param("invoiceDate") LocalDate invoiceDate,
                         Pageable pageable);

    /**
     * A rep's own invoices (FR-81), newest first by business date then id. Paged; children are
     * loaded lazily per row within the transaction.
     */
    Page<Invoice> findByRepresentativeId(Long representativeId, Pageable pageable);

    /**
     * Single invoice with its line items eagerly fetched.
     *
     * <p>Only ONE collection is fetch-joined here. Hibernate cannot fetch two bags (List
     * collections) in one query — it throws MultipleBagFetchException rather than return an
     * ambiguous Cartesian product. The ePOD artifacts are loaded by a second query into the
     * same persistence context, which Hibernate merges into this same entity instance.</p>
     */
    @Query("""
            select i from Invoice i
            left join fetch i.lines
            where i.id = :id
            """)
    Optional<Invoice> findWithLinesById(@Param("id") Long id);

    /** Second pass: loads the ePOD artifacts onto the invoice already in the persistence context. */
    @Query("""
            select i from Invoice i
            left join fetch i.epodArtifacts
            where i.id = :id
            """)
    Optional<Invoice> findWithEpodById(@Param("id") Long id);

    /**
     * Idempotency lookup for offline submits (D22): whether an invoice already exists for a
     * given mobile-generated {@code clientUuid}. The service checks this before creating so a
     * retried sync is recognised rather than colliding on the unique constraint.
     */
    boolean existsByClientUuid(String clientUuid);

    /** Fetch an existing invoice by its idempotency key (to return the already-created row on retry). */
    Optional<Invoice> findByClientUuid(String clientUuid);
}
