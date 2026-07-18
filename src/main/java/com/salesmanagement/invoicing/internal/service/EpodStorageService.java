package com.salesmanagement.invoicing.internal.service;

import com.salesmanagement.invoicing.internal.enums.EpodArtifactType;
import com.salesmanagement.shared.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Disk-backed storage for ePOD artifact files, and the tamper-detection hashing that binds a
 * file to its invoice (decision D16).
 *
 * <p><strong>The upload seam.</strong> The mobile app uploads a file in a prior step and gets an
 * opaque {@code fileToken}; at submit it sends that token, not the bytes. This service resolves
 * the token to the uploaded bytes, moves them to a write-once final path keyed by invoice + type,
 * and returns the stored location. Here the token is treated as the filename of a staged upload
 * under {@code epod.upload-dir}; the final files live under {@code epod.storage-dir}. Swapping
 * this class for S3/blob storage later touches only this file — the service and entities are
 * unaware of where bytes live.</p>
 *
 * <p><strong>Hashing.</strong> {@link # hash(byte[], Long, Long, BigDecimal)} computes
 * {@code SHA-256(invoiceId | customerId | total | fileBytes)} and returns lowercase hex. Because
 * the invoice's identity data is folded in, the same image on a different invoice produces a
 * different hash — so a signature cannot be lifted onto another invoice undetected, and re-hashing
 * the stored file later verifies it is the original.</p>
 *
 * <p>This service performs file I/O and is intentionally <em>not</em> transactional: filesystem
 * writes do not participate in the DB transaction. The calling service computes and stores the
 * hash + url inside its transaction; a failure after a file write leaves an orphaned file (a
 * cleanup sweep, out of MVP scope, would reconcile). The write is last-in so this window is small.</p>
 */
@Slf4j
@Service
public class EpodStorageService {

    private final Path uploadDir;
    private final Path storageDir;

    public EpodStorageService(
            @Value("${epod.upload-dir:./epod-uploads}") String uploadDir,
            @Value("${epod.storage-dir:./epod-storage}") String storageDir) {
        this.uploadDir  = Path.of(uploadDir);
        this.storageDir = Path.of(storageDir);
    }

    /**
     * Reads the staged upload for a token. Isolated so the service can hash the bytes and store
     * them in one place.
     *
     * @throws BusinessException 400 if the token resolves to no staged file
     */
    public byte[] readStaged(String fileToken) {
        Path staged = resolveStaged(fileToken);
        if (!Files.isRegularFile(staged)) {
            throw BusinessException.badRequest(
                    "No uploaded file for token: " + fileToken, "EPOD_UPLOAD_NOT_FOUND");
        }
        try {
            return Files.readAllBytes(staged);
        } catch (IOException e) {
            log.error("Failed to read staged ePOD upload token={}", fileToken, e);
            throw BusinessException.unprocessable(
                    "Could not read uploaded file", "EPOD_UPLOAD_READ_FAILED");
        }
    }

    /**
     * Computes the tamper-detection hash binding these bytes to this invoice <em>and</em> to the
     * capture metadata. Lowercase hex SHA-256 over
     * {@code invoiceId | customerId | total | capturedAt | latitude | longitude | bytes}.
     *
     * <p>Folding the metadata in (not just the invoice identity) means the fingerprint breaks if
     * anyone edits <em>where</em> or <em>when</em> the proof was captured — not only if they swap
     * the image or move it to another invoice. The whole proof bundle is protected, not just the
     * file. Null coordinates are encoded as the literal {@code "null"} so a present-vs-absent
     * coordinate produces a different hash.</p>
     */
    public String hash(byte[] fileBytes,
                       Long invoiceId,
                       Long customerId,
                       BigDecimal total,
                       Instant capturedAt,
                       BigDecimal latitude,
                       BigDecimal longitude) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // Identity + metadata prefix — pipe-delimited so field boundaries are unambiguous.
            String identity = invoiceId + "|"
                    + customerId + "|"
                    + total.toPlainString() + "|"
                    + capturedAt.toString() + "|"
                    + plain(latitude) + "|"
                    + plain(longitude) + "|";
            digest.update(identity.getBytes(StandardCharsets.UTF_8));
            digest.update(fileBytes);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every JVM; this is unreachable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String plain(BigDecimal v) {
        return v == null ? "null" : v.toPlainString();
    }

    /**
     * Stages an uploaded file and returns the opaque {@code fileToken} the client sends back at
     * submit. The token is a random filename under {@code epod.upload-dir} — it carries no
     * meaning and cannot be guessed from the invoice.
     *
     * @param bytes             the uploaded file content
     * @param originalFilename  used only to preserve the extension; may be {@code null}
     * @return the file token to be echoed in the submit request
     * @throws BusinessException 422 if the file cannot be staged
     */
    public String stage(byte[] bytes, String originalFilename) {
        String token = UUID.randomUUID() + extensionOf(originalFilename == null ? "" : originalFilename);
        try {
            Files.createDirectories(uploadDir);
            Files.write(uploadDir.resolve(token), bytes);
            return token;
        } catch (IOException e) {
            log.error("Failed to stage ePOD upload filename={}", originalFilename, e);
            throw BusinessException.unprocessable(
                    "Could not stage uploaded file", "EPOD_UPLOAD_FAILED");
        }
    }

    /**
     * Moves the staged upload to its write-once final path and returns the stored location as a
     * string to persist as the artifact {@code url}. Path is keyed by invoice id and artifact
     * type, so it is deterministic and never reused.
     *
     * @return the final stored path (used as the artifact url)
     * @throws BusinessException 422 if the file cannot be stored
     */
    public String store(String fileToken, Long invoiceId, EpodArtifactType type) {
        Path staged = resolveStaged(fileToken);
        String filename = "invoice_" + invoiceId + "_" + type.name().toLowerCase() + extensionOf(fileToken);
        Path target = storageDir.resolve(filename);
        try {
            Files.createDirectories(storageDir);
            // Write-once: fail rather than overwrite an existing proof file.
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
            return target.toString();
        } catch (IOException e) {
            log.error("Failed to store ePOD file token={} invoiceId={} type={}",
                    fileToken, invoiceId, type, e);
            throw BusinessException.unprocessable(
                    "Could not store proof-of-delivery file", "EPOD_STORE_FAILED");
        }
    }

    private Path resolveStaged(String fileToken) {
        // Guard against path traversal — a token must be a bare filename.
        String safe = Path.of(fileToken).getFileName().toString();
        if (!safe.equals(fileToken)) {
            throw BusinessException.badRequest("Invalid file token", "EPOD_TOKEN_INVALID");
        }
        return uploadDir.resolve(safe);
    }

    private static String extensionOf(String fileToken) {
        int dot = fileToken.lastIndexOf('.');
        return dot >= 0 ? fileToken.substring(dot) : "";
    }
}
