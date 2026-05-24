package com.salesmanagement.shared.exception;

import com.salesmanagement.shared.api.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.ArrayList;
import java.util.List;

/**
 * Single exception handler for the entire application.
 *
 * Lives in shared because it must handle exceptions from all 13 business modules.
 * It is the ONLY place that converts exceptions to HTTP responses. Module services
 * throw; this handler catches. No controller catches anything itself.
 *
 * Catch order (most specific to most general):
 *   1. MethodArgumentNotValidException   - @Valid bean validation failures
 *   2. BusinessException                 - all domain rule violations
 *   3. AccessDeniedException             - Spring Security 403 (role mismatch)
 *   4. AuthenticationException           - Spring Security 401 (bad/missing token)
 *   5. MethodArgumentTypeMismatchException - path/query param type errors
 *   6. Exception                         - safety net for unexpected errors
 *
 * Logging policy:
 *   - BusinessException: WARN level — expected failures, not bugs.
 *   - Everything else:   ERROR level — needs investigation.
 *   - Stack traces logged at ERROR but NEVER sent to clients (no internal leakage).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ── 1. Bean validation failures (@Valid on request DTOs) ─────────────────
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(
            MethodArgumentNotValidException ex) {

        List<String> errors = new ArrayList<>();

        ex.getBindingResult().getFieldErrors().forEach(fe ->
                errors.add(fe.getField() + ": " + fe.getDefaultMessage()));

        ex.getBindingResult().getGlobalErrors().forEach(ge ->
                errors.add(ge.getObjectName() + ": " + ge.getDefaultMessage()));

        log.warn("Validation failed: {}", errors);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.validationError(errors));
    }

    // ── 2. Domain / business rule violations ─────────────────────────────────
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        log.warn("Business rule violation [{}]: {}", ex.getErrorCode(), ex.getMessage());
        return ResponseEntity
                .status(ex.getStatus())
                .body(ApiResponse.error(ex.getMessage()));
    }

    // ── 3. Spring Security — insufficient role ────────────────────────────────
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("You do not have permission to perform this action"));
    }

    // ── 4. Spring Security — invalid / missing token ──────────────────────────
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException ex) {
        log.warn("Authentication failed: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("Authentication required"));
    }

    // ── 5. Path / query param type mismatch (e.g. "abc" where Long expected) ─
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex) {
        String msg = String.format("Parameter '%s' has invalid value: '%s'",
                ex.getName(), ex.getValue());
        log.warn(msg);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(msg));
    }

    // ── 6. Safety net — unexpected runtime errors ─────────────────────────────
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("An unexpected error occurred. Please try again later."));
    }
}