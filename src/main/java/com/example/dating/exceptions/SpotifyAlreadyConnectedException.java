package com.example.dating.exceptions;

public class SpotifyAlreadyConnectedException extends RuntimeException {
    public SpotifyAlreadyConnectedException(String message) {
        super(message);
    }
}
