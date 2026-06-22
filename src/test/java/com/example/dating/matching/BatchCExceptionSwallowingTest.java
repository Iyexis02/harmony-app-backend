package com.example.dating.matching;

import com.example.dating.config.DistributedLockService;
import com.example.dating.exceptions.SpotifyApiException;
import com.example.dating.mappers.SpotifyMapper;
import com.example.dating.models.user.artists.dto.SpotifyArtistDto;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.SpotifyService;
import com.example.dating.services.impl.SpotifyServiceImpl;
import com.example.dating.services.matching.GenreExtractionService;
import com.example.dating.services.matching.SpotifyGenreSyncService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Batch C (Master Audit) — Fix Exception Swallowing in getGenresFromTopArtists.
 *
 * <p>Pure unit tests — no Spring context, no DB required.
 *
 * <p>Validates:
 * <ol>
 *   <li>{@code getGenresFromTopArtists} propagates {@link RuntimeException} from
 *       {@code getTopArtists} instead of swallowing it into an empty list.</li>
 *   <li>{@code getGenresFromTopArtists} propagates {@link SpotifyApiException}
 *       (circuit-open fallback), restoring the circuit-breaker feedback loop.</li>
 *   <li>The {@code failedRanges} counter in {@code syncUserGenrePreferences} now
 *       increments when a range throws — previously it was dead code.</li>
 *   <li>All 3 ranges failing throws {@link SpotifyApiException} from the sync
 *       service (the {@code failedRanges == 3} path was dead code before the fix).</li>
 *   <li>20 concurrent syncs (all Spotify down) all surface {@link SpotifyApiException}
 *       — none silently returns {@code 0} as the pre-fix behaviour did.</li>
 * </ol>
 */
class BatchCExceptionSwallowingTest {

    private GenreExtractionService genreExtractionService;
    private ThreadPoolTaskExecutor executor;

