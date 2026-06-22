package com.example.dating.services.impl;


import com.example.dating.exceptions.SpotifyApiException;
import com.example.dating.exceptions.SpotifyTokenRevokedException;
import com.example.dating.models.auth.SpotifyTokenResponse;
import com.example.dating.models.user.domain.User;
import com.example.dating.services.JwtService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
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
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;


@Slf4j
@Service
@RequiredArgsConstructor
public class JwtServiceImpl implements JwtService {

    private final JwtDecoder jwtDecoder;
    /** Singleton RestTemplate with 5 s connect / 10 s read timeout — avoids unbounded thread blocking. */
    private final RestTemplate spotifyRestTemplate;

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

            // Common places to store user id: "sub", "userId", "id"
            Object userIdObj = claims.get("userId");
            if (userIdObj == null) userIdObj = claims.get("id");
            if (userIdObj == null) userIdObj = claims.get("sub");

            if (userIdObj == null) {
                log.warn("JWT does not contain user id claim (checked userId, id, sub)");
                return null;
            }

            log.debug("JWT decoded for userId: {}", userIdObj);
            return userIdObj.toString();

        } catch (JwtException ex) {
            // includes expired token, invalid signature, malformed token
            log.warn("Failed to decode/verify JWT: {}", ex.getMessage());
            return null; // or throw an exception mapped to 401/403 by your controller advice
        }
    }

    @Override
    @CircuitBreaker(name = "spotify-token", fallbackMethod = "refreshTokenFallback")
    public SpotifyTokenResponse refreshToken(String refreshToken) {
        int maxAttempts = 3;
        long[] backoffMs = {500, 1000, 2000};
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.info("Attempting to refresh Spotify token (attempt {}/{})", attempt, maxAttempts);

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
                headers.setBasicAuth(spotify_client_id, spotifyClientSecret);

                MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
                body.add("grant_type", "refresh_token");
                body.add("refresh_token", refreshToken.trim());

                HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

                ResponseEntity<SpotifyTokenResponse> response = spotifyRestTemplate.exchange(
                        SPOTIFY_TOKEN_URL,
                        HttpMethod.POST,
                        request,
                        SpotifyTokenResponse.class
                );

                log.debug("Spotify token refresh completed with status {}", response.getStatusCode());

                if (response.getBody() == null) {
                    log.error("Spotify returned null response body");
                    throw new SpotifyApiException("Spotify token refresh returned null body");
                }

                SpotifyTokenResponse tokenResponse = response.getBody();

                if (tokenResponse.getRefresh_token() == null || tokenResponse.getRefresh_token().isEmpty()) {
                    tokenResponse.setRefresh_token(refreshToken);
                    log.debug("No new refresh token provided, using existing one");
                }

                log.info("Successfully refreshed Spotify access token");
                return tokenResponse;

            } catch (HttpClientErrorException e) {
                int statusCode = e.getStatusCode().value();
                if (statusCode == 401 || statusCode == 403) {
                    // Token revoked or forbidden — non-retryable, user must reconnect Spotify
                    log.warn("Spotify token refresh rejected with status {}: non-retryable", statusCode);
                    throw new SpotifyTokenRevokedException("Spotify connection expired. Please reconnect.", e);
                }
                // Other 4xx (bad request, etc.) — non-retryable
                log.error("Spotify token refresh failed with client error {}", statusCode);
                throw new SpotifyApiException("Spotify token refresh failed: " + statusCode, e);

            } catch (HttpServerErrorException e) {
                // 5xx — transient, retryable
                log.warn("Spotify token refresh returned {} (attempt {}/{}), will retry",
                        e.getStatusCode().value(), attempt, maxAttempts);
                lastException = e;

            } catch (SpotifyApiException e) {
                // Already a typed exception (covers SpotifyTokenRevokedException too) — rethrow immediately
                throw e;

            } catch (Exception e) {
                // Network / connection timeout — retryable
                log.warn("Spotify token refresh network error (attempt {}/{}): {}", attempt, maxAttempts, e.getMessage());
                lastException = e;
            }

            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(backoffMs[attempt - 1]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new SpotifyApiException("Spotify token refresh interrupted", ie);
                }
            }
        }

        log.error("Spotify token refresh failed after {} attempts", maxAttempts);
        throw new SpotifyApiException("Spotify service temporarily unavailable", lastException);
    }

    private SpotifyTokenResponse refreshTokenFallback(String refreshToken, Throwable t) {
        log.warn("Spotify circuit open for token refresh: {}", t.getMessage());
        throw new SpotifyApiException("Spotify service temporarily unavailable — circuit open");
    }

    @Override
    public String generateToken(User user) {
        return generateToken(user, new HashMap<>());
    }

    @Override
    public String generateToken(User user, Map<String, Object> extraClaims) {
        Map<String, Object> claims = new HashMap<>(extraClaims);
        claims.put("userId", user.getId());
        claims.put("tokenVersion", user.getTokenVersion() != null ? user.getTokenVersion() : 0);

        return Jwts.builder()
                .setClaims(claims)
                .setSubject(user.getId())
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() +
                        TimeUnit.HOURS.toMillis(jwtExpirationHours)))
                .signWith(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), SignatureAlgorithm.HS256.getJcaName()))
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
