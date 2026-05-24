package com.salesmanagement.shared.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * Unified JSON response envelope for every endpoint in the system.
 *
 * Every controller returns ApiResponse<T> — never a raw entity, never a raw DTO.
 * This gives the mobile app and the Vue dashboard a single contract to parse,
 * regardless of which module produced the response.
 *
 * Wire shape:
 * {
 *   "success": true,
 *   "message": "Invoice approved",
 *   "data":    { ... },       // null on error
 *   "errors":  null           // null on success; list of field/global errors on failure
 * }
 *
 * JsonInclude.NON_NULL keeps the wire format lean — no "data: null" noise on
 * error responses, no "errors: null" noise on success responses.
 *
 * Sealed hierarchy: only the static factory methods may produce instances.
 * Controllers never call new ApiResponse<>() directly — they call
 * ApiResponse.ok(data), ApiResponse.created(data), ApiResponse.error(msg), etc.
 * This enforces a consistent shape across 14 modules and ~60+ endpoints.
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ApiResponse<T> {

    private final boolean success;
    private final String  message;
    private final T       data;
    private final List<String> errors;

    // ── Success factories ────────────────────────────────────────────────────

    /**
     * 200 OK with a body.  Used for GET and PUT responses.
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, null, data, null);
    }

    /**
     * 200 OK with a body and an explicit message (e.g. "Route optimised successfully").
     */
    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(true, message, data, null);
    }

    /**
     * 201 Created. Carries the newly created resource.
     */
    public static <T> ApiResponse<T> created(T data) {
        return new ApiResponse<>(true, "Resource created successfully", data, null);
    }

    /**
     * 204 No Content equivalent — operation succeeded, nothing to return.
     * Used for DELETE, approve/reject actions that return no body.
     */
    public static <Void> ApiResponse<Void> noContent(String message) {
        return new ApiResponse<>(true, message, null, null);
    }

    // ── Error factories ──────────────────────────────────────────────────────

    /**
     * Single-message error (400, 403, 404, 409, 500).
     * GlobalExceptionHandler is the primary caller.
     */
    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, message, null, null);
    }

    /**
     * Validation error with a list of field-level messages.
     * Used by GlobalExceptionHandler when @Valid fails.
     * Example errors: ["name: must not be blank", "price: must be positive"]
     */
    public static <T> ApiResponse<T> validationError(List<String> errors) {
        return new ApiResponse<>(false, "Validation failed", null, errors);
    }
}