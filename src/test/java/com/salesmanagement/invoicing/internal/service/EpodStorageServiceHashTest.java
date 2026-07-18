package com.salesmanagement.invoicing.internal.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ePOD tamper-detection hash (decision D16).
 *
 * <p>This is the security claim of the whole ePOD design, so it is tested as a set of
 * <em>properties</em> rather than golden values: the fingerprint must change if <em>anything</em>
 * in the bundle changes — the image, which invoice it belongs to, the amount, or when/where it was
 * captured — and must be stable when nothing changes.</p>
 *
 * <p>No Spring context: the storage service is constructed directly with temp paths, since hashing
 * touches no filesystem.</p>
 */
@DisplayName("EpodStorageService — tamper-detection hash")
class EpodStorageServiceHashTest {

    private static final Long       INVOICE_ID  = 42L;
    private static final Long       CUSTOMER_ID = 7L;
    private static final BigDecimal TOTAL       = new BigDecimal("150.00");
    private static final Instant    CAPTURED_AT = Instant.parse("2026-07-18T09:30:00Z");
    private static final BigDecimal LAT         = new BigDecimal("52.370216");
    private static final BigDecimal LON         = new BigDecimal("4.895168");

    private EpodStorageService storage;
    private byte[] signatureBytes;

    @BeforeEach
    void setUp() {
        storage = new EpodStorageService("./target/test-epod-uploads", "./target/test-epod-storage");
        signatureBytes = "a-customer-signature-image".getBytes(StandardCharsets.UTF_8);
    }

    private String hashOf(byte[] bytes, Long invoiceId, Long customerId, BigDecimal total,
                          Instant capturedAt, BigDecimal lat, BigDecimal lon) {
        return storage.hash(bytes, invoiceId, customerId, total, capturedAt, lat, lon);
    }

    private String baseline() {
        return hashOf(signatureBytes, INVOICE_ID, CUSTOMER_ID, TOTAL, CAPTURED_AT, LAT, LON);
    }

    @Nested
    @DisplayName("shape and stability")
    class ShapeAndStability {

        @Test
        @DisplayName("produces a 64-character lowercase hex SHA-256")
        void producesHexSha256() {
            String hash = baseline();

            assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("is deterministic — same inputs, same fingerprint")
        void isDeterministic() {
            assertThat(baseline()).isEqualTo(baseline());
        }

        @Test
        @DisplayName("verifying an untampered file reproduces the stored hash")
        void verificationReproducesStoredHash() {
            String storedAtSubmit = baseline();

            // Later re-verification: read the same bytes, recompute with the same invoice data.
            String recomputed = hashOf(signatureBytes.clone(), INVOICE_ID, CUSTOMER_ID, TOTAL,
                    CAPTURED_AT, LAT, LON);

            assertThat(recomputed).isEqualTo(storedAtSubmit);
        }
    }

    @Nested
    @DisplayName("detects tampering with the file")
    class FileTampering {

        @Test
        @DisplayName("a modified image produces a different fingerprint")
        void modifiedImageBreaksHash() {
            String original = baseline();

            byte[] tampered = "a-DIFFERENT-signature-image".getBytes(StandardCharsets.UTF_8);
            String after = hashOf(tampered, INVOICE_ID, CUSTOMER_ID, TOTAL, CAPTURED_AT, LAT, LON);

            assertThat(after).isNotEqualTo(original);
        }

        @Test
        @DisplayName("even a single flipped byte breaks the fingerprint")
        void singleByteChangeBreaksHash() {
            String original = baseline();

            byte[] tampered = signatureBytes.clone();
            tampered[0] = (byte) (tampered[0] ^ 0x01);
            String after = hashOf(tampered, INVOICE_ID, CUSTOMER_ID, TOTAL, CAPTURED_AT, LAT, LON);

            assertThat(after).isNotEqualTo(original);
        }
    }

    @Nested
    @DisplayName("binds the file to its invoice")
    class InvoiceBinding {

