package com.salesmanagement.invoicing.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Input to {@code InvoiceFacade.createFromOfflineSync} — a completed offline sale
 * replayed by the {@code sync} module. A public api type so sync can build it without
 * touching the internal {@code Invoice} entity or the REST DTOs.
 *
 * <p><strong>This is create + submit collapsed.</strong> Offline, the rep both
 * created and submitted the invoice, so it arrives already SENT: stock was sold off
 * the van, the customer signed, prices were frozen. The facade replays that end
 * state atomically — it does not re-open a DRAFT. Contrast the online path, which is
 * two calls (createDraft, then submit).</p>
 *
 * <p><strong>Prices are trusted from the payload (Fork C).</strong> The unit prices
 * here are the ones frozen at the offline submit, which the customer's ePOD signature
 * attests to (the hash binds the total, D16). The facade does NOT re-capture the
 * current server price — "server price wins" (FR-95) applies only to pre-submit
 * drafts, never to a completed, signed sale. A drift between {@link Line#price} and
 * the current server price is recorded for reporting, not corrected.</p>
 *
 * <p><strong>invoiceDate is client-set (D15/Fork D).</strong> It is the business date
 * of the offline sale, not the sync date.</p>
 *
 * <p><strong>Visit reference is pre-resolved.</strong> {@link #visitId} is the server
 * id, already remapped by sync from the offline visit's {@code clientUuid} (Fork D).
 * The facade never sees a client uuid; it validates {@code visitId} the same way the
 * online path does. Null for an ad-hoc sale with no visit.</p>
 *
 * <p><strong>ePOD via tokens (Fork F).</strong> The signature and delivery photo were
 * uploaded to the existing staging endpoint on reconnect; {@link Artifact#fileToken}
 * references the staged bytes, exactly as the online submit does.</p>
 *
 * @param clientUuid  per-record idempotency key; maps to {@code invoices.client_uuid} (UK)
 * @param customerId  the billed customer
 * @param visitId     pre-resolved server visit id, or null for an ad-hoc sale
 * @param invoiceDate client-set business date of the sale
 * @param lines       the sold lines, with prices frozen offline
 * @param artifacts   the two mandatory ePOD artifacts (signature + delivery photo)
 */
public record OfflineInvoiceInput(
        String        clientUuid,
        Long          customerId,
        Long          visitId,
        LocalDate     invoiceDate,
        List<Line>    lines,
        List<Artifact> artifacts
) {

    /**
     * One sold line. {@code price} is the offline-frozen unit price (Fork C); the
     * facade recomputes the subtotal from these values and never trusts a client-sent
     * subtotal (D9).
     *
     * @param productId the product sold (validated to exist; may be discontinued, D12)
     * @param quantity  units sold (&gt; 0)
     * @param price     offline-frozen unit price
     * @param discount  line-level fixed discount, 0..quantity*price
     */
    public record Line(Long productId, int quantity, BigDecimal price, BigDecimal discount) {}

    /**
     * One ePOD artifact captured offline. {@code fileToken} points at bytes already
     * staged via the upload endpoint (Fork F); {@code capturedAt}/lat/lng feed the
     * binding hash exactly as the online submit (D16).
     *
     * @param type       SIGNATURE or DELIVERY_PHOTO (both mandatory)
     * @param fileToken  staging token for the uploaded image bytes
     * @param capturedAt device UTC instant the artifact was captured
     * @param latitude   capture latitude
     * @param longitude  capture longitude
     */
    public record Artifact(String type, String fileToken, Instant capturedAt,
                           BigDecimal latitude, BigDecimal longitude) {}
}
