package com.salesmanagement.invoicing.api;

import com.salesmanagement.invoicing.internal.enums.InvoiceStatus;
import com.salesmanagement.invoicing.internal.repository.InvoiceRepository;
import com.salesmanagement.invoicing.internal.service.InvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Public API surface of the {@code invoicing} module for cross-module <em>reads</em> — the only
 * type {@code reporting} imports from invoicing. Mirrors the other read facades
 * ({@code UserFacade}, {@code CustomerFacade}); no business logic lives here, it delegates straight
 * to the repository's aggregate projections.
 *
 * <p><strong>Why aggregate, not per-row (D1).</strong> Every method returns a set that the database
 * has already grouped and summed in one query. Reporting never loops invoice-by-invoice — that was
 * the N+1 trap. A rep sales report over a month is one {@code aggregateByRep} call returning one row
 * per rep, not 500 facade calls.</p>
 *
 * <p><strong>Realised-sales filter is baked in (locked decision).</strong> The sales aggregates
 * ({@link #aggregateByRep}, {@link #aggregateByCustomer}, {@link #aggregateProductSales}) count only
 * {@code status IN (SENT, APPROVED)}. Reporting cannot pass a status — the internal
 * {@code InvoiceStatus} enum never crosses the boundary, and the policy of "what counts as a sale"
 * belongs to invoicing, not to its consumer. REJECTED is excluded (a disowned sale would inflate the
 * numbers); the un-reversed stock it left behind is surfaced by the FR-122 movement report instead.</p>
 *
 * <p><strong>Date semantics (D9).</strong> Every method takes a half-open business-date range
 * {@code [from, to)} as {@code LocalDate}. Reporting converts its incoming {@code Instant}/offset
 * params to {@code LocalDate} at its own controller boundary using the configured business zone, so
 * this facade deals only in calendar dates — matching {@code Invoice.invoiceDate}, which is itself a
 * {@code LocalDate}.</p>
 *
 * <p><strong>Read-only, best-effort snapshot (D8).</strong> All methods are
 * {@code @Transactional(readOnly = true)}. A report running while an invoice is mid-submit may or may
 * not include it; reporting is analytical, not a point-in-time ledger, and takes no cross-module lock.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InvoiceFacade {

    /**
     * The set of statuses that count as a realised sale. SENT and APPROVED both had stock deducted
     * and are stances the business stands behind; DRAFT never moved stock; REJECTED was disowned.
     * Baked in here so the policy has exactly one home.
     */
    private static final Set<InvoiceStatus> REALISED_SALES =
            Set.of(InvoiceStatus.SENT, InvoiceStatus.APPROVED);

    private final InvoiceRepository invoiceRepository;
    private final InvoiceService invoiceService;

    /**
     * Flat per-invoice rows for the tabular sales reports (FR-116, FR-117). Unbounded list, newest
     * first, so the whole result can be exported to Excel/PDF. Unlike the aggregates, this is
     * <em>not</em> status-filtered — a manager's sales list may legitimately include DRAFT and
     * REJECTED rows; callers that want only realised sales filter on {@link InvoiceSummary#status()}.
     *
     * @param from             inclusive start of the business-date window
     * @param to               exclusive end of the business-date window
     * @param representativeId optional rep filter; {@code null} for all reps
     * @param customerId       optional customer filter; {@code null} for all customers
     */
    public List<InvoiceSummary> findSummaries(LocalDate from, LocalDate to,
                                              Long representativeId, Long customerId) {
        return invoiceRepository.findSummaries(from, to, representativeId, customerId);
    }

    /**
     * Per-rep realised-sales rollup for FR-115. One row per rep with any realised invoice in the
     * window.
     */
    public List<RepSalesAggregate> aggregateByRep(LocalDate from, LocalDate to) {
        return invoiceRepository.aggregateByRep(from, to, REALISED_SALES);
    }

    /**
     * Per-customer realised-purchase rollup for FR-118 / FR-119.
     */
    public List<CustomerPurchaseAggregate> aggregateByCustomer(LocalDate from, LocalDate to) {
        return invoiceRepository.aggregateByCustomer(from, to, REALISED_SALES);
    }

    /**
     * Per-product realised-sales rollup for FR-123, carrying both units sold and revenue. Reporting
     * ranks these for the fast / slow-moving classification (top-N / bottom-N by units, N from
     * {@code systemconfig}).
     */
    public List<ProductSalesAggregate> aggregateProductSales(LocalDate from, LocalDate to) {
        return invoiceRepository.aggregateProductSales(from, to, REALISED_SALES);
    }

    /**
     * Replays a completed offline sale as a SENT invoice in one transaction and returns its
     * server id. Idempotent on {@code input.clientUuid} (a resend returns the existing invoice).
     * Records the sale even when the customer is inactive or a price drifted (flagged via
     * {@code OfflineInvoiceFlaggedEvent}); only unrecoverable problems (unknown product, missing
     * ePOD type, invalid line) throw.
     *
     * @param representativeId the selling rep, from the JWT principal (never the payload)
     * @param input            the offline invoice (frozen prices, staged ePOD tokens, pre-resolved visit)
     * @return the created invoice's server id
     */
    @Transactional
    public Long createFromOfflineSync(Long representativeId, OfflineInvoiceInput input) {
        return invoiceService.createFromOfflineSync(representativeId, input);
    }
}
