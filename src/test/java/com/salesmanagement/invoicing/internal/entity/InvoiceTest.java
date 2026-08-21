package com.salesmanagement.invoicing.internal.entity;

import com.salesmanagement.invoicing.internal.enums.EpodArtifactType;
import com.salesmanagement.invoicing.internal.enums.InvoiceStatus;
import com.salesmanagement.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The invoice state machine and total recomputation (decisions D5, D6).
 *
 * <p>Pure unit tests over the aggregate — no Spring, no database, no facades. They pin the two
 * invariants the module cannot be wrong about: <em>a SENT invoice is immutable</em>, and
 * <em>the total always equals the sum of line subtotals</em>.</p>
 */
@DisplayName("Invoice — state machine and totals")
class InvoiceTest {

    private static final Long CUSTOMER_ID = 100L;
    private static final Long REP_ID      = 200L;
    private static final Long REVIEWER_ID = 300L;

    private Invoice invoice;

    @BeforeEach
    void setUp() {
        invoice = new Invoice(CUSTOMER_ID, REP_ID, null, LocalDate.of(2026, 7, 18), null);
    }

    private static InvoiceLineItem line(Long productId, int qty, String price) {
        return new InvoiceLineItem(productId, qty, new BigDecimal(price), BigDecimal.ZERO);
    }

    private static EpodArtifact artifact(EpodArtifactType type) {
        return new EpodArtifact(type, "path/f.png", "hash", null, null, Instant.now());
    }

    /** Puts the invoice into SENT with one line, mirroring what the service does. */
    private void submitInvoice() {
        invoice.addLine(line(1L, 1, "10.00"));
        invoice.markSent();
    }

    @Nested
    @DisplayName("creation")
    class Creation {

        @Test
        @DisplayName("opens as DRAFT with a zero total and no lines")
        void opensAsDraft() {
            assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
            assertThat(invoice.isDraft()).isTrue();
            assertThat(invoice.getTotalAmount()).isEqualByComparingTo("0.00");
            assertThat(invoice.getLines()).isEmpty();
            assertThat(invoice.getEpodArtifacts()).isEmpty();
        }

        @Test
        @DisplayName("carries no reviewer or rejection reason until reviewed")
        void noReviewDataInitially() {
            assertThat(invoice.getReviewedById()).isNull();
            assertThat(invoice.getRejectionReason()).isNull();
        }
    }

    @Nested
    @DisplayName("total recomputation")
    class TotalRecomputation {

        @Test
        @DisplayName("total is the sum of line subtotals")
        void totalSumsLines() {
            invoice.addLine(line(1L, 2, "10.00"));   // 20.00
            invoice.addLine(line(2L, 3, "5.50"));    // 16.50

            assertThat(invoice.getTotalAmount()).isEqualByComparingTo("36.50");
        }

        @Test
        @DisplayName("total drops when a line is removed")
        void totalDropsOnRemoval() {
            invoice.addLine(line(1L, 2, "10.00"));
            invoice.addLine(line(2L, 1, "5.00"));
            assertThat(invoice.getTotalAmount()).isEqualByComparingTo("25.00");

            boolean removed = invoice.removeLineByProduct(2L);

            assertThat(removed).isTrue();
            assertThat(invoice.getTotalAmount()).isEqualByComparingTo("20.00");
        }

        @Test
        @DisplayName("removing an absent product changes nothing")
        void removingAbsentProductIsNoop() {
            invoice.addLine(line(1L, 1, "10.00"));

            boolean removed = invoice.removeLineByProduct(999L);

            assertThat(removed).isFalse();
            assertThat(invoice.getTotalAmount()).isEqualByComparingTo("10.00");
        }

