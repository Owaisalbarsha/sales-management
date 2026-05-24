package com.salesmanagement.shared.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base class for every domain/business exception in the system.
 *
 * Design decisions:
 *
 * 1. Carries HttpStatus — the GlobalExceptionHandler reads this to set the HTTP
 *    response code. Business logic never knows about HTTP; it just throws a
 *    meaningful exception. The transport layer (handler) does the mapping.
 *
 * 2. Carries an errorCode string — a stable machine-readable code the mobile
 *    app can switch on to show localised messages without parsing the English
 *    message string. Example: "INSUFFICIENT_STOCK", "INVOICE_ALREADY_APPROVED".
 *
 * 3. Unchecked (extends RuntimeException) — Spring's transaction rollback
 *    works automatically on RuntimeException. Checked exceptions require
 *    explicit rollbackFor = ... on every @Transactional and create noise.
 *
 * Module-specific subclasses live in their own module's exception package,
 * e.g. inventory's InsufficientStockException extends BusinessException.
 * This keeps shared lean while still giving the global handler a single catch.
 *
 * Example usage:
 *   throw new BusinessException("Invoice is already approved",
 *                               "INVOICE_ALREADY_APPROVED",
 *                               HttpStatus.CONFLICT);
 */
@Getter
public class BusinessException extends RuntimeException {

    private final String     errorCode;
    private final HttpStatus status;

    public BusinessException(String message, String errorCode, HttpStatus status) {
        super(message);
        this.errorCode = errorCode;
        this.status    = status;
    }

    // ── Convenience constructors for the most common status codes ────────────

    /** 400 Bad Request — invalid input that passed bean validation but fails domain rules. */
    public static BusinessException badRequest(String message, String errorCode) {
        return new BusinessException(message, errorCode, HttpStatus.BAD_REQUEST);
    }

    /** 403 Forbidden — authenticated but not authorised for this specific resource. */
    public static BusinessException forbidden(String message, String errorCode) {
        return new BusinessException(message, errorCode, HttpStatus.FORBIDDEN);
    }

    /** 404 Not Found — entity does not exist or is not visible to this user. */
    public static BusinessException notFound(String message, String errorCode) {
        return new BusinessException(message, errorCode, HttpStatus.NOT_FOUND);
    }

    /** 409 Conflict — state transition is illegal given current entity state. */
    public static BusinessException conflict(String message, String errorCode) {
        return new BusinessException(message, errorCode, HttpStatus.CONFLICT);
    }

    /** 422 Unprocessable Entity — domain invariant violated (e.g. insufficient stock). */
    public static BusinessException unprocessable(String message, String errorCode) {
        return new BusinessException(message, errorCode, HttpStatus.UNPROCESSABLE_ENTITY);
    }
}