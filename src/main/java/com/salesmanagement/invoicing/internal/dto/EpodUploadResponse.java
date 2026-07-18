package com.salesmanagement.invoicing.internal.dto;

/**
 * Response of the ePOD file upload step: the opaque handle the client echoes back in
 * {@link SubmitInvoiceRequest} instead of re-sending the bytes.
 *
 * <p>The token is a random identifier with no relationship to any invoice — a file is only bound
 * to an invoice at submit, when the server hashes it together with that invoice's data.</p>
 *
 * @param fileToken  the handle to send at submit
 * @param sizeBytes  the stored size, echoed so the client can verify the upload landed intact
 */
public record EpodUploadResponse(
        String fileToken,
        long   sizeBytes
) {}
