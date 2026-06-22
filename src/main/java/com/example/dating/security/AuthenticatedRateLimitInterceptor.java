package com.example.dating.security;

import com.example.dating.exceptions.ErrorCode;
import com.example.dating.models.common.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;

/**
 * MVC interceptor that enforces per-user rate limits on authenticated matching endpoints.
 *
 * <p>This interceptor runs <em>after</em> Spring Security's filter chain, so the JWT
 * has already been validated and the {@link Authentication} is available from the
 * {@link SecurityContextHolder}. Rate-limit buckets are keyed by {@code userId} (not IP),
 * which means the limits are per-account and cannot be bypassed by IP rotation.
 *
 * <p>Limits:
 * <ul>
 *   <li>POST /api/v1/matching/swipe → 60 requests / minute</li>
 *   <li>GET  /api/v1/matching/score/{id} → 30 requests / minute</li>
 * </ul>
 *
 * <p>Exceeding the limit returns HTTP 429 with a {@code Retry-After} header (seconds).
 */
@Component
public class AuthenticatedRateLimitInterceptor implements HandlerInterceptor {

    private static final int      SWIPE_REQUESTS_PER_MINUTE = 60;
    private static final int      SCORE_REQUESTS_PER_MINUTE = 30;
    private static final Duration ONE_MINUTE                 = Duration.ofMinutes(1);

    private static final String SWIPE_URI    = "/api/v1/matching/swipe";
    private static final String SCORE_PREFIX = "/api/v1/matching/score/";

    private final RateLimiter rateLimiter;

    /**
     * Spring-managed ObjectMapper (JavaTimeModule registered, timestamps disabled) so the
     * 429 body serializes {@code Instant} as ISO-8601 — matching the shape
     * {@link com.example.dating.exceptions.GlobalExceptionHandler} produces for every other error.
     */
    private final ObjectMapper objectMapper;

    public AuthenticatedRateLimitInterceptor(RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        String method = request.getMethod().toUpperCase();
        String uri    = request.getRequestURI();

        int capacity = resolveCapacity(method, uri);
        if (capacity == 0) {
            return true; // endpoint not subject to user-level rate limiting
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            // Not JWT-authenticated — Spring Security will reject the request downstream.
            return true;
        }

        String userId = jwt.getClaimAsString("userId");
        if (userId == null) {
            return true;
        }

        String bucketKey = method + ":" + normalizeUri(method, uri) + ":" + userId;
        RateLimiter.ConsumeResult result = rateLimiter.tryConsume(bucketKey, capacity, ONE_MINUTE);

        if (result.consumed()) {
            return true;
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(result.retryAfterSeconds()));
        response.setContentType("application/json;charset=UTF-8");
        // Standard ErrorResponse shape ({code, message, fields, timestamp}) — interceptor
        // short-circuits before the controller, so GlobalExceptionHandler never runs.
        ErrorResponse body = ErrorResponse.of(
                ErrorCode.RATE_LIMITED, "Too many requests. Please try again later.");
        response.getWriter().write(objectMapper.writeValueAsString(body));
        return false;
    }

    /**
     * Returns the per-minute capacity for the given method + URI, or 0 if the
     * endpoint is not subject to user-level rate limiting.
     */
    private int resolveCapacity(String method, String uri) {
        if ("POST".equals(method) && SWIPE_URI.equals(uri)) {
            return SWIPE_REQUESTS_PER_MINUTE;
        }
        if ("GET".equals(method) && uri.startsWith(SCORE_PREFIX)) {
            return SCORE_REQUESTS_PER_MINUTE;
        }
        return 0;
    }

    /**
     * Normalises path-variable URIs to a canonical bucket-key prefix so that
     * all {@code /api/v1/matching/score/{id}} requests for a given user share
     * the same bucket.
     */
    private String normalizeUri(String method, String uri) {
        if ("GET".equals(method) && uri.startsWith(SCORE_PREFIX)) {
            return "/api/v1/matching/score";
        }
        return uri;
    }
}
