package com.salesmanagement.invoicing.internal.service;

import com.salesmanagement.customer.api.CustomerFacade;
import com.salesmanagement.identity.api.UserFacade;
import com.salesmanagement.inventory.api.InventoryFacade;
import com.salesmanagement.invoicing.api.InvoiceApprovedEvent;
import com.salesmanagement.invoicing.api.InvoiceRejectedEvent;
import com.salesmanagement.invoicing.api.InvoiceSubmittedEvent;
import com.salesmanagement.invoicing.internal.dto.CreateInvoiceRequest;
import com.salesmanagement.invoicing.internal.dto.SubmitInvoiceRequest;
import com.salesmanagement.invoicing.internal.dto.UpdateInvoiceRequest;
import com.salesmanagement.invoicing.internal.entity.Invoice;
import com.salesmanagement.invoicing.internal.enums.EpodArtifactType;
import com.salesmanagement.invoicing.internal.enums.InvoiceStatus;
import com.salesmanagement.invoicing.internal.repository.InvoiceRepository;
import com.salesmanagement.shared.exception.BusinessException;
import com.salesmanagement.visit.api.VisitFacade;
import com.salesmanagement.visit.api.VisitInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Workflow logic of {@link InvoiceService} with every cross-module facade mocked.
 *
 * <p>These tests pin the behaviour the entity cannot enforce on its own: ownership (BR-1), the
 * submit hard gate (D5b), atomic stock deduction (D1/BR-4), price capture (BR-9/D10), offline
 * idempotency (D22), and the read scope. Persistence is mocked — {@code save} echoes its argument
 * and {@code findWithChildrenById} returns the entity under test — so the tests exercise service
 * logic, not Hibernate.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InvoiceService — workflow")
class InvoiceServiceTest {

    private static final Long REP_ID       = 200L;
    private static final Long OTHER_REP_ID = 201L;
    private static final Long REVIEWER_ID  = 300L;
    private static final Long CUSTOMER_ID  = 100L;
    private static final Long PRODUCT_A    = 1L;
    private static final Long PRODUCT_B    = 2L;
    private static final Long INVOICE_ID   = 500L;

    @Mock private InvoiceRepository invoiceRepository;
    @Mock private CustomerFacade customerFacade;
    @Mock private VisitFacade visitFacade;
    @Mock private InventoryFacade inventoryFacade;
    @Mock private UserFacade userFacade;
    @Mock private EpodStorageService epodStorage;
    @Mock private ApplicationEventPublisher events;

    @InjectMocks private InvoiceService service;

