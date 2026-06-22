package com.example.dating.security;

import com.example.dating.exceptions.ErrorCode;
import com.example.dating.models.common.ErrorResponse;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.repositories.UserJpaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Batch E: Blocks unverified-email accounts from accessing protected endpoints.
 *
 * <p>Runs after {@code BearerTokenAuthenticationFilter} so the JWT principal is already
 * available in the {@code SecurityContext}. Requests to auth, onboarding, and public
 * endpoints are exempt so users can complete profile setup while awaiting verification.
 *
 * <p>Spring Boot servlet auto-registration is disabled via
 * {@link SecurityConfig#emailVerificationFilterRegistration} so this filter runs exactly
 * once — inside the Spring Security filter chain, not again in the outer servlet chain.
 *
 * <p><b>Phase 4 — Caffeine cache for {@code emailVerified}:</b> the per-userId
 * verification status is cached for {@link #cacheTtlSeconds} seconds to avoid an
 * extra {@code findById} on every authenticated request. Mirrors the tokenVersion
 * cache pattern in {@link SecurityConfig#jwtDecoder}. {@link AuthServiceImpl#verifyEmail}
 * calls {@link #evictEmailVerified(String)} after a successful verification so the user
 * does not have to wait for the TTL to expire before their next request succeeds.
 */
@Component
@RequiredArgsConstructor
public class EmailVerificationFilter extends OncePerRequestFilter {

    private final UserJpaRepository userJpaRepository;

    /**
     * Spring-managed ObjectMapper (JavaTimeModule registered, timestamps disabled) so the
     * filter's error body serializes {@code Instant} as ISO-8601 — matching the shape
     * {@link com.example.dating.exceptions.GlobalExceptionHandler} produces for every other error.
     */
    private final ObjectMapper objectMapper;

    /** Configurable TTL for the per-userId emailVerified cache (seconds). */
    @Value("${app.email-verification.cache-ttl-seconds:30}")
    private int cacheTtlSeconds;

    /** Lazily built in {@link #initCache} so {@link #cacheTtlSeconds} is bound first. */
    private Cache<String, Boolean> emailVerifiedCache;

    @PostConstruct
    void initCache() {
        emailVerifiedCache = Caffeine.newBuilder()
                .expireAfterWrite(cacheTtlSeconds, TimeUnit.SECONDS)
                .maximumSize(10_000)
                .build();
    }

    /**
     * Path prefixes that do NOT require a verified email address.
     * Auth endpoints: allow login / registration / verification flows.
     * Onboarding endpoints: allow profile completion before verification.
     *
     * <p>{@code /api/test/} is intentionally NOT in this list: the Phase test
     * controllers are {@code @Profile("dev")} and do not exist in non-dev profiles,
     * so codifying the prefix here would only matter if the dev profile were
     * accidentally activated in another environment — at which point we want the
     * filter to apply, not be bypassed.
     */
    private static final List<String> EXEMPT_PREFIXES = List.of(
            "/api/v1/auth/",
            "/api/v1/onboarding/",
            "/public/"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // Pass through exempt paths without a DB lookup.
        if (isExempt(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Pass through unauthenticated requests (public/anonymous routes).
        // BearerTokenAuthenticationFilter has already run; if authentication is present
        // its principal is a Jwt object. AnonymousAuthenticationToken has a String principal
        // and is correctly rejected by the instanceof check below.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Jwt jwt)) {
            filterChain.doFilter(request, response);
            return;
        }

        String userId = jwt.getClaim("userId");
        if (userId == null) {
            filterChain.doFilter(request, response);
            return;
        }

        // Cached lookup — Caffeine's loader is called under a per-key lock so
        // concurrent misses for the same userId result in exactly one DB query.
        // Null return (user not found) is NOT cached (Caffeine skips null values),
        // so deleted-user tokens — which the SecurityConfig.jwtDecoder rejects up
        // front — never linger here. Master Audit Batch G means this method should
        // not normally see a null user, but the defensive null-pass-through below
        // preserves the historical behaviour.
        Boolean emailVerified = emailVerifiedCache.get(userId, id ->
                userJpaRepository.findById(id)
                        .map(UserEntity::getEmailVerified)
                        .orElse(null));

        if (Boolean.FALSE.equals(emailVerified)) {
            // Emit the standardized ErrorResponse shape ({code, message, fields, timestamp}).
            // As a pre-MVC servlet filter this bypasses GlobalExceptionHandler, so the body is
            // built here with a stable, machine-readable code the frontend can switch on
            // directly instead of parsing the human-readable message.
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            ErrorResponse body = ErrorResponse.of(
                    ErrorCode.EMAIL_VERIFICATION_REQUIRED, "Email verification required");
            response.getWriter().write(objectMapper.writeValueAsString(body));
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Removes the cached {@code emailVerified} value for {@code userId}. Called by
     * {@code AuthServiceImpl.verifyEmail} immediately after a successful verification
     * so the next request sees the new state without waiting for the TTL.
     */
    public void evictEmailVerified(String userId) {
        if (emailVerifiedCache != null && userId != null) {
            emailVerifiedCache.invalidate(userId);
        }
    }

    private boolean isExempt(String path) {
        return EXEMPT_PREFIXES.stream().anyMatch(path::startsWith);
    }
}