        @Test
        @DisplayName("clearing lines resets the total to zero")
        void clearingResetsTotal() {
            invoice.addLine(line(1L, 2, "10.00"));

            invoice.clearLines();

            assertThat(invoice.getLines()).isEmpty();
            assertThat(invoice.getTotalAmount()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("a discounted line contributes its discounted subtotal")
        void discountFlowsIntoTotal() {
            invoice.addLine(new InvoiceLineItem(1L, 4, new BigDecimal("25.00"), new BigDecimal("10.00")));

            assertThat(invoice.getTotalAmount()).isEqualByComparingTo("90.00");
        }
    }

    @Nested
    @DisplayName("DRAFT is mutable, SENT is not")
    class Mutability {

        @Test
        @DisplayName("lines can be added while DRAFT")
        void canAddLineWhileDraft() {
            assertThatCode(() -> invoice.addLine(line(1L, 1, "10.00"))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("adding a line to a SENT invoice is rejected (409)")
        void cannotAddLineWhenSent() {
            submitInvoice();

            assertThatThrownBy(() -> invoice.addLine(line(2L, 1, "5.00")))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        BusinessException be = (BusinessException) e;
                        assertThat(be.getErrorCode()).isEqualTo("INVOICE_NOT_DRAFT");
                        assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    });
        }

        @Test
        @DisplayName("removing a line from a SENT invoice is rejected")
        void cannotRemoveLineWhenSent() {
            submitInvoice();

            assertThatThrownBy(() -> invoice.removeLineByProduct(1L))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("clearing lines on a SENT invoice is rejected")
        void cannotClearLinesWhenSent() {
            submitInvoice();

            assertThatThrownBy(() -> invoice.clearLines())
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("attaching ePOD to a SENT invoice is rejected — proof is frozen")
        void cannotAttachEpodWhenSent() {
            submitInvoice();

            assertThatThrownBy(() -> invoice.addEpodArtifact(artifact(EpodArtifactType.SIGNATURE)))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("an APPROVED invoice is also immutable")
        void approvedIsImmutable() {
            submitInvoice();
            invoice.approve(REVIEWER_ID);

            assertThatThrownBy(() -> invoice.addLine(line(2L, 1, "5.00")))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("ePOD attachment")
    class Epod {

        @Test
        @DisplayName("reports which artifact types are present")
        void reportsPresentTypes() {
            assertThat(invoice.hasArtifact(EpodArtifactType.SIGNATURE)).isFalse();

            invoice.addEpodArtifact(artifact(EpodArtifactType.SIGNATURE));

            assertThat(invoice.hasArtifact(EpodArtifactType.SIGNATURE)).isTrue();
            assertThat(invoice.hasArtifact(EpodArtifactType.DELIVERY_PHOTO)).isFalse();
        }

        @Test
        @DisplayName("attaching sets the back-reference to this invoice")
        void setsBackReference() {
            EpodArtifact a = artifact(EpodArtifactType.DELIVERY_PHOTO);

            invoice.addEpodArtifact(a);

            assertThat(a.getInvoice()).isSameAs(invoice);
        }
    }

    @Nested
    @DisplayName("transitions")
    class Transitions {

        @Test
        @DisplayName("DRAFT -> SENT")
        void draftToSent() {
            invoice.markSent();

            assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.SENT);
            assertThat(invoice.isDraft()).isFalse();
        }

        @Test
        @DisplayName("SENT -> APPROVED records the reviewer")
        void sentToApproved() {
            submitInvoice();

            invoice.approve(REVIEWER_ID);

            assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.APPROVED);
            assertThat(invoice.getReviewedById()).isEqualTo(REVIEWER_ID);
            assertThat(invoice.getRejectionReason()).isNull();
        }

        @Test
        @DisplayName("SENT -> REJECTED records the reviewer and the trimmed reason")
        void sentToRejected() {
            submitInvoice();

            invoice.reject(REVIEWER_ID, "  Price above agreed rate  ");

            assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.REJECTED);
            assertThat(invoice.getReviewedById()).isEqualTo(REVIEWER_ID);
            assertThat(invoice.getRejectionReason()).isEqualTo("Price above agreed rate");
        }

        @Test
        @DisplayName("cannot submit an invoice twice")
        void cannotSubmitTwice() {
            submitInvoice();

            assertThatThrownBy(() -> invoice.markSent())
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo("INVOICE_NOT_DRAFT"));
        }

        @Test
        @DisplayName("cannot approve a DRAFT — review only follows submission")
        void cannotApproveDraft() {
            assertThatThrownBy(() -> invoice.approve(REVIEWER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        BusinessException be = (BusinessException) e;
                        assertThat(be.getErrorCode()).isEqualTo("INVOICE_NOT_SENT");
                        assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    });
        }

        @Test
        @DisplayName("cannot reject a DRAFT")
        void cannotRejectDraft() {
            assertThatThrownBy(() -> invoice.reject(REVIEWER_ID, "reason"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("APPROVED is terminal — cannot be re-reviewed")
        void approvedIsTerminal() {
            submitInvoice();
            invoice.approve(REVIEWER_ID);

            assertThatThrownBy(() -> invoice.reject(REVIEWER_ID, "changed my mind"))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> invoice.approve(REVIEWER_ID))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("REJECTED is terminal — no re-submit, a correction is a new invoice")
        void rejectedIsTerminal() {
            submitInvoice();
            invoice.reject(REVIEWER_ID, "wrong price");

            assertThatThrownBy(() -> invoice.approve(REVIEWER_ID))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> invoice.markSent())
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("rejection reason is mandatory (BR-3)")
    class RejectionReason {

        @Test
        @DisplayName("a null reason is rejected (400)")
        void nullReasonRejected() {
            submitInvoice();

            assertThatThrownBy(() -> invoice.reject(REVIEWER_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        BusinessException be = (BusinessException) e;
                        assertThat(be.getErrorCode()).isEqualTo("INVOICE_REJECTION_REASON_REQUIRED");
                        assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    });
        }

        @Test
        @DisplayName("a blank reason is rejected")
        void blankReasonRejected() {
            submitInvoice();

            assertThatThrownBy(() -> invoice.reject(REVIEWER_ID, "   "))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("a failed rejection leaves the invoice SENT, not half-rejected")
        void failedRejectionLeavesStateUntouched() {
            submitInvoice();

            assertThatThrownBy(() -> invoice.reject(REVIEWER_ID, ""))
                    .isInstanceOf(BusinessException.class);

            assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.SENT);
            assertThat(invoice.getReviewedById()).isNull();
        }
    }
}