    @BeforeEach
    void setUp() {
        // Default happy-path stubs; individual tests override what they care about.
        when(customerFacade.exists(anyLong())).thenReturn(true);
        when(customerFacade.isActive(anyLong())).thenReturn(true);
        when(inventoryFacade.productExists(anyLong())).thenReturn(true);
        when(inventoryFacade.getProductPrice(PRODUCT_A)).thenReturn(new BigDecimal("10.00"));
        when(inventoryFacade.getProductPrice(PRODUCT_B)).thenReturn(new BigDecimal("5.00"));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));
        when(epodStorage.readStaged(any())).thenReturn("bytes".getBytes());
        when(epodStorage.hash(any(), any(), any(), any(), any(), any(), any())).thenReturn("hash");
        when(epodStorage.store(any(), any(), any())).thenReturn("stored/path.png");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static CreateInvoiceRequest createRequest(Long visitId, CreateInvoiceRequest.Line... lines) {
        return new CreateInvoiceRequest(CUSTOMER_ID, visitId, null, List.of(lines));
    }

    private static CreateInvoiceRequest.Line createLine(Long productId, int qty, String discount) {
        return new CreateInvoiceRequest.Line(productId, qty,
                discount == null ? null : new BigDecimal(discount));
    }

    /** A DRAFT invoice as it would exist in the DB, with an id set via reflection-free save echo. */
    private Invoice draftInvoice(Long ownerId) {
        Invoice invoice = new Invoice(CUSTOMER_ID, ownerId, null, LocalDate.now(), null);
        setId(invoice, INVOICE_ID);
        return invoice;
    }

    /** BaseEntity's id has no public setter; tests set it directly through the field. */
    private static void setId(Invoice invoice, Long id) {
        try {
            var field = Class.forName("com.salesmanagement.shared.domain.BaseEntity")
                    .getDeclaredField("id");
            field.setAccessible(true);
            field.set(invoice, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not set test id", e);
        }
    }

    private static SubmitInvoiceRequest fullEpodRequest() {
        return new SubmitInvoiceRequest(List.of(
                new SubmitInvoiceRequest.Artifact(EpodArtifactType.SIGNATURE, "sig.png",
                        new BigDecimal("52.370216"), new BigDecimal("4.895168"), Instant.now()),
                new SubmitInvoiceRequest.Artifact(EpodArtifactType.DELIVERY_PHOTO, "photo.jpg",
                        new BigDecimal("52.370216"), new BigDecimal("4.895168"), Instant.now())));
    }

    private void givenInvoiceExists(Invoice invoice) {
        when(invoiceRepository.findWithLinesById(INVOICE_ID)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findWithEpodById(INVOICE_ID)).thenReturn(Optional.of(invoice));
    }

    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("createDraft")
    class CreateDraft {

        @Test
        @DisplayName("captures the current price from inventory, ignoring any client price")
        void capturesPriceFromInventory() {
            var response = service.createDraft(REP_ID, createRequest(null, createLine(PRODUCT_A, 2, null)));

            assertThat(response.lines()).hasSize(1);
            assertThat(response.lines().get(0).price()).isEqualByComparingTo("10.00");
            assertThat(response.totalAmount()).isEqualByComparingTo("20.00");
            verify(inventoryFacade).getProductPrice(PRODUCT_A);
        }

        @Test
        @DisplayName("opens in DRAFT and moves no stock")
        void opensInDraftWithoutMovingStock() {
            var response = service.createDraft(REP_ID, createRequest(null, createLine(PRODUCT_A, 2, null)));

            assertThat(response.status()).isEqualTo(InvoiceStatus.DRAFT);
            verify(inventoryFacade, never()).deductVanStock(anyLong(), anyLong(), anyInt());
        }

        @Test
        @DisplayName("sets the representative from the caller, never from the request")
        void setsRepresentativeFromCaller() {
            var response = service.createDraft(REP_ID, createRequest(null, createLine(PRODUCT_A, 1, null)));

            assertThat(response.representativeId()).isEqualTo(REP_ID);
        }

        @Test
        @DisplayName("rejects an unknown customer (404)")
        void rejectsUnknownCustomer() {
            when(customerFacade.exists(CUSTOMER_ID)).thenReturn(false);

            assertThatThrownBy(() -> service.createDraft(REP_ID,
                    createRequest(null, createLine(PRODUCT_A, 1, null))))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        var be = (BusinessException) e;
                        assertThat(be.getErrorCode()).isEqualTo("CUSTOMER_NOT_FOUND");
                        assertThat(be.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    });
        }

        @Test
        @DisplayName("rejects an inactive customer (422)")
        void rejectsInactiveCustomer() {
            when(customerFacade.isActive(CUSTOMER_ID)).thenReturn(false);

            assertThatThrownBy(() -> service.createDraft(REP_ID,
                    createRequest(null, createLine(PRODUCT_A, 1, null))))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo("CUSTOMER_NOT_ACTIVE"));
        }

        @Test
        @DisplayName("rejects duplicate products on one invoice (D11)")
        void rejectsDuplicateProducts() {
            assertThatThrownBy(() -> service.createDraft(REP_ID, createRequest(null,
                    createLine(PRODUCT_A, 1, null),
                    createLine(PRODUCT_A, 2, null))))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo("INVOICE_DUPLICATE_PRODUCT"));
        }

        @Test
        @DisplayName("rejects a discount larger than the line total (D7)")
        void rejectsExcessiveDiscount() {
            // 1 x 10.00 = 10.00 gross; a 15.00 discount would make the line negative.
            assertThatThrownBy(() -> service.createDraft(REP_ID,
                    createRequest(null, createLine(PRODUCT_A, 1, "15.00"))))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo("INVOICE_DISCOUNT_EXCEEDS_LINE"));
        }

        @Test
        @DisplayName("accepts a discount exactly equal to the line total")
        void acceptsFullDiscount() {
            var response = service.createDraft(REP_ID,
                    createRequest(null, createLine(PRODUCT_A, 1, "10.00")));

            assertThat(response.totalAmount()).isEqualByComparingTo("0.00");
        }
    }

    @Nested
    @DisplayName("createDraft — visit binding (D13)")
    class VisitBinding {

        private static final Long VISIT_ID = 900L;

        @Test
        @DisplayName("accepts a null visit — ad-hoc sale off route")
        void acceptsNullVisit() {
            var response = service.createDraft(REP_ID, createRequest(null, createLine(PRODUCT_A, 1, null)));

            assertThat(response.visitId()).isNull();
            verify(visitFacade, never()).existsById(anyLong());
        }

        @Test
        @DisplayName("accepts a visit belonging to this customer and rep")
        void acceptsMatchingVisit() {
            when(visitFacade.existsById(VISIT_ID)).thenReturn(true);
            when(visitFacade.getVisitInfo(VISIT_ID)).thenReturn(new VisitInfo(
                    VISIT_ID, 1L, CUSTOMER_ID, REP_ID, "IN_PROGRESS", Instant.now(), null));

            var response = service.createDraft(REP_ID,
                    createRequest(VISIT_ID, createLine(PRODUCT_A, 1, null)));

            assertThat(response.visitId()).isEqualTo(VISIT_ID);
        }

        @Test
        @DisplayName("rejects an unknown visit (404)")
        void rejectsUnknownVisit() {
            when(visitFacade.existsById(VISIT_ID)).thenReturn(false);

            assertThatThrownBy(() -> service.createDraft(REP_ID,
                    createRequest(VISIT_ID, createLine(PRODUCT_A, 1, null))))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo("VISIT_NOT_FOUND"));
        }

        @Test
        @DisplayName("rejects another rep's visit — no stapling someone else's visit")
        void rejectsVisitOfAnotherRep() {
            when(visitFacade.existsById(VISIT_ID)).thenReturn(true);
            when(visitFacade.getVisitInfo(VISIT_ID)).thenReturn(new VisitInfo(
                    VISIT_ID, 1L, CUSTOMER_ID, OTHER_REP_ID, "IN_PROGRESS", Instant.now(), null));

            assertThatThrownBy(() -> service.createDraft(REP_ID,
                    createRequest(VISIT_ID, createLine(PRODUCT_A, 1, null))))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo("VISIT_MISMATCH"));
        }

        @Test
        @DisplayName("rejects a visit for a different customer")
        void rejectsVisitOfDifferentCustomer() {
            when(visitFacade.existsById(VISIT_ID)).thenReturn(true);
            when(visitFacade.getVisitInfo(VISIT_ID)).thenReturn(new VisitInfo(
                    VISIT_ID, 1L, 999L, REP_ID, "IN_PROGRESS", Instant.now(), null));

            assertThatThrownBy(() -> service.createDraft(REP_ID,
                    createRequest(VISIT_ID, createLine(PRODUCT_A, 1, null))))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo("VISIT_MISMATCH"));
        }
    }

    @Nested
    @DisplayName("createDraft — offline idempotency (D22)")
    class Idempotency {

        @Test
        @DisplayName("a retried submit with a known clientUuid returns the existing invoice")
        void returnsExistingInvoiceOnRetry() {
            Invoice existing = draftInvoice(REP_ID);
            when(invoiceRepository.findByClientUuid("uuid-1")).thenReturn(Optional.of(existing));
            givenInvoiceExists(existing);

            var request = new CreateInvoiceRequest(CUSTOMER_ID, null, "uuid-1",
                    List.of(createLine(PRODUCT_A, 1, null)));
            var response = service.createDraft(REP_ID, request);

            assertThat(response.id()).isEqualTo(INVOICE_ID);
            // Crucially: no second invoice is persisted.
            verify(invoiceRepository, never()).save(any(Invoice.class));
        }

        @Test
        @DisplayName("an unseen clientUuid creates a new invoice")
        void createsWhenUuidUnseen() {
            when(invoiceRepository.findByClientUuid("uuid-2")).thenReturn(Optional.empty());

            var request = new CreateInvoiceRequest(CUSTOMER_ID, null, "uuid-2",
                    List.of(createLine(PRODUCT_A, 1, null)));
            var response = service.createDraft(REP_ID, request);

            assertThat(response.clientUuid()).isEqualTo("uuid-2");
            verify(invoiceRepository).save(any(Invoice.class));
        }
    }

    @Nested
    @DisplayName("updateDraft and deleteDraft — ownership (BR-1)")
    class DraftMutation {

        @Test
        @DisplayName("the owner may replace the lines, re-capturing prices")
        void ownerMayUpdate() {
            givenInvoiceExists(draftInvoice(REP_ID));

            var request = new UpdateInvoiceRequest(List.of(
                    new UpdateInvoiceRequest.Line(PRODUCT_B, 3, null)));
            var response = service.updateDraft(INVOICE_ID, REP_ID, request);

            assertThat(response.lines()).hasSize(1);
            assertThat(response.totalAmount()).isEqualByComparingTo("15.00");
            verify(inventoryFacade).getProductPrice(PRODUCT_B);
        }

        @Test
        @DisplayName("another rep may not update the draft (403)")
        void nonOwnerMayNotUpdate() {
            givenInvoiceExists(draftInvoice(REP_ID));

            var request = new UpdateInvoiceRequest(List.of(
                    new UpdateInvoiceRequest.Line(PRODUCT_B, 1, null)));

            assertThatThrownBy(() -> service.updateDraft(INVOICE_ID, OTHER_REP_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        var be = (BusinessException) e;
                        assertThat(be.getErrorCode()).isEqualTo("INVOICE_NOT_OWNED");
                        assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    });
        }

        @Test
        @DisplayName("a SENT invoice cannot be updated (409)")
        void sentCannotBeUpdated() {
            Invoice sent = draftInvoice(REP_ID);
            sent.addLine(new com.salesmanagement.invoicing.internal.entity.InvoiceLineItem(
                    PRODUCT_A, 1, new BigDecimal("10.00"), BigDecimal.ZERO));
            sent.markSent();
            givenInvoiceExists(sent);

            var request = new UpdateInvoiceRequest(List.of(
                    new UpdateInvoiceRequest.Line(PRODUCT_B, 1, null)));

            assertThatThrownBy(() -> service.updateDraft(INVOICE_ID, REP_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo("INVOICE_NOT_DRAFT"));
        }

        @Test
        @DisplayName("the owner may delete their draft")
        void ownerMayDelete() {
            Invoice draft = draftInvoice(REP_ID);
            givenInvoiceExists(draft);

            service.deleteDraft(INVOICE_ID, REP_ID);

            verify(invoiceRepository).delete(draft);
        }

        @Test
        @DisplayName("another rep may not delete the draft")
        void nonOwnerMayNotDelete() {
            givenInvoiceExists(draftInvoice(REP_ID));

            assertThatThrownBy(() -> service.deleteDraft(INVOICE_ID, OTHER_REP_ID))
                    .isInstanceOf(BusinessException.class);
            verify(invoiceRepository, never()).delete(any(Invoice.class));
        }
    }

    @Nested
    @DisplayName("submit — hard gate and atomic deduction")
    class Submit {

        private Invoice draftWithTwoLines() {
            Invoice invoice = draftInvoice(REP_ID);
            invoice.addLine(new com.salesmanagement.invoicing.internal.entity.InvoiceLineItem(
                    PRODUCT_A, 2, new BigDecimal("10.00"), BigDecimal.ZERO));
            invoice.addLine(new com.salesmanagement.invoicing.internal.entity.InvoiceLineItem(
                    PRODUCT_B, 3, new BigDecimal("5.00"), BigDecimal.ZERO));
            return invoice;
        }

        @Test
        @DisplayName("deducts van stock once per line, then flips to SENT")
        void deductsPerLineAndFlipsToSent() {
            givenInvoiceExists(draftWithTwoLines());

            var response = service.submit(INVOICE_ID, REP_ID, fullEpodRequest());

            verify(inventoryFacade).deductVanStock(REP_ID, PRODUCT_A, 2);
            verify(inventoryFacade).deductVanStock(REP_ID, PRODUCT_B, 3);
            verify(inventoryFacade, times(2)).deductVanStock(anyLong(), anyLong(), anyInt());
            assertThat(response.status()).isEqualTo(InvoiceStatus.SENT);
            assertThat(response.totalAmount()).isEqualByComparingTo("35.00");
        }

        @Test
        @DisplayName("insufficient stock propagates and nothing is marked SENT (BR-4)")
        void insufficientStockAborts() {
            Invoice draft = draftWithTwoLines();
            givenInvoiceExists(draft);
            doThrow(BusinessException.unprocessable("Insufficient van stock", "INSUFFICIENT_STOCK"))
                    .when(inventoryFacade).deductVanStock(REP_ID, PRODUCT_B, 3);

            assertThatThrownBy(() -> service.submit(INVOICE_ID, REP_ID, fullEpodRequest()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo("INSUFFICIENT_STOCK"));

            // The invoice is left in DRAFT; the transaction rolls back the first deduction.
            assertThat(draft.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
        }

        @Test
        @DisplayName("attaches both ePOD artifacts and publishes the submitted event")
        void attachesEpodAndPublishes() {
            givenInvoiceExists(draftWithTwoLines());

            var response = service.submit(INVOICE_ID, REP_ID, fullEpodRequest());

            assertThat(response.epodArtifacts()).hasSize(2);
            verify(events).publishEvent(any(InvoiceSubmittedEvent.class));
        }

        @Test
        @DisplayName("hashes each artifact against the frozen total, after recomputation")
        void hashesAgainstFrozenTotal() {
            givenInvoiceExists(draftWithTwoLines());

            service.submit(INVOICE_ID, REP_ID, fullEpodRequest());

            ArgumentCaptor<BigDecimal> totalCaptor = ArgumentCaptor.forClass(BigDecimal.class);
            verify(epodStorage, times(2)).hash(any(), eq(INVOICE_ID), eq(CUSTOMER_ID),
                    totalCaptor.capture(), any(), any(), any());
            assertThat(totalCaptor.getAllValues())
                    .allSatisfy(t -> assertThat(t).isEqualByComparingTo("35.00"));
        }

        @Test
        @DisplayName("rejects a submit missing the delivery photo (422)")
        void rejectsMissingArtifactType() {
            givenInvoiceExists(draftWithTwoLines());

            var onlySignature = new SubmitInvoiceRequest(List.of(
                    new SubmitInvoiceRequest.Artifact(EpodArtifactType.SIGNATURE, "sig.png",
                            null, null, Instant.now())));

            assertThatThrownBy(() -> service.submit(INVOICE_ID, REP_ID, onlySignature))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo("EPOD_ARTIFACT_MISSING"));

            verify(inventoryFacade, never()).deductVanStock(anyLong(), anyLong(), anyInt());
        }

        @Test
        @DisplayName("rejects submitting an empty invoice")
        void rejectsEmptyInvoice() {
            givenInvoiceExists(draftInvoice(REP_ID));

            assertThatThrownBy(() -> service.submit(INVOICE_ID, REP_ID, fullEpodRequest()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo("INVOICE_NO_LINES"));
        }

        @Test
        @DisplayName("another rep cannot submit this draft (BR-1)")
        void nonOwnerCannotSubmit() {
            givenInvoiceExists(draftWithTwoLines());

            assertThatThrownBy(() -> service.submit(INVOICE_ID, OTHER_REP_ID, fullEpodRequest()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo("INVOICE_NOT_OWNED"));

            verify(inventoryFacade, never()).deductVanStock(anyLong(), anyLong(), anyInt());
        }

        @Test
        @DisplayName("a customer deactivated after draft creation blocks submit (D5b)")
        void inactiveCustomerBlocksSubmit() {
            givenInvoiceExists(draftWithTwoLines());
            when(customerFacade.isActive(CUSTOMER_ID)).thenReturn(false);

            assertThatThrownBy(() -> service.submit(INVOICE_ID, REP_ID, fullEpodRequest()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo("CUSTOMER_NOT_ACTIVE"));

            verify(inventoryFacade, never()).deductVanStock(anyLong(), anyLong(), anyInt());
        }

        @Test
        @DisplayName("a DISCONTINUED product still submits — van stock may be sold off (D12)")
        void discontinuedProductStillSells() {
            givenInvoiceExists(draftWithTwoLines());
            // Product is no longer active, but exists and is physically on the van.
            when(inventoryFacade.isProductActive(PRODUCT_A)).thenReturn(false);

            var response = service.submit(INVOICE_ID, REP_ID, fullEpodRequest());

            assertThat(response.status()).isEqualTo(InvoiceStatus.SENT);
            verify(inventoryFacade).deductVanStock(REP_ID, PRODUCT_A, 2);
        }

        @Test
        @DisplayName("a product deleted from the catalogue blocks submit (404)")
        void missingProductBlocksSubmit() {
            givenInvoiceExists(draftWithTwoLines());
            when(inventoryFacade.productExists(PRODUCT_B)).thenReturn(false);

            assertThatThrownBy(() -> service.submit(INVOICE_ID, REP_ID, fullEpodRequest()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo("PRODUCT_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("approve and reject")
    class Review {

        private Invoice sentInvoice() {
            Invoice invoice = draftInvoice(REP_ID);
            invoice.addLine(new com.salesmanagement.invoicing.internal.entity.InvoiceLineItem(
                    PRODUCT_A, 1, new BigDecimal("10.00"), BigDecimal.ZERO));
            invoice.markSent();
            return invoice;
        }

        @Test
        @DisplayName("approve records the reviewer and publishes the event")
        void approveRecordsReviewer() {
            givenInvoiceExists(sentInvoice());

            var response = service.approve(INVOICE_ID, REVIEWER_ID);

            assertThat(response.status()).isEqualTo(InvoiceStatus.APPROVED);
            assertThat(response.reviewedById()).isEqualTo(REVIEWER_ID);
            verify(events).publishEvent(any(InvoiceApprovedEvent.class));
        }

        @Test
        @DisplayName("approve moves no stock — it is informational only")
        void approveMovesNoStock() {
            givenInvoiceExists(sentInvoice());

            service.approve(INVOICE_ID, REVIEWER_ID);

            verify(inventoryFacade, never()).deductVanStock(anyLong(), anyLong(), anyInt());
            verify(inventoryFacade, never()).returnVanToWarehouse(anyLong(), anyLong(), anyInt());
        }

        @Test
        @DisplayName("reject records the reason and publishes it in the event (BR-3)")
        void rejectRecordsReason() {
            givenInvoiceExists(sentInvoice());

            var response = service.reject(INVOICE_ID, REVIEWER_ID, "Unauthorised discount");

            assertThat(response.status()).isEqualTo(InvoiceStatus.REJECTED);
            assertThat(response.rejectionReason()).isEqualTo("Unauthorised discount");

            ArgumentCaptor<InvoiceRejectedEvent> captor =
                    ArgumentCaptor.forClass(InvoiceRejectedEvent.class);
            verify(events).publishEvent(captor.capture());
            assertThat(captor.getValue().reason()).isEqualTo("Unauthorised discount");
            assertThat(captor.getValue().representativeId()).isEqualTo(REP_ID);
        }

        @Test
        @DisplayName("reject does NOT return stock — the goods physically left the van (D1)")
        void rejectDoesNotReturnStock() {
            givenInvoiceExists(sentInvoice());

            service.reject(INVOICE_ID, REVIEWER_ID, "wrong price");

            verify(inventoryFacade, never()).returnVanToWarehouse(anyLong(), anyLong(), anyInt());
            verify(inventoryFacade, never()).transferWarehouseToVan(anyLong(), anyLong(), anyInt());
        }

        @Test
        @DisplayName("a blank reason is refused and no event is published")
        void blankReasonRefused() {
            givenInvoiceExists(sentInvoice());

            assertThatThrownBy(() -> service.reject(INVOICE_ID, REVIEWER_ID, "  "))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo("INVOICE_REJECTION_REASON_REQUIRED"));

            verify(events, never()).publishEvent(any(InvoiceRejectedEvent.class));
        }

        @Test
        @DisplayName("a DRAFT cannot be approved")
        void cannotApproveDraft() {
            givenInvoiceExists(draftInvoice(REP_ID));

            assertThatThrownBy(() -> service.approve(INVOICE_ID, REVIEWER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo("INVOICE_NOT_SENT"));
        }
    }

    @Nested
    @DisplayName("getById — read scope")
    class ReadScope {

        @Test
        @DisplayName("a rep may read their own invoice")
        void ownerMayRead() {
            givenInvoiceExists(draftInvoice(REP_ID));

            var response = service.getById(INVOICE_ID, REP_ID, false);

            assertThat(response.id()).isEqualTo(INVOICE_ID);
        }

        @Test
        @DisplayName("a rep may not read another rep's invoice (403)")
        void nonOwnerRepMayNotRead() {
            givenInvoiceExists(draftInvoice(REP_ID));

            assertThatThrownBy(() -> service.getById(INVOICE_ID, OTHER_REP_ID, false))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        var be = (BusinessException) e;
                        assertThat(be.getErrorCode()).isEqualTo("INVOICE_NOT_OWNED");
                        assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    });
        }

        @Test
        @DisplayName("a manager or admin may read any invoice for oversight")
        void oversightMayReadAny() {
            givenInvoiceExists(draftInvoice(REP_ID));

            var response = service.getById(INVOICE_ID, REVIEWER_ID, true);

            assertThat(response.id()).isEqualTo(INVOICE_ID);
        }

        @Test
        @DisplayName("an unknown invoice is a 404")
        void unknownInvoiceIsNotFound() {
            when(invoiceRepository.findWithLinesById(INVOICE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getById(INVOICE_ID, REP_ID, true))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        var be = (BusinessException) e;
                        assertThat(be.getErrorCode()).isEqualTo("INVOICE_NOT_FOUND");
                        assertThat(be.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    });
        }
    }
}
