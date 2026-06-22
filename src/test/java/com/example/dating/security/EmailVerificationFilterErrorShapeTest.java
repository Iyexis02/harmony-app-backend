package com.example.dating.security;

import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.repositories.UserJpaRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Problem B — standardized error body from {@code EmailVerificationFilter}.
 *
 * <p>As a pre-MVC servlet filter, this filter bypasses {@code GlobalExceptionHandler} and used to
 * return an ad-hoc {@code {"error":"Email verification required"}} body. With no {@code code} and
 * the wrong field name, clients could not switch on it reliably — it surfaced as "Unknown error".
 * The filter now emits the standard {@code ErrorResponse} shape
 * ({@code {code, message, fields, timestamp}}) with the stable code
 * {@code EMAIL_VERIFICATION_REQUIRED}, while still returning HTTP 403.
 *
 * <p>Tests:
 * <ol>
 *   <li>Unverified user on a gated path → 403 with the standard body and the exact code.</li>
 *   <li>{@code timestamp} is ISO-8601 (not an epoch number), matching every other error.</li>
 *   <li>Verified user → filter chain proceeds, no body written.</li>
 *   <li>Exempt path (/onboarding/) → chain proceeds without a DB lookup.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class EmailVerificationFilterErrorShapeTest {

    @Mock
    private UserJpaRepository userJpaRepository;

    /** Mirrors Spring Boot's autoconfigured mapper: JavaTimeModule + timestamps disabled. */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(WRITE_DATES_AS_TIMESTAMPS);

    private EmailVerificationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new EmailVerificationFilter(userJpaRepository, objectMapper);
        ReflectionTestUtils.setField(filter, "cacheTtlSeconds", 30);
        ReflectionTestUtils.invokeMethod(filter, "initCache");
    }

    @org.junit.jupiter.api.AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String userId) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("userId", userId)
                .build();
        // Two-arg constructor marks the token authenticated (single-arg leaves it unauthenticated,
        // which the filter would correctly treat as anonymous and pass through).
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, java.util.List.of()));
    }

    @Test
    @DisplayName("Unverified user on gated path → 403 with standard body and EMAIL_VERIFICATION_REQUIRED code")
    void unverifiedUser_returnsStandardErrorBody() throws Exception {
        String userId = "user-unverified";
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setEmailVerified(false);
        when(userJpaRepository.findById(userId)).thenReturn(Optional.of(user));

        authenticateAs(userId);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/preferences/genres");
        request.setRequestURI("/api/v1/preferences/genres");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        ReflectionTestUtils.invokeMethod(filter, "doFilterInternal", request, response, chain);

        // Still 403, JSON content type, and the chain did NOT proceed (request was blocked).
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).contains("application/json");
        assertThat(chain.getRequest()).as("filter must short-circuit the chain").isNull();

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo("EMAIL_VERIFICATION_REQUIRED");
        assertThat(body.get("message").asText()).isEqualTo("Email verification required");
        assertThat(body.has("timestamp")).isTrue();
        // Legacy ad-hoc "error" field must be gone.
        assertThat(body.has("error")).isFalse();
    }

    @Test
    @DisplayName("timestamp is ISO-8601, not an epoch number")
    void errorBody_timestampIsIso8601() throws Exception {
        String userId = "user-unverified-2";
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setEmailVerified(false);
        when(userJpaRepository.findById(userId)).thenReturn(Optional.of(user));

        authenticateAs(userId);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/matching/potential");
        request.setRequestURI("/api/v1/matching/potential");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ReflectionTestUtils.invokeMethod(filter, "doFilterInternal", request, response, new MockFilterChain());

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        JsonNode ts = body.get("timestamp");
        assertThat(ts.isNumber()).as("timestamp must be a string, not a numeric epoch").isFalse();
        // Parses cleanly as an Instant → confirms ISO-8601.
        assertThatCode(() -> Instant.parse(ts.asText())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Verified user → filter chain proceeds, no error body written")
    void verifiedUser_passesThrough() throws Exception {
        String userId = "user-verified";
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setEmailVerified(true);
        when(userJpaRepository.findById(userId)).thenReturn(Optional.of(user));

        authenticateAs(userId);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/matching/potential");
        request.setRequestURI("/api/v1/matching/potential");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        ReflectionTestUtils.invokeMethod(filter, "doFilterInternal", request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(chain.getRequest()).as("filter must let verified users through").isNotNull();
    }

    @Test
    @DisplayName("Exempt /onboarding path → chain proceeds without a DB lookup")
    void exemptOnboardingPath_skipsVerificationAndDbLookup() throws Exception {
        // No authentication set: exempt paths short-circuit before reading the SecurityContext.
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/v1/onboarding/music-preferences");
        request.setRequestURI("/api/v1/onboarding/music-preferences");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        ReflectionTestUtils.invokeMethod(filter, "doFilterInternal", request, response, chain);

        assertThat(chain.getRequest()).as("onboarding writes must be exempt").isNotNull();
        verify(userJpaRepository, never()).findById(org.mockito.ArgumentMatchers.anyString());
    }
}
