package com.example.dating.exceptions;

public class UnauthorizedMatchAccessException extends RuntimeException {
    public UnauthorizedMatchAccessException(String message) {
        super(message);
    }
}
