package com.example.dating.security;

import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.models.user.common.dao.UserEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationFilter;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;


@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${jwt.secret.key}") // read from environment variable
    private String secret;

    /** Batch F: TTL for the per-userId token-version cache (seconds). Default 30s. */
    @Value("${jwt.token-version.cache-ttl-seconds:30}")
    private int tokenVersionCacheTtlSeconds;

    /**
     * Batch C: Fail fast if the configured HMAC key is shorter than 256 bits (32 bytes).
     * A short key weakens HMAC-SHA256 and must never reach production.
     */
    @PostConstruct
    public void validateJwtSecret() {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET_KEY must be at least 32 bytes (256 bits)");
        }
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   EmailVerificationFilter emailVerificationFilter) throws Exception {
        http
                .cors(Customizer.withDefaults())  // Enable CORS
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // connect-spotify is an authenticated action — must precede the auth wildcard
                        .requestMatchers("/api/v1/auth/connect-spotify").authenticated()
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/api/v1/spotify/**").permitAll()
                        .requestMatchers("/public/**").permitAll()
                        // Batch F: health probes must be public for load-balancer/K8s liveness
                        // and readiness checks. All other Actuator paths require a valid JWT.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").authenticated()
                        // Batch I: Swagger UI and OpenAPI spec — disabled by config in production
                        // (springdoc.api-docs.enabled: false). permitAll here avoids 401 confusion
                        // in dev and is harmless in production where these paths return 404.
                        .requestMatchers("/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                )
                // Batch E: enforce email verification after JWT authentication.
                .addFilterAfter(emailVerificationFilter, BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Batch E: Prevent Spring Boot from auto-registering EmailVerificationFilter as a
     * top-level servlet filter.  Without this, the filter would run twice per request —
     * once in the outer servlet filter chain (before Spring Security, where no principal
     * is set yet) and again inside the security chain where it is intentionally placed.
     */
    @Bean
    public FilterRegistrationBean<EmailVerificationFilter> emailVerificationFilterRegistration(
            EmailVerificationFilter filter) {
        FilterRegistrationBean<EmailVerificationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Allow specific origins (update for production)
        configuration.setAllowedOrigins(Arrays.asList(
                "http://localhost:3000",      // React default
                "http://localhost:5173",      // Vite default
                "http://localhost:4200",      // Angular default
                "http://localhost:8081",      // Alternative port
                "http://127.0.0.1:3000",
                "http://127.0.0.1:5173"
        ));

        // Allow all HTTP methods
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));

        // Allow all headers
        configuration.setAllowedHeaders(List.of("*"));

        // Allow credentials (cookies, authorization headers)
        configuration.setAllowCredentials(true);

        // Expose headers to the browser
        // X-Correlation-Id is exposed so the frontend can log it alongside client-side errors
        // for cross-referencing with server-side structured logs (Batch H).
        configuration.setExposedHeaders(Arrays.asList("Authorization", "Content-Type", "X-Correlation-Id"));

        // Cache preflight response for 1 hour
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }

    @Bean
    public JwtDecoder jwtDecoder(UserJpaRepository userJpaRepository) {
        NimbusJwtDecoder nimbusDecoder = NimbusJwtDecoder.withSecretKey(
                new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")
        ).build();

        // Batch F: cache DB token-versions to avoid a DB hit on every authenticated request.
        // TTL is configurable (default 30s). Password-reset revocation propagates within
        // that window, which is the documented acceptable trade-off. Caffeine's loader is
        // called under a per-key lock, so concurrent cache misses for the same userId
        // result in exactly one DB query. Null return (user not found) is not cached —
        // Caffeine skips null values — so deleted-user tokens re-query the DB each time.
        Cache<String, Integer> tokenVersionCache = Caffeine.newBuilder()
                .expireAfterWrite(tokenVersionCacheTtlSeconds, TimeUnit.SECONDS)
                .maximumSize(10_000)
                .build();

        return token -> {
            Jwt jwt = nimbusDecoder.decode(token);

            // Batch C: Every token that carries a userId must also carry tokenVersion.
            // Tokens issued before Batch A lacked the claim and could bypass password-reset
            // invalidation indefinitely. They are now hard-rejected so all sessions are
            // forced to re-login and receive a clean, versioned token.
            String userId = jwt.getClaim("userId");
            Object versionClaim = jwt.getClaim("tokenVersion");
            if (userId != null) {
                if (versionClaim == null) {
                    throw new BadJwtException("Token missing required version claim");
                }
                int jwtVersion = ((Number) versionClaim).intValue();
                // Batch F: look up DB version via cache. Caffeine does not cache null
                // values, so deleted-user tokens always re-query the DB on each request.
                // Batch G: null dbVersion means the user no longer exists in the DB.
                // Reject hard with BadJwtException so Spring Security returns 401 before
                // the request reaches any controller. Without this, the token passes the
                // decoder, reaches the controller, and produces 404 "User not found" —
                // leaking whether the account ever existed. A 401 reveals nothing.
                Integer dbVersion = tokenVersionCache.get(userId, id -> {
                    UserEntity u = userJpaRepository.findById(id).orElse(null);
                    return u != null ? (u.getTokenVersion() != null ? u.getTokenVersion() : 0) : null;
                });
                if (dbVersion == null) {
                    throw new BadJwtException("Invalid token");
                }
                if (jwtVersion != dbVersion) {
                    throw new BadJwtException("Token has been invalidated");
                }
            }

            return jwt;
        };
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
