package com.salesmanagement.sync.internal.dto.payload;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The JSON shape of an INVOICE sync item's payload — a completed offline sale.
 *
 * <p><strong>Visit reference (Fork D).</strong> Exactly one of {@code visitId} /
 * {@code visitClientUuid} is expected, or neither (ad-hoc sale). {@code visitId} is set
 * when the visit was online or already synced; {@code visitClientUuid} when the visit was
 * created offline in (ideally) the same batch. Sync resolves {@code visitClientUuid} to a
 * server id via the ledger before calling invoicing; if it cannot resolve (the visit
 * failed to sync), the link degrades to null — the sale still records (the goods are gone),
 * consistent with the "record completed sales" stance (Fork B/C).</p>
 *
 * <p><strong>Prices are the offline-frozen ones (Fork C)</strong> — carried per line and
 * trusted, because the customer's signed ePOD attests to the total.</p>
 *
 * @param customerId      the billed customer
 * @param visitId         pre-known server visit id, or null
 * @param visitClientUuid clientUuid of an offline visit to remap, or null
 * @param invoiceDate     client-set business date of the sale (D15)
 * @param lines           sold lines with frozen prices
 * @param artifacts       the two mandatory ePOD artifacts (staged tokens, Fork F)
 */
public record InvoicePayload(
        Long           customerId,
        Long           visitId,
        String         visitClientUuid,
        LocalDate      invoiceDate,
        List<Line>     lines,
        List<Artifact> artifacts
) {
    public record Line(Long productId, int quantity, BigDecimal price, BigDecimal discount) {}

    public record Artifact(String type, String fileToken, Instant capturedAt,
                           BigDecimal latitude, BigDecimal longitude) {}
}
