package com.salesmanagement.invoicing.internal.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Money math for a single invoice line (decision D9).
 *
 * <p>These are pure unit tests — no Spring, no database. They pin the arithmetic that the whole
 * module's correctness rests on: {@code subtotal = quantity * price - discount}, always at scale
 * 2 with {@code HALF_UP}, and never in floating point.</p>
 */
@DisplayName("InvoiceLineItem — money math")
class InvoiceLineItemTest {

    private static BigDecimal money(String v) {
        return new BigDecimal(v);
    }

    @Nested
    @DisplayName("subtotal computation")
    class SubtotalComputation {

        @Test
        @DisplayName("computes quantity * price with no discount")
        void computesGrossWithoutDiscount() {
            InvoiceLineItem line = new InvoiceLineItem(1L, 3, money("10.00"), BigDecimal.ZERO);

            assertThat(line.getSubtotal()).isEqualByComparingTo("30.00");
        }

        @Test
        @DisplayName("subtracts a fixed discount from the gross")
        void subtractsDiscount() {
            InvoiceLineItem line = new InvoiceLineItem(1L, 4, money("25.00"), money("10.00"));

            // 4 * 25.00 = 100.00, minus 10.00 discount
            assertThat(line.getSubtotal()).isEqualByComparingTo("90.00");
        }

        @Test
        @DisplayName("treats a null discount as zero")
        void nullDiscountIsZero() {
            InvoiceLineItem line = new InvoiceLineItem(1L, 2, money("7.50"), null);

            assertThat(line.getDiscount()).isEqualByComparingTo("0.00");
            assertThat(line.getSubtotal()).isEqualByComparingTo("15.00");
        }

        @Test
        @DisplayName("a discount equal to the gross yields a zero subtotal")
        void fullDiscountYieldsZero() {
            InvoiceLineItem line = new InvoiceLineItem(1L, 2, money("12.00"), money("24.00"));

            assertThat(line.getSubtotal()).isEqualByComparingTo("0.00");
        }
    }

    @Nested
    @DisplayName("scale and rounding")
    class ScaleAndRounding {

        @Test
        @DisplayName("always stores money at scale 2")
        void alwaysScaleTwo() {
            InvoiceLineItem line = new InvoiceLineItem(1L, 1, money("5"), money("1"));

            assertThat(line.getPrice().scale()).isEqualTo(2);
            assertThat(line.getDiscount().scale()).isEqualTo(2);
            assertThat(line.getSubtotal().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("rounds a half-cent up (HALF_UP), not down")
        void roundsHalfUp() {
            // 12.345 rounds to 12.35 under HALF_UP (12.34 under HALF_EVEN — this pins the rule)
            InvoiceLineItem line = new InvoiceLineItem(1L, 1, money("12.345"), BigDecimal.ZERO);

            assertThat(line.getPrice()).isEqualByComparingTo("12.35");
        }

        @Test
        @DisplayName("exact decimal arithmetic — no floating point drift")
        void noFloatingPointDrift() {
            // The classic double trap: 0.1 + 0.2 != 0.3. With BigDecimal, 3 * 0.10 is exactly 0.30.
            InvoiceLineItem line = new InvoiceLineItem(1L, 3, money("0.10"), BigDecimal.ZERO);

            assertThat(line.getSubtotal()).isEqualByComparingTo("0.30");
        }

        @Test
        @DisplayName("large quantities stay exact")
        void largeQuantitiesStayExact() {
            InvoiceLineItem line = new InvoiceLineItem(1L, 999, money("19.99"), BigDecimal.ZERO);

            assertThat(line.getSubtotal()).isEqualByComparingTo("19970.01");
        }
    }

    @Nested
    @DisplayName("recompute after mutation")
    class Recompute {

        @Test
        @DisplayName("recompute() refreshes the subtotal after a quantity change")
        void recomputesAfterQuantityChange() {
            InvoiceLineItem line = new InvoiceLineItem(1L, 2, money("10.00"), BigDecimal.ZERO);
            assertThat(line.getSubtotal()).isEqualByComparingTo("20.00");

            line.setQuantity(5);
            line.recompute();

            assertThat(line.getSubtotal()).isEqualByComparingTo("50.00");
        }

        @Test
        @DisplayName("gross() reports the pre-discount amount")
        void grossIgnoresDiscount() {
            InvoiceLineItem line = new InvoiceLineItem(1L, 3, money("10.00"), money("5.00"));

            assertThat(line.gross()).isEqualByComparingTo("30.00");
            assertThat(line.getSubtotal()).isEqualByComparingTo("25.00");
        }
    }
}
