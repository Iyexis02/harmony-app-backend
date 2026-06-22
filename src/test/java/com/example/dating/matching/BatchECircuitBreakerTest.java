package com.example.dating.matching;

import com.example.dating.exceptions.SpotifyApiException;
import com.example.dating.exceptions.SpotifyTokenRevokedException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Batch E — Circuit Breakers for External APIs.
 *
 * Uses Resilience4j's programmatic API to test the circuit breaker configuration
 * that is applied to Spotify and geocoding API calls in production.
 *
 * Tests (no Spring context, no database required):
 * 1. Circuit opens after enough failures reach the failure-rate threshold.
 * 2. SpotifyTokenRevokedException (401/403) does NOT trip the circuit.
 * 3. Once open, CallNotPermittedException is thrown immediately (no retry / blocking).
 * 4. Concurrent: 20 threads against an open circuit all fail fast.
 * 5. Geocoding circuit is independent from the Spotify circuit.
 * 6. Non-ignored exception does count as failure and trips the circuit.
 * 7. Half-open probe: after waitDuration, one call is permitted through.
 */
class BatchECircuitBreakerTest {

    // Mirror production config from application.yml
    private CircuitBreakerConfig spotifyConfig;
    private CircuitBreakerConfig geocodingConfig;
    private CircuitBreakerRegistry registry;

    @BeforeEach
    void setUp() {
        spotifyConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .ignoreExceptions(SpotifyTokenRevokedException.class)
                .build();

        geocodingConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(5)
                .failureRateThreshold(60)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .permittedNumberOfCallsInHalfOpenState(2)
                .build();

        registry = CircuitBreakerRegistry.ofDefaults();
    }

    // ── 1. Circuit opens after sliding window reaches failure threshold ───────

