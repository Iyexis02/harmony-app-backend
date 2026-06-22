package com.example.dating.exceptions;

/**
 * Thrown when a swipe exists but cannot be undone — e.g. it already resulted in a match.
 * Maps to HTTP 409 Conflict.
 */
public class SwipeUndoNotAllowedException extends RuntimeException {
    public SwipeUndoNotAllowedException(String message) {
        super(message);
    }
}