    @BeforeEach
    void setUp() {
        genreExtractionService = mock(GenreExtractionService.class);

        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(25);
        executor.setMaxPoolSize(50);
        executor.setThreadNamePrefix("test-batchC-");
        executor.initialize();
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    /**
     * Creates a {@link SpotifyServiceImpl} whose {@code getTopArtists} always throws
     * the supplied exception. Using a subclass lets us override without Spring AOP,
     * which is what the real service uses for its {@code @CircuitBreaker} proxy — a
     * Mockito spy on the concrete class would NOT intercept {@code this.getTopArtists()}
     * because internal calls bypass the proxy.
     */
    private SpotifyServiceImpl implThatThrows(RuntimeException ex) {
        return new SpotifyServiceImpl(
                new ObjectMapper(), mock(SpotifyMapper.class), mock(RestTemplate.class)) {
            @Override
            public SpotifyArtistDto getTopArtists(
                    String token, Integer limit, String timeRange, Integer offset) {
                throw ex;
            }
        };
    }

    /** Creates a {@link SpotifyGenreSyncService} backed by the given {@link SpotifyService} mock. */
    private SpotifyGenreSyncService syncService(SpotifyService spotifyService) {
        DistributedLockService lock = new DistributedLockService(mock(StringRedisTemplate.class));
        return new SpotifyGenreSyncService(
                spotifyService, genreExtractionService,
                mock(UserJpaRepository.class), executor, lock);
    }

    // =========================================================================
    // 1. RuntimeException propagates — no longer swallowed
    // =========================================================================

    @Test
    @DisplayName("RuntimeException from getTopArtists propagates — no longer swallowed into empty list")
    void getGenresFromTopArtists_propagatesRuntimeException() throws Exception {
        SpotifyServiceImpl impl = implThatThrows(new RuntimeException("Spotify 500"));

        assertThatThrownBy(() -> impl.getGenresFromTopArtists("token", 50, "short_term"))
                .as("Before the fix this was caught and returned emptyList(); "
                        + "after the fix the RuntimeException must propagate to the caller")
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Spotify 500");
    }

    // =========================================================================
    // 2. SpotifyApiException (circuit-open) propagates — circuit breaker loop restored
    // =========================================================================

    @Test
    @DisplayName("SpotifyApiException (circuit open) propagates — circuit-breaker feedback loop restored")
    void getGenresFromTopArtists_propagatesSpotifyApiException() throws Exception {
        SpotifyServiceImpl impl = implThatThrows(
                new SpotifyApiException("Spotify service temporarily unavailable — circuit open"));

        assertThatThrownBy(() -> impl.getGenresFromTopArtists("token", 50, "short_term"))
                .as("SpotifyApiException thrown by the circuit-breaker fallback must reach the caller, "
                        + "not be swallowed into an empty list that hides the open circuit")
                .isInstanceOf(SpotifyApiException.class)
                .hasMessageContaining("circuit open");
    }

    // =========================================================================
    // 3. failedRanges counter is now active — previously dead code
    // =========================================================================

    @Test
    @DisplayName("failedRanges counter increments when one range throws — partial failure still produces genres")
    void syncUserGenrePreferences_failedRangesCounterIsNowActive() throws Exception {
        SpotifyService spotifyService = mock(SpotifyService.class);
        // short_term fails; medium and long succeed.
        when(spotifyService.getGenresFromTopArtists("token", 50, "short_term"))
                .thenThrow(new SpotifyApiException("Spotify short_term down"));
        when(spotifyService.getGenresFromTopArtists("token", 50, "medium_term"))
                .thenReturn(List.of("rock", "metal"));
        when(spotifyService.getGenresFromTopArtists("token", 50, "long_term"))
                .thenReturn(List.of("indie"));
        when(genreExtractionService.persistGenreSync(any(), any())).thenReturn(3);

        User user = mock(User.class);
        when(user.getId()).thenReturn("user-partial");

        // Must NOT throw — 1 failure is tolerated; failedRanges stays at 1, not 3.
        int result = syncService(spotifyService).syncUserGenrePreferences(user, "token");

        assertThat(result)
                .as("Two successful ranges must produce a non-zero result; failedRanges=1 is tolerated. "
                        + "Before the fix, getGenresFromTopArtists returned [] on error, so the futures "
                        + "completed normally and failedRanges never incremented.")
                .isEqualTo(3);

        verify(genreExtractionService).persistGenreSync(eq(user), any());
    }

    // =========================================================================
    // 4. All 3 ranges fail → SpotifyApiException (dead code path is now live)
    // =========================================================================

    @Test
    @DisplayName("All 3 ranges fail → SpotifyApiException thrown (failedRanges==3 guard is no longer dead code)")
    void syncUserGenrePreferences_allRangesFail_throwsSpotifyApiException() throws Exception {
        SpotifyService spotifyService = mock(SpotifyService.class);
        when(spotifyService.getGenresFromTopArtists(any(), anyInt(), any()))
                .thenThrow(new SpotifyApiException("Spotify completely down"));

        User user = mock(User.class);
        when(user.getId()).thenReturn("user-allfail");

        assertThatThrownBy(() -> syncService(spotifyService).syncUserGenrePreferences(user, "token"))
                .as("When all 3 time ranges fail, syncUserGenrePreferences must throw SpotifyApiException. "
                        + "Before the fix the futures always completed normally (with []), "
                        + "failedRanges stayed 0, and the method returned 0 silently.")
                .isInstanceOf(SpotifyApiException.class)
                .hasMessageContaining("all time ranges unavailable");

        // DB must not be touched when all calls failed.
        verify(genreExtractionService, never()).persistGenreSync(any(), any());
    }

    // =========================================================================
    // 5. Concurrent — 20 users all fail → all surface SpotifyApiException
    // =========================================================================

    @Test
    @DisplayName("20 concurrent syncs all get SpotifyApiException — none silently returns 0 as the pre-fix code did")
    void syncUserGenrePreferences_concurrentAllFail_allThrowNotSilent() throws Exception {
        int threadCount = 20;
        SpotifyService spotifyService = mock(SpotifyService.class);
        when(spotifyService.getGenresFromTopArtists(any(), anyInt(), any()))
                .thenThrow(new SpotifyApiException("Spotify down"));

        SpotifyGenreSyncService svc = syncService(spotifyService);

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done     = new CountDownLatch(threadCount);
        AtomicInteger exceptions  = new AtomicInteger(0);
        AtomicInteger silentZeros = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            final String userId = "concurrent-user-" + i; // distinct IDs → each wins its own lock
            pool.submit(() -> {
                try {
                    startGate.await();
                    User u = mock(User.class);
                    when(u.getId()).thenReturn(userId);
                    svc.syncUserGenrePreferences(u, "token");
                    // Reaching here means the method returned silently (pre-fix behaviour).
                    silentZeros.incrementAndGet();
                } catch (SpotifyApiException e) {
                    exceptions.incrementAndGet();
                } catch (Exception ignored) {
                    // Any other exception also counts as non-silent.
                    exceptions.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS))
                .as("All %d threads must complete within 30 s", threadCount)
                .isTrue();
        pool.shutdown();

        assertThat(silentZeros.get())
                .as("No sync must silently return 0 when Spotify is down (that was the pre-fix behaviour)")
                .isEqualTo(0);
        assertThat(exceptions.get())
                .as("All %d concurrent syncs must surface SpotifyApiException", threadCount)
                .isEqualTo(threadCount);
    }
}
