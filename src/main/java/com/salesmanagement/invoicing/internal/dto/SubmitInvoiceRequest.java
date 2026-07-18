package com.salesmanagement.invoicing.internal.dto;

import com.salesmanagement.invoicing.internal.enums.EpodArtifactType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Request body for submitting a DRAFT invoice (DRAFT → SENT, decision D1/D16).
 *
 * <p>Carries the Electronic Proof of Delivery artifacts captured in the field. Both mandatory
 * types — {@code SIGNATURE} and {@code DELIVERY_PHOTO} — must be present; the service verifies
 * the set is complete before deducting stock and flipping the status. Submit is where stock is
 * deducted atomically and the invoice becomes immutable.</p>
 *
 * <p><strong>File transport note:</strong> each artifact references already-uploaded file bytes
 * by {@code fileToken} (an opaque handle from a prior upload step) rather than inlining the
 * bytes in this JSON. The service resolves the token to the stored file, computes the
 * tamper-detection hash over (invoice identity + bytes), and records the final {@code url}.
 * This keeps the submit payload small and lets the same upload flow serve online and sync.
 * The hash is never accepted from the client (D16).</p>
 *
 * @param artifacts the ePOD artifacts; must include both mandatory types
 */
public record SubmitInvoiceRequest(

        @NotEmpty(message = "ePOD artifacts are required")
        @Valid
        List<Artifact> artifacts
) {
    /**
     * One ePOD artifact captured on the device.
     *
     * @param type       SIGNATURE or DELIVERY_PHOTO; required
     * @param fileToken  opaque handle to the already-uploaded file bytes; required
     * @param latitude   capture latitude; optional
     * @param longitude  capture longitude; optional
     * @param capturedAt UTC instant of capture (ISO 8601 with offset on sync); required
     */
    public record Artifact(

            @NotNull(message = "artifact type is required")
            EpodArtifactType type,

            @NotNull(message = "fileToken is required")
            @Size(min = 1, message = "fileToken must not be blank")
            String fileToken,

            BigDecimal latitude,

            BigDecimal longitude,

            @NotNull(message = "capturedAt is required")
            Instant capturedAt
    ) {}
}
