package com.example.dating.exceptions;

public class SpotifyTokenRevokedException extends SpotifyApiException {
    public SpotifyTokenRevokedException(String message) {
        super(message);
    }

    public SpotifyTokenRevokedException(String message, Throwable cause) {
        super(message, cause);
    }
}
