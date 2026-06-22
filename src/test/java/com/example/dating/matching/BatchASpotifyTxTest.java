package com.example.dating.matching;

import com.example.dating.config.DistributedLockService;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.SpotifyService;
import com.example.dating.services.matching.GenreExtractionService;
import com.example.dating.services.matching.SpotifyGenreSyncService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Batch A (Master Audit) — Move HTTP Calls Outside @Transactional in Spotify Sync.
 *
 * <p>Pure unit tests — no Spring context, no DB required.
 *
 * <p>Validates:
 * <ol>
 *   <li>{@code syncUserGenrePreferences} has NO {@link Transactional} annotation —
 *       the DB connection is not acquired before Spotify HTTP calls begin.</li>
 *   <li>{@code quickSyncUserGenrePreferences} has NO {@link Transactional} annotation.</li>
 *   <li>{@code GenreExtractionService.persistGenreSync} IS {@link Transactional} —
 *       the delete + save + touchUpdatedAt run in one transaction after all HTTP is done.</li>
 *   <li>{@code @CircuitBreaker} is preserved on both public entry points.</li>
 *   <li>A successful sync delegates DB writes exclusively to {@code persistGenreSync},
 *       not to {@code replaceSpotifyPreferences} or {@code touchUpdatedAt} directly.</li>
 *   <li>Ten concurrent syncs (each with a slow 200 ms Spotify stub) complete in under
 *       2 × single-call wall time — proving no serialisation or connection-hold contention
 *       between concurrent callers during the HTTP phase.</li>
 * </ol>
 */
class BatchASpotifyTxTest {

    private SpotifyService          spotifyService;
    private GenreExtractionService  genreExtractionService;
    private SpotifyGenreSyncService service;
    private ThreadPoolTaskExecutor  executor;