        @Test
        @DisplayName("the same signature on a different invoice hashes differently — no reuse")
        void sameSignatureDifferentInvoice() {
            String onInvoice42 = baseline();

            String onInvoice99 = hashOf(signatureBytes, 99L, CUSTOMER_ID, TOTAL,
                    CAPTURED_AT, LAT, LON);

            assertThat(onInvoice99).isNotEqualTo(onInvoice42);
        }

        @Test
        @DisplayName("a different customer changes the fingerprint")
        void differentCustomerChangesHash() {
            String original = baseline();

            String other = hashOf(signatureBytes, INVOICE_ID, 8L, TOTAL, CAPTURED_AT, LAT, LON);

            assertThat(other).isNotEqualTo(original);
        }

        @Test
        @DisplayName("editing the invoice total after the fact breaks the fingerprint")
        void editedTotalBreaksHash() {
            String original = baseline();

            String afterEdit = hashOf(signatureBytes, INVOICE_ID, CUSTOMER_ID,
                    new BigDecimal("150.01"), CAPTURED_AT, LAT, LON);

            assertThat(afterEdit).isNotEqualTo(original);
        }
    }

    @Nested
    @DisplayName("protects the capture metadata")
    class MetadataProtection {

        @Test
        @DisplayName("changing the capture time breaks the fingerprint")
        void editedTimestampBreaksHash() {
            String original = baseline();

            String afterEdit = hashOf(signatureBytes, INVOICE_ID, CUSTOMER_ID, TOTAL,
                    Instant.parse("2026-07-18T11:30:00Z"), LAT, LON);

            assertThat(afterEdit).isNotEqualTo(original);
        }

        @Test
        @DisplayName("moving the capture location breaks the fingerprint")
        void editedLocationBreaksHash() {
            String original = baseline();

            String afterEdit = hashOf(signatureBytes, INVOICE_ID, CUSTOMER_ID, TOTAL,
                    CAPTURED_AT, new BigDecimal("51.924420"), LON);

            assertThat(afterEdit).isNotEqualTo(original);
        }

        @Test
        @DisplayName("absent coordinates hash differently from present ones")
        void nullCoordinatesDifferFromPresent() {
            String withGps = baseline();

            String withoutGps = hashOf(signatureBytes, INVOICE_ID, CUSTOMER_ID, TOTAL,
                    CAPTURED_AT, null, null);

            assertThat(withoutGps).isNotEqualTo(withGps);
        }

        @Test
        @DisplayName("null coordinates still produce a stable, valid fingerprint")
        void nullCoordinatesStillHash() {
            String first  = hashOf(signatureBytes, INVOICE_ID, CUSTOMER_ID, TOTAL, CAPTURED_AT, null, null);
            String second = hashOf(signatureBytes, INVOICE_ID, CUSTOMER_ID, TOTAL, CAPTURED_AT, null, null);

            assertThat(first).hasSize(64).isEqualTo(second);
        }

        @Test
        @DisplayName("swapping latitude and longitude changes the fingerprint — field order matters")
        void swappedCoordinatesChangeHash() {
            String original = baseline();

            String swapped = hashOf(signatureBytes, INVOICE_ID, CUSTOMER_ID, TOTAL,
                    CAPTURED_AT, LON, LAT);

            assertThat(swapped).isNotEqualTo(original);
        }
    }

    @Nested
    @DisplayName("independence of the two artifacts")
    class ArtifactIndependence {

        @Test
        @DisplayName("signature and delivery photo on the same invoice hash differently")
        void twoArtifactsHaveDistinctHashes() {
            byte[] photoBytes = "a-delivery-photo-image".getBytes(StandardCharsets.UTF_8);

            String signatureHash = baseline();
            String photoHash = hashOf(photoBytes, INVOICE_ID, CUSTOMER_ID, TOTAL,
                    CAPTURED_AT, LAT, LON);

            // Each file carries its own fingerprint, so either can be verified on its own.
            assertThat(photoHash).isNotEqualTo(signatureHash);
        }
    }
}
