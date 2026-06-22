package com.example.dating.exceptions;

public class DuplicateSwipeException extends RuntimeException {
    public DuplicateSwipeException(String swiperId, String swipedId) {
        super("User " + swiperId + " has already swiped on user " + swipedId);
    }
}
