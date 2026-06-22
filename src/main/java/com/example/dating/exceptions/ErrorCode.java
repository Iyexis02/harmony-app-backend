package com.example.dating.exceptions;

/**
 * Machine-readable error code constants included in every {@link com.example.dating.models.common.ErrorResponse}.
 * Frontend code should switch on these strings, never on the human-readable {@code message} field.
 */
public final class ErrorCode {

    private ErrorCode() {}

    // ── Input errors ─────────────────────────────────────────────────────────
    public static final String VALIDATION_ERROR       = "VALIDATION_ERROR";
    public static final String INVALID_ARGUMENT       = "INVALID_ARGUMENT";
    public static final String INVALID_TOKEN          = "INVALID_TOKEN";

    // ── Auth / access errors ─────────────────────────────────────────────────
    public static final String UNAUTHORIZED           = "UNAUTHORIZED";
    public static final String FORBIDDEN              = "FORBIDDEN";
    public static final String ACCOUNT_LOCKED         = "ACCOUNT_LOCKED";
    public static final String EMAIL_VERIFICATION_REQUIRED = "EMAIL_VERIFICATION_REQUIRED";
    public static final String RATE_LIMITED           = "RATE_LIMITED";

    // ── Resource errors ──────────────────────────────────────────────────────
    public static final String NOT_FOUND              = "NOT_FOUND";
    public static final String EMAIL_EXISTS           = "EMAIL_EXISTS";
    public static final String CONFLICT               = "CONFLICT";
    public static final String DUPLICATE_SWIPE        = "DUPLICATE_SWIPE";
    public static final String CONCURRENT_MODIFICATION = "CONCURRENT_MODIFICATION";

    // ── External service errors ───────────────────────────────────────────────
    public static final String SPOTIFY_TOKEN_EXPIRED  = "SPOTIFY_TOKEN_EXPIRED";
    public static final String SPOTIFY_UNAVAILABLE    = "SPOTIFY_UNAVAILABLE";

    // ── Catch-all ────────────────────────────────────────────────────────────
    public static final String INTERNAL_ERROR         = "INTERNAL_ERROR";
}
