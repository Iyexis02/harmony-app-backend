package com.example.dating.services;

import com.example.dating.models.auth.SpotifyTokenResponse;
import com.example.dating.models.user.domain.User;

import java.util.Map;

public interface JwtService {

    String getUserIdFromToken(String jwt);
    SpotifyTokenResponse refreshToken(String jwt);

    // JWT Generation methods
    String generateToken(User user);
    String generateToken(User user, Map<String, Object> extraClaims);
    boolean validateToken(String token, String userId);
}
