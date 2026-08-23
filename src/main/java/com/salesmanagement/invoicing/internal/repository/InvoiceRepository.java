package com.salesmanagement.invoicing.internal.repository;

import com.salesmanagement.invoicing.internal.entity.Invoice;
import com.salesmanagement.invoicing.internal.enums.InvoiceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.salesmanagement.invoicing.api.CustomerPurchaseAggregate;
import com.salesmanagement.invoicing.api.DailySalesAggregate;
import com.salesmanagement.invoicing.api.DailyUnitsSoldAggregate;
import com.salesmanagement.invoicing.api.InvoiceStatusCount;
import com.salesmanagement.invoicing.api.InvoiceSummary;
import com.salesmanagement.invoicing.api.ProductSalesAggregate;
import com.salesmanagement.invoicing.api.RepSalesAggregate;
import java.util.Collection;
import java.util.List;
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


    /**
     * Per-rep sales rollup for FR-115 (see {@link RepSalesAggregate}). One row per rep whose
     * invoices in the window fall within {@code statuses}. Half-open date range.
     */
    @Query("""
            select new com.salesmanagement.invoicing.api.RepSalesAggregate(
                       i.representativeId, count(i), coalesce(sum(i.totalAmount), 0))
            from Invoice i
            where i.invoiceDate >= :from and i.invoiceDate < :to
              and i.status in :statuses
            group by i.representativeId
            """)
    List<RepSalesAggregate> aggregateByRep(@Param("from") LocalDate from,
                                           @Param("to") LocalDate to,
                                           @Param("statuses") Collection<InvoiceStatus> statuses);

    /**
     * Per-customer purchasing rollup for FR-118/119 (see {@link CustomerPurchaseAggregate}).
     */
    @Query("""
            select new com.salesmanagement.invoicing.api.CustomerPurchaseAggregate(
                       i.customerId, count(i), coalesce(sum(i.totalAmount), 0))
            from Invoice i
            where i.invoiceDate >= :from and i.invoiceDate < :to
              and i.status in :statuses
            group by i.customerId
            """)
    List<CustomerPurchaseAggregate> aggregateByCustomer(@Param("from") LocalDate from,
                                                        @Param("to") LocalDate to,
                                                        @Param("statuses") Collection<InvoiceStatus> statuses);

    /**
     * Per-product sales rollup for FR-123 (see {@link ProductSalesAggregate}). Joins the invoice
     * to its line items and groups by product, returning both units and revenue. The join lives on
     * the aggregate root's {@code lines} association, so no bag-fetch problem arises (this is a
     * scalar projection, not an entity fetch — MultipleBagFetchException cannot occur here).
     */
    @Query("""
            select new com.salesmanagement.invoicing.api.ProductSalesAggregate(
                       li.productId, coalesce(sum(li.quantity), 0), coalesce(sum(li.subtotal), 0))
            from Invoice i
            join i.lines li
            where i.invoiceDate >= :from and i.invoiceDate < :to
              and i.status in :statuses
            group by li.productId
            """)
    List<ProductSalesAggregate> aggregateProductSales(@Param("from") LocalDate from,
                                                      @Param("to") LocalDate to,
                                                      @Param("statuses") Collection<InvoiceStatus> statuses);

    /**
     * Flat per-invoice rows for the tabular reports FR-116/117 (see {@link InvoiceSummary}).
     * Unbounded {@code List} by design (D2): reports are exported whole to Excel/PDF, so a paged
     * return would truncate the export to page one. Optional filters follow the same
     * {@code cast(:param as type)} null-guard the existing {@code search} uses, to avoid the
     * Postgres type-inference error on typed params. Status is exposed as its enum name via
     * {@code str(i.status)} so no internal enum leaks into the api DTO.
     */
    @Query("""
            select new com.salesmanagement.invoicing.api.InvoiceSummary(
                       i.id, i.customerId, i.representativeId, i.invoiceDate,
                       i.totalAmount, str(i.status))
            from Invoice i
            where i.invoiceDate >= :from and i.invoiceDate < :to
              and (cast(:representativeId as long) is null or i.representativeId = :representativeId)
              and (cast(:customerId       as long) is null or i.customerId       = :customerId)
            order by i.invoiceDate desc, i.id desc
            """)
    List<InvoiceSummary> findSummaries(@Param("from") LocalDate from,
                                       @Param("to") LocalDate to,
                                       @Param("representativeId") Long representativeId,
                                       @Param("customerId") Long customerId);

    /**
     * Last realised invoice date per customer - backs the dormant-customers report. Returns rows of
     * [customerId (Long), maxInvoiceDate (LocalDate)] across invoices whose status is in
     * :statuses (the facade passes {SENT, APPROVED}: a dormant check ignores drafts and rejected
     * sales). A customer who never had a realised invoice does not appear - the reporting side treats
     * absence as "never purchased", the strongest dormancy signal.
     */
    /**
     * Per-day realised-sales rollup for the dashboard sales trend (see {@link DailySalesAggregate}).
     * ONE {@code group by} over the whole window returns one row per day that had sales — the caller
     * never loops over dates. Ordered ascending so the trend arrives already chart-ready; the
     * zero-filling of silent days happens in memory upstream, not by inventing rows here.
     */
    @Query("""
            select new com.salesmanagement.invoicing.api.DailySalesAggregate(
                       i.invoiceDate, count(i), coalesce(sum(i.totalAmount), 0))
            from Invoice i
            where i.invoiceDate >= :from and i.invoiceDate < :to
              and i.status in :statuses
            group by i.invoiceDate
            order by i.invoiceDate asc
            """)
    List<DailySalesAggregate> aggregateDailySales(@Param("from") LocalDate from,
                                                  @Param("to") LocalDate to,
                                                  @Param("statuses") Collection<InvoiceStatus> statuses);

    /**
     * Per-day units sold across realised invoice lines (see {@link DailyUnitsSoldAggregate}) — the
     * sold series of the inventory movement chart. Joins the aggregate root's {@code lines}
     * association and groups by the invoice's business date; one query per window.
     */
    @Query("""
            select new com.salesmanagement.invoicing.api.DailyUnitsSoldAggregate(
                       i.invoiceDate, coalesce(sum(li.quantity), 0))
            from Invoice i
            join i.lines li
            where i.invoiceDate >= :from and i.invoiceDate < :to
              and i.status in :statuses
            group by i.invoiceDate
            order by i.invoiceDate asc
            """)
    List<DailyUnitsSoldAggregate> aggregateDailyUnitsSold(@Param("from") LocalDate from,
                                                          @Param("to") LocalDate to,
                                                          @Param("statuses") Collection<InvoiceStatus> statuses);

    /**
     * Invoice count per status over the window (see {@link InvoiceStatusCount}) — ALL statuses, not
     * just the realised ones, because the donut it feeds is about workflow state, not revenue. A
     * single {@code group by i.status}: counting statuses must never mean loading invoices. Statuses
     * with no invoices in the window are absent here and zero-filled by the facade, which owns the
     * enum and therefore the full status set.
     */
    @Query("""
            select new com.salesmanagement.invoicing.api.InvoiceStatusCount(
                       str(i.status), count(i))
            from Invoice i
            where i.invoiceDate >= :from and i.invoiceDate < :to
            group by i.status
            """)
    List<InvoiceStatusCount> countByStatus(@Param("from") LocalDate from,
                                           @Param("to") LocalDate to);

    @Query("""
            select i.customerId, max(i.invoiceDate)
            from Invoice i
            where i.status in :statuses
            group by i.customerId
            """)
    List<Object[]> findLastInvoiceDatePerCustomer(@Param("statuses") Collection<InvoiceStatus> statuses);
}
