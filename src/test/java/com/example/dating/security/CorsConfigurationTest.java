package com.example.dating.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the CORS policy built by {@link SecurityConfig#corsConfigurationSource()}.
 *
 * No Spring context or database is required: we instantiate SecurityConfig directly, inject the
 * allowed-origins string via reflection (mirroring the @Value default), and assert the resulting
 * {@link CorsConfiguration} matches the deployed Vercel frontend — production and preview deploys —
 * while still rejecting unknown origins. This is the regression guard for Bug 1 (browser CORS
 * preflight failing from https://harmony-app-frontend.vercel.app).
 */
class CorsConfigurationTest {

    /** The production default baked into SecurityConfig's @Value fallback. */
    private static final String DEFAULT_ORIGINS =
            "http://localhost:3000,http://localhost:5173,http://localhost:4200,http://localhost:8081,"
                    + "http://127.0.0.1:3000,http://127.0.0.1:5173,"
                    + "https://harmony-app-frontend.vercel.app,https://harmony-app-frontend-*.vercel.app";

    private CorsConfiguration buildConfigFor(String requestUri) {
        SecurityConfig config = new SecurityConfig();
        ReflectionTestUtils.setField(config, "allowedOrigins", DEFAULT_ORIGINS);

        CorsConfigurationSource source = config.corsConfigurationSource();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(requestUri);
        return source.getCorsConfiguration(request);
    }

    @Test
    @DisplayName("Vercel production origin is allowed and echoed back exactly")
    void allowsVercelProductionOrigin() {
        CorsConfiguration cors = buildConfigFor("/api/v1/matching/potential");

        String origin = "https://harmony-app-frontend.vercel.app";
        assertThat(cors.checkOrigin(origin)).isEqualTo(origin);
    }

    @Test
    @DisplayName("Vercel preview deploys (wildcard) are allowed and echoed back exactly")
    void allowsVercelPreviewOrigin() {
        CorsConfiguration cors = buildConfigFor("/api/v1/matching/potential");

        String previewOrigin = "https://harmony-app-frontend-git-feature-abc123.vercel.app";
        // Patterns echo back the concrete requesting origin (never "*"), so credentials work.
        assertThat(cors.checkOrigin(previewOrigin)).isEqualTo(previewOrigin);
    }

    @Test
    @DisplayName("Local dev origin (localhost:3000) is still allowed")
    void allowsLocalDevOrigin() {
        CorsConfiguration cors = buildConfigFor("/api/v1/spotify/genres");

        String origin = "http://localhost:3000";
        assertThat(cors.checkOrigin(origin)).isEqualTo(origin);
    }

    @Test
    @DisplayName("Unknown origin is rejected (no Access-Control-Allow-Origin)")
    void rejectsUnknownOrigin() {
        CorsConfiguration cors = buildConfigFor("/api/v1/matching/potential");

        assertThat(cors.checkOrigin("https://evil.example.com")).isNull();
        // A look-alike that is not the configured Vercel app must not match the wildcard.
        assertThat(cors.checkOrigin("https://harmony-app-frontend.vercel.app.evil.com")).isNull();
    }

    @Test
    @DisplayName("Preflight essentials: OPTIONS allowed, credentials enabled, Authorization header accepted")
    void preflightEssentials() {
        CorsConfiguration cors = buildConfigFor("/api/v1/matching/potential");

        assertThat(cors.getAllowedMethods()).contains("OPTIONS", "GET", "POST", "PUT", "PATCH", "DELETE");
        assertThat(cors.getAllowCredentials()).isTrue();
        // Authorization is a real request header the browser asks about during preflight.
        assertThat(cors.checkHeaders(java.util.List.of("authorization", "content-type")))
                .containsExactlyInAnyOrder("authorization", "content-type");
    }
}
