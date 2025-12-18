package com.example.dating.services.impl;


import com.example.dating.models.auth.SpotifyTokenResponse;
import com.example.dating.models.user.domain.User;
import com.example.dating.services.JwtService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;


@Slf4j
@Service
@RequiredArgsConstructor
public class JwtServiceImpl implements JwtService {

    private final JwtDecoder jwtDecoder;

    @Value("${jwt.secret.key}")
    private String secret;

    @Value("${jwt.expiration.hours:24}")
    private int jwtExpirationHours;

    @Value("${spotify.client.id}")
    private String spotify_client_id;

    @Value("${spotify.client.secret}")
    private String spotifyClientSecret;

    private static final String SPOTIFY_TOKEN_URL = "https://accounts.spotify.com/api/token";

    /**
     * Decodes and verifies the JWT. Returns null if token is invalid.
     * Expects either a raw token or a Bearer token string ("Bearer ...").
     */
    @Override
    public String getUserIdFromToken(String jwt) throws JwtException {
        try {
            Jwt decoded = jwtDecoder.decode(jwt);
            Map<String, Object> claims = decoded.getClaims();

            log.info(claims.toString());

            // Common places to store user id: "sub", "userId", "id"
            Object userIdObj = claims.get("userId");
            if (userIdObj == null) userIdObj = claims.get("id");
            if (userIdObj == null) userIdObj = claims.get("sub");

            if (userIdObj == null) {
                log.warn("JWT does not contain user id claim (checked userId, id, sub)");
                return null; // or throw an application-specific exception
            }


            // adapt to your DTO id type: if UUID:

            // try parse as 3UUID, otherwise set as string

            return userIdObj.toString();

        } catch (JwtException ex) {
            // includes expired token, invalid signature, malformed token
            log.warn("Failed to decode/verify JWT: {}", ex.getMessage());
            return null; // or throw an exception mapped to 401/403 by your controller advice
        }
    }

    @Override
    public SpotifyTokenResponse refreshToken(String refreshToken) {
        try {
            log.info("Attempting to refresh Spotify token");
            log.debug("Using client ID: {}", spotify_client_id);
            log.debug("Refresh token (first 10 chars): {}",
                    refreshToken != null && refreshToken.length() > 10
                            ? refreshToken.substring(0, 10) + "..."
                            : "null or too short");
            log.debug("Token URL: {}", SPOTIFY_TOKEN_URL);

            RestTemplate restTemplate = new RestTemplate();

            // Set up headers with Basic Authentication
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            // Log the auth header to verify it's correct (be careful in production!)
            String authHeader = "Basic " + Base64.getEncoder()
                    .encodeToString((spotify_client_id + ":" + spotifyClientSecret).getBytes());
            log.debug("Auth header starts with: {}", authHeader.substring(0, 20) + "...");

            headers.setBasicAuth(spotify_client_id, spotifyClientSecret);

            // Set up request body
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "refresh_token");
            body.add("refresh_token", refreshToken.trim()); // Trim whitespace

            // Log request details
            log.debug("Request body: grant_type=refresh_token, refresh_token={}",
                    refreshToken.substring(0, Math.min(10, refreshToken.length())) + "...");

            // Create HTTP entity
            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

            // Make POST request
            ResponseEntity<SpotifyTokenResponse> response = restTemplate.exchange(
                    SPOTIFY_TOKEN_URL,
                    HttpMethod.POST,
                    request,
                    SpotifyTokenResponse.class
            );

            log.debug("Response status: {}", response.getStatusCode());
            log.debug("Response body: {}", response.getBody());

            // Validate response
            if (response.getBody() == null) {
                log.error("Spotify returned null response body");
                throw new RuntimeException("Spotify token refresh returned null body");
            }

            SpotifyTokenResponse tokenResponse = response.getBody();

            // Keep existing refresh token if not provided
            if (tokenResponse.getRefresh_token() == null || tokenResponse.getRefresh_token().isEmpty()) {
                tokenResponse.setRefresh_token(refreshToken);
                log.debug("No new refresh token provided, using existing one");
            }

            log.info("Successfully refreshed Spotify access token");
            return tokenResponse;

        } catch (HttpClientErrorException e) {
            log.error("HTTP error while refreshing Spotify token");
            log.error("Status code: {}", e.getStatusCode());
            log.error("Response body: {}", e.getResponseBodyAsString());
            log.error("Request headers would have been: Content-Type: application/x-www-form-urlencoded, Authorization: Basic [credentials]");
            throw new RuntimeException("Failed to refresh Spotify token: " + e.getStatusCode(), e);
        } catch (Exception e) {
            log.error("Unexpected error refreshing Spotify token: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to refresh Spotify token", e);
        }
    }

    @Override
    public String generateToken(User user) {
        return generateToken(user, new HashMap<>());
    }

    @Override
    public String generateToken(User user, Map<String, Object> extraClaims) {
        Map<String, Object> claims = new HashMap<>(extraClaims);
        claims.put("userId", user.getId());
        claims.put("email", user.getEmail());
        claims.put("authProvider", user.getAuthProvider().toString());
        claims.put("emailVerified", user.getEmailVerified());

        return Jwts.builder()
                .setClaims(claims)
                .setSubject(user.getId())
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() +
                        TimeUnit.HOURS.toMillis(jwtExpirationHours)))
                .signWith(new SecretKeySpec(secret.getBytes(), SignatureAlgorithm.HS256.getJcaName()))
                .compact();
    }

    @Override
    public boolean validateToken(String token, String userId) {
        try {
            String extractedUserId = getUserIdFromToken(token);
            return extractedUserId != null && extractedUserId.equals(userId);
        } catch (JwtException e) {
            log.warn("Token validation failed: {}", e.getMessage());
            return false;
        }
    }
}
