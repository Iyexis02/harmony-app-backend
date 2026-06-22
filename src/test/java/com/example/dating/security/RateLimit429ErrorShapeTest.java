package com.example.dating.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;

import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Standardized 429 error body from both rate-limit components.
 *
 * <p>{@link RateLimitFilter} (per-IP, servlet filter) and
 * {@link AuthenticatedRateLimitInterceptor} (per-user, MVC interceptor) both run outside
 * {@code GlobalExceptionHandler} and used to return an ad-hoc
 * {@code {"error":"Too many requests..."}} body. They now emit the standard
 * {@code ErrorResponse} shape ({@code {code, message, fields, timestamp}}) with the stable
 * code {@code RATE_LIMITED}, while still returning HTTP 429 + {@code Retry-After}.
 *
 * <p>Tests:
 * <ol>
 *   <li>Filter: exhausted login bucket → 429 with standard body, code, Retry-After.</li>
 *   <li>Filter: timestamp is ISO-8601, legacy {@code error} field gone.</li>
 *   <li>Interceptor: exhausted swipe bucket → 429 with standard body and code.</li>
 *   <li>Interceptor: request under the limit passes through with no body written.</li>
 * </ol>
 */
class RateLimit429ErrorShapeTest {

    private static final String LOGIN_URI = "/api/v1/auth/login";
    private static final String SWIPE_URI = "/api/v1/matching/swipe";

    /** Mirrors Spring Boot's autoconfigured mapper: JavaTimeModule + timestamps disabled. */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(WRITE_DATES_AS_TIMESTAMPS);

    private RateLimitFilter filter;
    private AuthenticatedRateLimitInterceptor interceptor;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter(new CaffeineRateLimiter(), objectMapper);
        interceptor = new AuthenticatedRateLimitInterceptor(new CaffeineRateLimiter(), objectMapper);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest loginRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", LOGIN_URI);
        request.setRequestURI(LOGIN_URI);
        request.setRemoteAddr("10.0.0.1");
        return request;
    }

    private MockHttpServletResponse exhaustLoginBucket() throws Exception {
        MockHttpServletResponse last = null;
        for (int i = 0; i < 11; i++) { // login limit: 10 / 15 min
            last = new MockHttpServletResponse();
            filter.doFilter(loginRequest(), last, new MockFilterChain());
        }
        return last;
    }

    @Test
    @DisplayName("Filter: exhausted bucket → 429 with standard body, RATE_LIMITED code, Retry-After")
    void filter_exhaustedBucket_returnsStandardErrorBody() throws Exception {
        MockHttpServletResponse response = exhaustLoginBucket();

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).contains("application/json");
        assertThat(response.getHeader("Retry-After")).isNotNull();

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(body.get("message").asText()).isEqualTo("Too many requests. Please try again later.");
        assertThat(body.has("timestamp")).isTrue();
    }

    @Test
    @DisplayName("Filter: timestamp is ISO-8601 and legacy ad-hoc 'error' field is gone")
    void filter_errorBody_isStandardEnvelope() throws Exception {
        MockHttpServletResponse response = exhaustLoginBucket();

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.has("error")).as("legacy ad-hoc field must be gone").isFalse();
        JsonNode ts = body.get("timestamp");
        assertThat(ts.isNumber()).as("timestamp must be a string, not a numeric epoch").isFalse();
        assertThatCode(() -> Instant.parse(ts.asText())).doesNotThrowAnyException();
    }

    private void authenticateAs(String userId) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("userId", userId)
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    private MockHttpServletRequest swipeRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", SWIPE_URI);
        request.setRequestURI(SWIPE_URI);
        return request;
    }

    @Test
    @DisplayName("Interceptor: exhausted swipe bucket → 429 with standard body and RATE_LIMITED code")
    void interceptor_exhaustedBucket_returnsStandardErrorBody() throws Exception {
        authenticateAs("user-429");

        MockHttpServletResponse response = null;
        boolean allowed = true;
        for (int i = 0; i < 61 && allowed; i++) { // swipe limit: 60 / min
            response = new MockHttpServletResponse();
            allowed = interceptor.preHandle(swipeRequest(), response, new Object());
        }

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isNotNull();

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(body.get("message").asText()).isEqualTo("Too many requests. Please try again later.");
        assertThat(body.has("timestamp")).isTrue();
        assertThat(body.has("error")).isFalse();
    }

    @Test
    @DisplayName("Interceptor: request under the limit passes through with no body written")
    void interceptor_underLimit_passesThrough() throws Exception {
        authenticateAs("user-ok");

        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(swipeRequest(), response, new Object());

        assertThat(allowed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
    }
}