    @Test
    @DisplayName("Spotify circuit opens after 50% failure rate across 10 calls")
    void spotifyCircuit_opensAfterFailureThreshold() {
        CircuitBreaker cb = registry.circuitBreaker("spotify-test-1", spotifyConfig);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // Fill the 10-call sliding window with 6 failures (60% > 50% threshold)
        for (int i = 0; i < 10; i++) {
            final int call = i;
            try {
                cb.executeSupplier(() -> {
                    if (call < 6) throw new SpotifyApiException("Spotify down");
                    return "ok";
                });
            } catch (SpotifyApiException ignored) {}
        }

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    // ── 2. SpotifyTokenRevokedException does NOT trip the circuit ─────────────

    @Test
    @DisplayName("SpotifyTokenRevokedException (401/403) does not count as a circuit failure")
    void revokedTokenException_doesNotTripCircuit() {
        CircuitBreaker cb = registry.circuitBreaker("spotify-test-2", spotifyConfig);

        // All 10 calls throw SpotifyTokenRevokedException (ignored exception)
        for (int i = 0; i < 10; i++) {
            try {
                cb.executeSupplier(() -> {
                    throw new SpotifyTokenRevokedException("token revoked");
                });
            } catch (SpotifyTokenRevokedException ignored) {}
        }

        // Circuit must still be CLOSED — ignored exceptions never count as failures.
        // getFailureRate() returns -1.0 when the window has no countable calls, which
        // is the expected result when every call was ignored. State is the definitive check.
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ── 3. Open circuit rejects calls immediately ─────────────────────────────

    @Test
    @DisplayName("Open circuit throws CallNotPermittedException immediately without executing the supplier")
    void openCircuit_throwsCallNotPermittedImmediately() {
        CircuitBreaker cb = registry.circuitBreaker("spotify-test-3", spotifyConfig);

        // Force the circuit open by recording 10 failures directly
        for (int i = 0; i < 10; i++) {
            cb.onError(0, TimeUnit.NANOSECONDS, new SpotifyApiException("forced failure"));
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        AtomicInteger supplierInvocations = new AtomicInteger(0);
        assertThatThrownBy(() ->
                cb.executeSupplier(() -> {
                    supplierInvocations.incrementAndGet();
                    return "should not run";
                })
        ).isInstanceOf(CallNotPermittedException.class);

        // Supplier body was never executed — fail-fast behaviour confirmed
        assertThat(supplierInvocations.get()).isZero();
    }

    // ── 4. Concurrent: 20 threads against an open circuit all fail fast ───────

    @Test
    @DisplayName("20 concurrent threads against an open circuit all receive CallNotPermittedException")
    void concurrent_openCircuit_allThreadsFailFast() throws InterruptedException {
        CircuitBreaker cb = registry.circuitBreaker("spotify-test-4", spotifyConfig);

        // Force open
        for (int i = 0; i < 10; i++) {
            cb.onError(0, TimeUnit.NANOSECONDS, new SpotifyApiException("forced"));
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int threadCount = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<Throwable>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                try {
                    cb.executeSupplier(() -> "ok");
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            }));
        }

        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        int callNotPermitted = 0;
        for (Future<Throwable> f : futures) {
            Throwable t;
            try { t = f.get(); } catch (ExecutionException e) { t = e.getCause(); }
            assertThat(t).isInstanceOf(CallNotPermittedException.class);
            callNotPermitted++;
        }
        assertThat(callNotPermitted).isEqualTo(threadCount);
    }

    // ── 5. Geocoding circuit is independent from Spotify circuit ─────────────

    @Test
    @DisplayName("Geocoding circuit trips independently — Spotify open does not affect geocoding")
    void geocodingCircuit_isIndependentFromSpotify() {
        CircuitBreaker spotifyCb = registry.circuitBreaker("spotify-test-5", spotifyConfig);
        CircuitBreaker geocodingCb = registry.circuitBreaker("geocoding-test-5", geocodingConfig);

        // Force Spotify open
        for (int i = 0; i < 10; i++) {
            spotifyCb.onError(0, TimeUnit.NANOSECONDS, new SpotifyApiException("forced"));
        }
        assertThat(spotifyCb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Geocoding circuit should still be CLOSED
        assertThat(geocodingCb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // Geocoding calls still execute normally
        String result = geocodingCb.executeSupplier(() -> "geocoding ok");
        assertThat(result).isEqualTo("geocoding ok");
    }

    // ── 6. Non-ignored exception counts as failure ────────────────────────────

    @Test
    @DisplayName("SpotifyApiException (non-ignored) counts as failure and trips the circuit")
    void spotifyApiException_countsAsFailureAndTripsCircuit() {
        CircuitBreaker cb = registry.circuitBreaker("spotify-test-6", spotifyConfig);

        // 6 SpotifyApiException failures in 10 calls (60% > 50% threshold)
        for (int i = 0; i < 10; i++) {
            final int call = i;
            try {
                cb.executeSupplier(() -> {
                    if (call < 6) throw new SpotifyApiException("api down");
                    return "ok";
                });
            } catch (SpotifyApiException ignored) {}
        }

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.getMetrics().getFailureRate()).isGreaterThanOrEqualTo(50.0f);
    }

    // ── 7. Half-open probe: after waitDuration expires, one call is allowed ───

    @Test
    @DisplayName("Circuit transitions to HALF_OPEN after wait duration and allows probe calls")
    void circuit_transitionsToHalfOpen_afterWaitDuration() {
        // Use a very short wait to keep the test fast
        CircuitBreakerConfig fastConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMillis(100))
                .permittedNumberOfCallsInHalfOpenState(3)
                .ignoreExceptions(SpotifyTokenRevokedException.class)
                .build();

        CircuitBreaker cb = registry.circuitBreaker("spotify-test-7", fastConfig);

        // Force open
        for (int i = 0; i < 10; i++) {
            cb.onError(0, TimeUnit.NANOSECONDS, new SpotifyApiException("forced"));
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Transition to HALF_OPEN manually (simulates wait duration elapsed)
        cb.transitionToHalfOpenState();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // 3 successful probe calls should close the circuit
        for (int i = 0; i < 3; i++) {
            cb.executeSupplier(() -> "probe ok");
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
