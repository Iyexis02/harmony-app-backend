package com.example.dating.security;

import com.example.dating.exceptions.ErrorCode;
import com.example.dating.models.common.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/**
 * Servlet filter that applies per-IP rate limits to sensitive auth endpoints
 * and public Spotify search endpoints.
 *
 * Registered automatically by Spring Boot as a servlet filter (runs before
 * Spring Security's filter chain). Requests that exceed the limit receive
 * HTTP 429 with a Retry-After header.
 *
 * POST limits (auth endpoints, keyed by exact URI):
 *   POST /api/v1/auth/login               → 10 attempts / 15 min
 *   POST /api/v1/auth/register            →  5 attempts / 60 min
 *   POST /api/v1/auth/forgot-password     →  3 attempts / 60 min
 *   POST /api/v1/auth/reset-password      →  5 attempts / 15 min
 *   POST /api/v1/auth/resend-verification →  3 attempts / 60 min
 *
 * GET limits (public Spotify endpoints):
 *   GET /api/v1/spotify/search/artists    → 30 requests / min
 *   GET /api/v1/spotify/search/tracks     → 30 requests / min
 *   GET /api/v1/spotify/genres            → 60 requests / min
 *   GET /api/v1/spotify/artists/{id}      → 60 requests / min  (prefix-matched)
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    /** Pairs of (capacity, window) for POST auth endpoints — matched by exact URI. */
    private record RateLimit(int capacity, Duration window) {}

    private static final Map<String, RateLimit> POST_LIMITS = Map.of(
            "/api/v1/auth/login",
                new RateLimit(10, Duration.ofMinutes(15)),
            "/api/v1/auth/register",
                new RateLimit(5,  Duration.ofHours(1)),
            "/api/v1/auth/forgot-password",
                new RateLimit(3,  Duration.ofHours(1)),
            "/api/v1/auth/reset-password",
                new RateLimit(5,  Duration.ofMinutes(15)),
            "/api/v1/auth/resend-verification",
                new RateLimit(3,  Duration.ofHours(1))
    );

    private static final Map<String, RateLimit> GET_LIMITS = Map.of(
            "/api/v1/spotify/search/artists",
                new RateLimit(30, Duration.ofMinutes(1)),
            "/api/v1/spotify/search/tracks",
                new RateLimit(30, Duration.ofMinutes(1)),
            "/api/v1/spotify/genres",
                new RateLimit(60, Duration.ofMinutes(1)),
            "/api/v1/spotify/artists",        // prefix: matches /api/v1/spotify/artists/{id}
                new RateLimit(60, Duration.ofMinutes(1))
    );

    /** URI prefix used to identify artist-detail requests for bucket normalisation. */
    private static final String ARTIST_DETAIL_PREFIX = "/api/v1/spotify/artists/";

    private final RateLimiter rateLimiter;

    /**
     * Spring-managed ObjectMapper (JavaTimeModule registered, timestamps disabled) so the
     * 429 body serializes {@code Instant} as ISO-8601 — matching the shape
     * {@link com.example.dating.exceptions.GlobalExceptionHandler} produces for every other error.
     */
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String method = request.getMethod().toUpperCase();
        String uri    = request.getRequestURI();

        RateLimit rateLimit = resolveLimitSpec(method, uri);

        // URI/method combination not subject to rate limiting — pass through.
        if (rateLimit == null) {
            chain.doFilter(request, response);
            return;
        }

        String ip = resolveClientIp(request);
        // Normalise the URI for path-variable endpoints so all requests to
        // /api/v1/spotify/artists/* share the same per-IP bucket.
        String normalizedUri = normalizeUri(method, uri);
        String bucketKey = method + ":" + normalizedUri + ":" + ip;

        RateLimiter.ConsumeResult result =
                rateLimiter.tryConsume(bucketKey, rateLimit.capacity(), rateLimit.window());

        if (result.consumed()) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(result.retryAfterSeconds()));
            response.setContentType("application/json;charset=UTF-8");
            // Standard ErrorResponse shape ({code, message, fields, timestamp}) — as a
            // servlet filter this bypasses GlobalExceptionHandler, so the body is built here.
            ErrorResponse body = ErrorResponse.of(
                    ErrorCode.RATE_LIMITED, "Too many requests. Please try again later.");
            response.getWriter().write(objectMapper.writeValueAsString(body));
        }
    }

    /**
     * Resolves the applicable RateLimit for this method + URI combination.
     * Returns null if the endpoint is not rate-limited.
     */
    private RateLimit resolveLimitSpec(String method, String uri) {
        if ("POST".equals(method)) {
            return POST_LIMITS.get(uri);
        }
        if ("GET".equals(method)) {
            return GET_LIMITS.get(normalizeUri(method, uri));
        }
        return null;
    }

    /**
     * Normalises URIs with path variables to their canonical map key.
     * e.g. /api/v1/spotify/artists/3TVXtAsR1... → /api/v1/spotify/artists
     */
    private String normalizeUri(String method, String uri) {
        if ("GET".equals(method) && uri.startsWith(ARTIST_DETAIL_PREFIX)) {
            return "/api/v1/spotify/artists";
        }
        return uri;
    }

    /**
     * Returns the originating client IP from {@code request.getRemoteAddr()}.
     *
     * <p>We deliberately do NOT read {@code X-Forwarded-For} here. That header is
     * attacker-controlled in the absence of a trusted reverse proxy and would allow
     * rate-limit bypass by rotating spoofed IPs.
     *
     * <p>When the application is deployed behind a trusted proxy (nginx, ALB, …), add
     * {@code server.forward-headers-strategy: framework} in {@code application.yml}
     * (already present). Spring Boot's {@code ForwardedHeaderFilter} will then rewrite
     * {@code RemoteAddr} to the real client IP before this filter runs, so the value
     * returned here is correct in both direct and proxied deployments.
     */
    private String resolveClientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
