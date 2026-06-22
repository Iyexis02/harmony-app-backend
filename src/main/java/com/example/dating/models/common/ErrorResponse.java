package com.example.dating.models.common;

import java.time.Instant;
import java.util.Map;

/**
 * Standardized error response body returned by GlobalExceptionHandler for every error.
 *
 * <pre>
 * {
 *   "code":      "DUPLICATE_SWIPE",           // machine-readable discriminator
 *   "message":   "You have already swiped...", // human-readable message
 *   "fields":    { "email": "must not be blank" }, // only for VALIDATION_ERROR, null otherwise
 *   "timestamp": "2026-03-19T10:15:30.123Z"   // when the error occurred (ISO 8601)
 * }
 * </pre>
 */
public record ErrorResponse(
        String code,
        String message,
        Map<String, String> fields,
        Instant timestamp) {

    /** Factory for regular (non-validation) errors. {@code fields} is null. */
    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, null, Instant.now());
    }

    /** Factory for validation errors. {@code fields} contains per-field messages. */
    public static ErrorResponse ofValidation(String message, Map<String, String> fields) {
        return new ErrorResponse("VALIDATION_ERROR", message, fields, Instant.now());
    }
}
