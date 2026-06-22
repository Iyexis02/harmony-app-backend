package com.example.dating.matching;

import com.example.dating.security.AuthenticatedRateLimitInterceptor;
import com.example.dating.security.CaffeineRateLimiter;
import com.example.dating.security.RateLimitFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Batch F — Rate Limit Proxy Trust + Authenticated Endpoint Rate Limits
 *
 * <p>These are pure unit tests: no Spring context, no DB connection required.
 * They exercise both fixed components directly:
 * <ul>
 *   <li>{@link RateLimitFilter} — proxy trust (X-Forwarded-For must be ignored)</li>
 *   <li>{@link AuthenticatedRateLimitInterceptor} — per-user limits on swipe/score</li>
 * </ul>
 */
class BatchFRateLimitTest {

    private RateLimitFilter               rateLimitFilter;
    private AuthenticatedRateLimitInterceptor interceptor;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        rateLimitFilter = new RateLimitFilter(new CaffeineRateLimiter(), objectMapper);
        interceptor     = new AuthenticatedRateLimitInterceptor(new CaffeineRateLimiter(), objectMapper);
        SecurityContextHolder.clearContext();
    }

    // =========================================================================
    // Proxy Trust — X-Forwarded-For must be ignored by RateLimitFilter
    // =========================================================================

    @Test
    @DisplayName("resolveClientIp uses getRemoteAddr only — X-Forwarded-For is ignored")
    void proxyTrust_xForwardedForSpoofingCannotBypassRateLimit() throws Exception {
        // Exhaust the /auth/register bucket for remoteAddr "127.0.0.1" (limit: 5/hour).
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest req = authRegisterRequest("127.0.0.1");
            req.addHeader("X-Forwarded-For", "99.99.99.99"); // attacker-controlled
            MockHttpServletResponse res = new MockHttpServletResponse();
            rateLimitFilter.doFilter(req, res, noopChain());
            assertThat(res.getStatus())
                    .as("request %d should not be rate-limited yet", i + 1)
                    .isNotEqualTo(429);
        }

        // 6th request from the SAME remoteAddr but a DIFFERENT spoofed X-Forwarded-For.
        // Before the fix: the filter keyed on XFF ("11.11.11.11") → fresh bucket → 200.
        // After the fix:  the filter keys on remoteAddr ("127.0.0.1") → exhausted → 429.
        MockHttpServletRequest req6 = authRegisterRequest("127.0.0.1");
        req6.addHeader("X-Forwarded-For", "11.11.11.11");
        MockHttpServletResponse res6 = new MockHttpServletResponse();
        rateLimitFilter.doFilter(req6, res6, noopChain());

        assertThat(res6.getStatus())
                .as("spoofing X-Forwarded-For must not reset the bucket")
                .isEqualTo(429);
        assertThat(res6.getHeader("Retry-After")).isNotNull();
    }

    @Test
    @DisplayName("Different remoteAddrs have independent buckets regardless of X-Forwarded-For")
    void proxyTrust_differentRemoteAddrsHaveSeparateBuckets() throws Exception {
        // Exhaust the bucket for 127.0.0.1.
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest req = authRegisterRequest("127.0.0.1");
            rateLimitFilter.doFilter(req, new MockHttpServletResponse(), noopChain());
        }

        // Request from a genuinely different remoteAddr should get its own fresh bucket.
        MockHttpServletRequest req = authRegisterRequest("10.0.0.1");
        MockHttpServletResponse res = new MockHttpServletResponse();
        rateLimitFilter.doFilter(req, res, noopChain());

        assertThat(res.getStatus())
                .as("a different remote address must not be penalised by another IP's exhausted bucket")
                .isNotEqualTo(429);
    }

    // =========================================================================
    // Authenticated swipe rate limit — 60 / minute per user
    // =========================================================================

    @Test
    @DisplayName("Swipe endpoint: exactly 60 requests allowed, remainder blocked")
    void swipe_serial_60Allowed_excessBlocked() throws Exception {
        setSecurityContext("swipe-serial-user");

        int allowed = 0, blocked = 0;
        for (int i = 0; i < 65; i++) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            if (interceptor.preHandle(swipeRequest(), res, new Object())) {
                allowed++;
            } else {
                blocked++;
                assertThat(res.getStatus()).isEqualTo(429);
                assertThat(res.getHeader("Retry-After")).isNotNull();
            }
        }

        assertThat(allowed).isEqualTo(60);
        assertThat(blocked).isEqualTo(5);
    }

    @Test
    @DisplayName("Swipe endpoint: concurrent burst from same user — exactly 60 pass")
    void swipe_concurrent_only60Pass() throws InterruptedException {
        int total   = 80;
        int threads = 20;

        ExecutorService pool  = Executors.newFixedThreadPool(threads);
        CountDownLatch  start = new CountDownLatch(1);
        CountDownLatch  done  = new CountDownLatch(total);
        AtomicInteger   passed = new AtomicInteger();

        for (int i = 0; i < total; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    setSecurityContext("swipe-concurrent-user");
                    if (interceptor.preHandle(swipeRequest(), new MockHttpServletResponse(), new Object())) {
                        passed.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await();
        pool.shutdown();

        assertThat(passed.get())
                .as("bucket capacity is 60; no more, no fewer concurrent requests must pass")
                .isEqualTo(60);
    }

    // =========================================================================
    // Authenticated score rate limit — 30 / minute per user
    // =========================================================================

    @Test
    @DisplayName("Score endpoint: exactly 30 requests allowed, remainder blocked")
    void score_serial_30Allowed_excessBlocked() throws Exception {
        setSecurityContext("score-serial-user");

        int allowed = 0, blocked = 0;
        for (int i = 0; i < 34; i++) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            if (interceptor.preHandle(scoreRequest("target-" + i), res, new Object())) {
                allowed++;
            } else {
                blocked++;
                assertThat(res.getStatus()).isEqualTo(429);
            }
        }

        assertThat(allowed).isEqualTo(30);
        assertThat(blocked).isEqualTo(4);
    }

    @Test
    @DisplayName("Score endpoint: concurrent burst — exactly 30 pass")
    void score_concurrent_only30Pass() throws InterruptedException {
        int total   = 50;
        int threads = 10;

        ExecutorService pool  = Executors.newFixedThreadPool(threads);
        CountDownLatch  start = new CountDownLatch(1);
        CountDownLatch  done  = new CountDownLatch(total);
        AtomicInteger   passed = new AtomicInteger();

        for (int i = 0; i < total; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    setSecurityContext("score-concurrent-user");
                    if (interceptor.preHandle(scoreRequest("target-" + idx), new MockHttpServletResponse(), new Object())) {
                        passed.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await();
        pool.shutdown();

        assertThat(passed.get()).isEqualTo(30);
    }

    // =========================================================================
    // Per-user bucket isolation
    // =========================================================================

    @Test
    @DisplayName("Two users have independent swipe buckets — each gets their full 60")
    void swipe_differentUsers_independentBuckets_concurrent() throws InterruptedException {
        int requestsPerUser = 60;

        ExecutorService pool   = Executors.newFixedThreadPool(16);
        CountDownLatch  start  = new CountDownLatch(1);
        CountDownLatch  done   = new CountDownLatch(requestsPerUser * 2);
        AtomicInteger   aPass  = new AtomicInteger();
        AtomicInteger   bPass  = new AtomicInteger();

        for (int i = 0; i < requestsPerUser; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    setSecurityContext("bucket-isolation-user-A");
                    if (interceptor.preHandle(swipeRequest(), new MockHttpServletResponse(), new Object())) {
                        aPass.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
            pool.submit(() -> {
                try {
                    start.await();
                    setSecurityContext("bucket-isolation-user-B");
                    if (interceptor.preHandle(swipeRequest(), new MockHttpServletResponse(), new Object())) {
                        bPass.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await();
        pool.shutdown();

        assertThat(aPass.get())
                .as("user A must get the full 60-request budget regardless of user B")
                .isEqualTo(requestsPerUser);
        assertThat(bPass.get())
                .as("user B must get the full 60-request budget regardless of user A")
                .isEqualTo(requestsPerUser);
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    @DisplayName("Unrelated endpoint passes through the interceptor untouched")
    void unrelatedEndpoint_notIntercepted() throws Exception {
        setSecurityContext("edge-user-1");
        MockHttpServletRequest  req = new MockHttpServletRequest("GET", "/api/v1/matching/potential");
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(req, res, new Object());

        assertThat(result).isTrue();
        assertThat(res.getStatus()).isNotEqualTo(429);
    }

    @Test
    @DisplayName("Interceptor passes through when no JWT is present (Spring Security will reject)")
    void noAuthentication_interceptorDoesNotBlock() throws Exception {
        SecurityContextHolder.clearContext(); // explicitly no auth
        MockHttpServletRequest  req = swipeRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(req, res, new Object());

        assertThat(result)
                .as("interceptor must not 429 on unauthenticated requests — Spring Security handles 401")
                .isTrue();
    }

    @Test
    @DisplayName("429 response contains Retry-After header and JSON content type")
    void rateLimited_responseShape() throws Exception {
        setSecurityContext("response-shape-user");
        for (int i = 0; i < 30; i++) {
            interceptor.preHandle(scoreRequest("any"), new MockHttpServletResponse(), new Object());
        }

        MockHttpServletResponse res = new MockHttpServletResponse();
        interceptor.preHandle(scoreRequest("any"), res, new Object());

        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getHeader("Retry-After")).isNotNull();
        assertThat(res.getContentType()).contains("application/json");
        assertThat(res.getContentAsString()).contains("Too many requests");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private MockHttpServletRequest authRegisterRequest(String remoteAddr) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/register");
        req.setRemoteAddr(remoteAddr);
        return req;
    }

    private MockHttpServletRequest swipeRequest() {
        return new MockHttpServletRequest("POST", "/api/v1/matching/swipe");
    }

    private MockHttpServletRequest scoreRequest(String targetUserId) {
        return new MockHttpServletRequest("GET", "/api/v1/matching/score/" + targetUserId);
    }

    /**
     * Populates the {@link SecurityContextHolder} in the <em>calling thread</em> with a
     * minimal JWT whose only claim is {@code userId}. Executor threads that need an auth
     * context must call this themselves before invoking the interceptor.
     */
    private void setSecurityContext(String userId) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .claim("userId", userId)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(jwt);
        SecurityContextHolder.setContext(new SecurityContextImpl(auth));
    }

    private FilterChain noopChain() throws Exception {
        return mock(FilterChain.class);
    }
}
