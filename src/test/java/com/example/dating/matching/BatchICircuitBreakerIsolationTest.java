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
 * Batch I — Separate Spotify Circuit Breakers by Operation Type.
 *
 * Verifies that the three circuit breaker instances introduced in Batch I
 * (spotify-token, spotify-data, spotify-sync) are fully isolated from each other.
 * A burst of failures in spotify-sync must never block spotify-token calls.
 *
 * Uses the Resilience4j programmatic API — no Spring context, no database.
 *
 * Tests:
 * 1. spotify-sync failures do NOT open the spotify-token circuit.
 * 2. spotify-token failures do NOT open the spotify-sync circuit.
 * 3. spotify-data failures do NOT open the spotify-token circuit.
 * 4. SpotifyTokenRevokedException is ignored on spotify-token but counts as
 *    a failure on spotify-data (ignoreExceptions scoped to token CB only).
 * 5. Concurrent: 20 threads flood spotify-sync until it opens; spotify-token
 *    still accepts calls throughout.
 */
class BatchICircuitBreakerIsolationTest {

    // Mirror production config from application.yml (Batch I values)
    private CircuitBreakerConfig tokenConfig;
    private CircuitBreakerConfig dataConfig;
    private CircuitBreakerConfig syncConfig;
    private CircuitBreakerRegistry registry;

    @BeforeEach
    void setUp() {
        tokenConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(5)
                .failureRateThreshold(60)
                .waitDurationInOpenState(Duration.ofSeconds(15))
                .permittedNumberOfCallsInHalfOpenState(2)
                .ignoreExceptions(SpotifyTokenRevokedException.class)
                .build();

        dataConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();

        syncConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();

        registry = CircuitBreakerRegistry.ofDefaults();
    }

    // ── 1. spotify-sync open does NOT affect spotify-token ────────────────────

    @Test
    @DisplayName("Opening spotify-sync does not open spotify-token")
    void syncCircuitOpen_doesNotOpenTokenCircuit() {
        CircuitBreaker syncCb  = registry.circuitBreaker("sync-test-1",  syncConfig);
        CircuitBreaker tokenCb = registry.circuitBreaker("token-test-1", tokenConfig);

        // Drive spotify-sync to OPEN: 10 failures (100% > 50% threshold)
        for (int i = 0; i < 10; i++) {
            syncCb.onError(0, TimeUnit.NANOSECONDS, new SpotifyApiException("genre sync failure"));
        }
        assertThat(syncCb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // spotify-token must be completely unaffected
        assertThat(tokenCb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        String result = tokenCb.executeSupplier(() -> "token refreshed");
        assertThat(result).isEqualTo("token refreshed");
    }

    // ── 2. spotify-token open does NOT affect spotify-sync ───────────────────

    @Test
    @DisplayName("Opening spotify-token does not open spotify-sync")
    void tokenCircuitOpen_doesNotOpenSyncCircuit() {
        CircuitBreaker tokenCb = registry.circuitBreaker("token-test-2", tokenConfig);
        CircuitBreaker syncCb  = registry.circuitBreaker("sync-test-2",  syncConfig);

        // Drive spotify-token to OPEN: 5 failures (100% > 60% threshold)
        for (int i = 0; i < 5; i++) {
            tokenCb.onError(0, TimeUnit.NANOSECONDS, new SpotifyApiException("token refresh failure"));
        }
        assertThat(tokenCb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // spotify-sync must be completely unaffected
        assertThat(syncCb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        String result = syncCb.executeSupplier(() -> "sync ok");
        assertThat(result).isEqualTo("sync ok");
    }

    // ── 3. spotify-data open does NOT affect spotify-token ───────────────────

    @Test
    @DisplayName("Opening spotify-data does not open spotify-token")
    void dataCircuitOpen_doesNotOpenTokenCircuit() {
        CircuitBreaker dataCb  = registry.circuitBreaker("data-test-3",  dataConfig);
        CircuitBreaker tokenCb = registry.circuitBreaker("token-test-3", tokenConfig);

        // Drive spotify-data to OPEN: 10 failures (100% > 50% threshold)
        for (int i = 0; i < 10; i++) {
            dataCb.onError(0, TimeUnit.NANOSECONDS, new SpotifyApiException("top artists failure"));
        }
        assertThat(dataCb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // spotify-token must be completely unaffected
        assertThat(tokenCb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        String result = tokenCb.executeSupplier(() -> "token refreshed");
        assertThat(result).isEqualTo("token refreshed");
    }

    // ── 4. ignoreExceptions scoped to spotify-token only ─────────────────────

    @Test
    @DisplayName("SpotifyTokenRevokedException is ignored on spotify-token but counts as failure on spotify-data")
    void revokedTokenException_ignoredOnTokenCb_countsAsFailureOnDataCb() {
        CircuitBreaker tokenCb = registry.circuitBreaker("token-test-4", tokenConfig);
        CircuitBreaker dataCb  = registry.circuitBreaker("data-test-4",  dataConfig);

        // On spotify-token: 5 SpotifyTokenRevokedException calls — all ignored, circuit stays CLOSED
        for (int i = 0; i < 5; i++) {
            try {
                tokenCb.executeSupplier(() -> {
                    throw new SpotifyTokenRevokedException("token revoked");
                });
            } catch (SpotifyTokenRevokedException ignored) {}
        }
        assertThat(tokenCb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // On spotify-data: SpotifyTokenRevokedException is NOT in ignoreExceptions,
        // so it counts as a failure. Drive 10 calls with 6 of them throwing it.
        for (int i = 0; i < 10; i++) {
            final int call = i;
            try {
                dataCb.executeSupplier(() -> {
                    if (call < 6) throw new SpotifyTokenRevokedException("revoked on data path");
                    return "ok";
                });
            } catch (SpotifyApiException ignored) {}
        }
        assertThat(dataCb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    // ── 5. Concurrent flood of spotify-sync does not block spotify-token ─────

    @Test
    @DisplayName("20 concurrent threads flooding spotify-sync until open; spotify-token still accepts calls")
    void concurrent_syncFlood_tokenCircuitStillAcceptsRequests() throws InterruptedException {
        CircuitBreaker syncCb  = registry.circuitBreaker("sync-test-5",  syncConfig);
        CircuitBreaker tokenCb = registry.circuitBreaker("token-test-5", tokenConfig);

        // Force spotify-sync open from a single thread first (avoids race in the assertion)
        for (int i = 0; i < 10; i++) {
            syncCb.onError(0, TimeUnit.NANOSECONDS, new SpotifyApiException("sync flood"));
        }
        assertThat(syncCb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Now 20 threads hammer the open sync circuit concurrently
        int threadCount = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        AtomicInteger syncRejected = new AtomicInteger(0);
        AtomicInteger tokenSucceeded = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                // Each thread tries the sync circuit (expects rejection)
                try {
                    syncCb.executeSupplier(() -> "sync ok");
                } catch (CallNotPermittedException e) {
                    syncRejected.incrementAndGet();
                }

                // Each thread also tries the token circuit (must succeed)
                try {
                    tokenCb.executeSupplier(() -> "token refreshed");
                    tokenSucceeded.incrementAndGet();
                } catch (Exception e) {
                    // should not happen — token circuit is CLOSED
                }
            }));
        }

        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        for (Future<?> f : futures) {
            try { f.get(); } catch (ExecutionException e) { throw new RuntimeException(e); }
        }

        assertThat(syncRejected.get()).isEqualTo(threadCount);
        assertThat(tokenSucceeded.get()).isEqualTo(threadCount);
    }
}
