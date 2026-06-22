package com.example.dating.exceptions;

/**
 * Thrown when user input is syntactically valid but semantically rejected.
 * The message is intended for the client and is echoed in the 400 response.
 *
 * <p>Use this for legitimate user-facing input errors (e.g. "Cannot calculate
 * score with yourself"). For programming-error guards or internal invariants,
 * throw {@link IllegalArgumentException} or {@link IllegalStateException} —
 * those are mapped to generic responses without echoing the message.
 */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
