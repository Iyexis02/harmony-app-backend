package com.example.dating.exceptions;

/**
 * Thrown when a user tries to undo a swipe that does not exist
 * (no prior swipe row for the current user → target user). Maps to HTTP 404.
 */
public class SwipeNotFoundException extends RuntimeException {
    public SwipeNotFoundException(String message) {
        super(message);
    }
}