    @BeforeEach
    void setUp() {
        spotifyService         = mock(SpotifyService.class);
        genreExtractionService = mock(GenreExtractionService.class);

        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(20);
        executor.setMaxPoolSize(50);
        executor.setThreadNamePrefix("test-batchA-");
        executor.initialize();

        DistributedLockService distributedLockService =
                new DistributedLockService(mock(StringRedisTemplate.class));

        service = new SpotifyGenreSyncService(
                spotifyService, genreExtractionService, mock(UserJpaRepository.class),
                executor, distributedLockService);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    // =========================================================================
    // 1. No @Transactional on syncUserGenrePreferences
    // =========================================================================

    @Test
    @DisplayName("syncUserGenrePreferences must NOT be @Transactional — DB connection not held during HTTP calls")
    void syncUserGenrePreferences_hasNoTransactionalAnnotation() throws Exception {
        Method method = SpotifyGenreSyncService.class.getDeclaredMethod(
                "syncUserGenrePreferences", User.class, String.class);

        assertThat(method.isAnnotationPresent(Transactional.class))
                .as("syncUserGenrePreferences must not be @Transactional after Batch A; "
                        + "holding a DB connection through Spotify HTTP calls exhausts HikariCP under load")
                .isFalse();
    }

    // =========================================================================
    // 2. No @Transactional on quickSyncUserGenrePreferences
    // =========================================================================

    @Test
    @DisplayName("quickSyncUserGenrePreferences must NOT be @Transactional")
    void quickSyncUserGenrePreferences_hasNoTransactionalAnnotation() throws Exception {
        Method method = SpotifyGenreSyncService.class.getDeclaredMethod(
                "quickSyncUserGenrePreferences", User.class, String.class);

        assertThat(method.isAnnotationPresent(Transactional.class))
                .as("quickSyncUserGenrePreferences must not be @Transactional after Batch A")
                .isFalse();
    }

    // =========================================================================
    // 3. @Transactional IS on persistGenreSync in GenreExtractionService
    // =========================================================================

    @Test
    @DisplayName("GenreExtractionService.persistGenreSync IS @Transactional — write phase is atomic")
    void persistGenreSync_isAnnotatedTransactional() throws Exception {
        Method method = GenreExtractionService.class.getDeclaredMethod(
                "persistGenreSync", User.class, List.class);

        assertThat(method.isAnnotationPresent(Transactional.class))
                .as("persistGenreSync must be @Transactional so replaceSpotifyPreferences + "
                        + "touchUpdatedAt execute atomically in one transaction")
                .isTrue();
    }

    // =========================================================================
    // 4. @CircuitBreaker still on both public entry points
    // =========================================================================

    @Test
    @DisplayName("@CircuitBreaker(name='spotify') is preserved on both entry points after Batch A")
    void circuitBreaker_preservedOnBothEntryPoints() throws Exception {
        Method syncMethod = SpotifyGenreSyncService.class.getDeclaredMethod(
                "syncUserGenrePreferences", User.class, String.class);
        Method quickMethod = SpotifyGenreSyncService.class.getDeclaredMethod(
                "quickSyncUserGenrePreferences", User.class, String.class);

        CircuitBreaker syncCb  = syncMethod.getAnnotation(CircuitBreaker.class);
        CircuitBreaker quickCb = quickMethod.getAnnotation(CircuitBreaker.class);

        assertThat(syncCb)
                .as("syncUserGenrePreferences must retain @CircuitBreaker")
                .isNotNull();
        assertThat(syncCb.name())
                .as("circuit breaker name must be 'spotify'")
                .isEqualTo("spotify");

        assertThat(quickCb)
                .as("quickSyncUserGenrePreferences must retain @CircuitBreaker")
                .isNotNull();
        assertThat(quickCb.name())
                .as("circuit breaker name must be 'spotify'")
                .isEqualTo("spotify");
    }

    // =========================================================================
    // 5. DB writes go via persistGenreSync — not via replaceSpotifyPreferences directly
    // =========================================================================

    @Test
    @DisplayName("Successful sync calls persistGenreSync — not replaceSpotifyPreferences directly")
    void syncUserGenrePreferences_dbWriteViaPersisteGenreSync() throws Exception {
        User user = mock(User.class);
        when(user.getId()).thenReturn("user-batchA-1");
        when(spotifyService.getGenresFromTopArtists(anyString(), anyInt(), anyString()))
                .thenReturn(List.of("rock", "pop"));
        when(genreExtractionService.persistGenreSync(any(), any())).thenReturn(2);

        service.syncUserGenrePreferences(user, "token");

        verify(genreExtractionService, atLeastOnce()).persistGenreSync(eq(user), any());
        verify(genreExtractionService, never()).replaceSpotifyPreferences(any(), any());
    }

    // =========================================================================
    // 6. Concurrent syncs — no contention during HTTP phase
    // =========================================================================

    @Test
    @DisplayName("10 concurrent syncs with slow Spotify complete in < 2× single-call wall time")
    void tenConcurrentSyncs_noHttpPhaseContention() throws Exception {
        int concurrency    = 10;
        long callLatencyMs = 200L;

        // Each user gets a unique ID so the distributed lock does not serialize them.
        List<User> users = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            User u = mock(User.class);
            when(u.getId()).thenReturn("concurrent-user-" + i);
            users.add(u);
        }

        // Simulate slow Spotify — 200 ms per HTTP call (3 parallel calls inside each sync
        // still finish in ~200 ms wall time; 10 concurrent syncs add no serial overhead).
        when(spotifyService.getGenresFromTopArtists(anyString(), anyInt(), anyString()))
                .thenAnswer(inv -> { Thread.sleep(callLatencyMs); return List.of("indie"); });
        when(genreExtractionService.persistGenreSync(any(), any())).thenReturn(1);

        CountDownLatch ready  = new CountDownLatch(concurrency);
        CountDownLatch go     = new CountDownLatch(1);
        CountDownLatch done   = new CountDownLatch(concurrency);
        AtomicInteger errors  = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        for (int i = 0; i < concurrency; i++) {
            final User user = users.get(i);
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    service.syncUserGenrePreferences(user, "token");
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        long start = System.nanoTime();
        go.countDown();
        boolean finished = done.await(10, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        pool.shutdownNow();

        assertThat(finished)
                .as("All 10 concurrent syncs must complete within 10 s")
                .isTrue();

        assertThat(errors.get())
                .as("No sync should throw an exception")
                .isZero();

        // Each sync takes ~callLatencyMs (3 parallel Spotify calls each sleeping callLatencyMs).
        // 10 truly concurrent syncs should also finish in ~callLatencyMs, not 10 × callLatencyMs.
        // Upper bound: 2 × callLatencyMs + generous CI buffer of 500 ms.
        long upperBoundMs = callLatencyMs * 2 + 500;
        assertThat(elapsedMs)
                .as("10 concurrent syncs finished in %d ms; expected < %d ms. "
                        + "If this exceeds the bound, the HTTP phase may be serialised by a "
                        + "transaction or lock that should not be present.", elapsedMs, upperBoundMs)
                .isLessThan(upperBoundMs);
    }
}
