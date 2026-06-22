package com.example.dating.matching;

import com.example.dating.config.DistributedLockService;
import com.example.dating.config.SpotifyClientConfig;
import com.example.dating.exceptions.SpotifyApiException;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.SpotifyService;
import com.example.dating.services.matching.GenreExtractionService;
import com.example.dating.services.matching.SpotifyGenreSyncService;
import org.junit.jupiter.api.AfterEach;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Batch F (Scalability) — Async Spotify Sync and Blocking I/O
 *
 * <p>Pure unit tests — no Spring context, no DB connection required.
 *
 * <p>Validates:
 * <ol>
 *   <li>The three Spotify HTTP calls in {@code syncUserGenrePreferences} run in parallel,
 *       reducing wall time from ~1.5 s to ~500 ms.</li>
 *   <li>A single range failure is tolerated; the other two ranges still contribute.</li>
 *   <li>All-ranges failure propagates as {@link SpotifyApiException}.</li>
 *   <li>{@link SpotifyClientConfig} configures the {@link RestTemplate} with a 5 s connect
 *       timeout and a 10 s read timeout via {@link SimpleClientHttpRequestFactory}.</li>
 * </ol>
 */
class BatchFSpotifyConcurrencyTest {

    private SpotifyService          spotifyService;
    private GenreExtractionService  genreExtractionService;
    private SpotifyGenreSyncService service;
    private User                    user;
    private ThreadPoolTaskExecutor  executor;

    @BeforeEach
    void setUp() {
        spotifyService        = mock(SpotifyService.class);
        genreExtractionService = mock(GenreExtractionService.class);
        UserJpaRepository mockUserJpaRepository = mock(UserJpaRepository.class);

        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setThreadNamePrefix("test-async-");
        executor.initialize();

        // DistributedLockService: mock Redis with no stubs — setIfAbsent() returns null by
        // default, triggering the in-memory fallback without requiring any lenient stubs.
        DistributedLockService distributedLockService =
                new DistributedLockService(mock(StringRedisTemplate.class));
        service = new SpotifyGenreSyncService(
                spotifyService, genreExtractionService, mockUserJpaRepository, executor,
                distributedLockService);

        user = mock(User.class);
        when(user.getId()).thenReturn("test-user-1");
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    // =========================================================================
    // 1. Parallelism — wall time must be ≈ single-call latency, not 3×
    // =========================================================================

    @Test
    @DisplayName("Three Spotify calls run in parallel — total time < 2× single-call latency")
    void syncUserGenrePreferences_threeCallsRunInParallel() throws Exception {
        long callLatencyMs = 300L;

        // Each call sleeps to simulate Spotify network latency
        when(spotifyService.getGenresFromTopArtists("token", 50, "short_term"))
                .thenAnswer(inv -> { Thread.sleep(callLatencyMs); return List.of("pop"); });
        when(spotifyService.getGenresFromTopArtists("token", 50, "medium_term"))
                .thenAnswer(inv -> { Thread.sleep(callLatencyMs); return List.of("rock"); });
        when(spotifyService.getGenresFromTopArtists("token", 50, "long_term"))
                .thenAnswer(inv -> { Thread.sleep(callLatencyMs); return List.of("indie"); });

        long start = System.nanoTime();
        service.syncUserGenrePreferences(user, "token");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // Parallel: should complete in ~300 ms, not 900 ms.
        // Upper bound = 2 × callLatency + 200 ms generous CI tolerance.
        assertThat(elapsedMs)
                .as("Three 300 ms calls should complete in < 800 ms when parallelised "
                        + "(sequential baseline would be ~900 ms). Actual: %d ms", elapsedMs)
                .isLessThan(callLatencyMs * 2 + 200);

        // Lower bound: at least one call's worth of latency must have elapsed
        assertThat(elapsedMs)
                .as("Elapsed time must be at least one call's latency — if < 100 ms the mock is not blocking")
                .isGreaterThan(100);

        // All 3 time ranges must have been called
        verify(spotifyService).getGenresFromTopArtists("token", 50, "short_term");
        verify(spotifyService).getGenresFromTopArtists("token", 50, "medium_term");
        verify(spotifyService).getGenresFromTopArtists("token", 50, "long_term");
    }

    // =========================================================================
    // 2. Partial failure — one range fails, result still populated from other two
    // =========================================================================

    @Test
    @DisplayName("One range failure is tolerated — other two ranges still contribute genres")
    void syncUserGenrePreferences_oneRangeFails_otherTwoSucceed() throws Exception {
        when(spotifyService.getGenresFromTopArtists("token", 50, "short_term"))
                .thenThrow(new RuntimeException("Spotify 503 on short_term"));
        when(spotifyService.getGenresFromTopArtists("token", 50, "medium_term"))
                .thenReturn(List.of("rock", "metal"));
        when(spotifyService.getGenresFromTopArtists("token", 50, "long_term"))
                .thenReturn(List.of("indie"));

        // Must not throw
        int distinctCount = service.syncUserGenrePreferences(user, "token");

        // medium_term (weight 2) → ["rock","rock","metal","metal"]
        // long_term   (weight 1) → ["indie"]
        // distinct: rock, metal, indie = 3
        assertThat(distinctCount)
                .as("Partial failure must not zero out the result; got %d distinct genres", distinctCount)
                .isEqualTo(3);

        verify(genreExtractionService).clearSpotifyPreferences(user);
        verify(genreExtractionService).extractAndSaveGenrePreferences(eq(user), any(), any());
    }

    @Test
    @DisplayName("Two range failures tolerated — surviving range contributes genres")
    void syncUserGenrePreferences_twoRangesFail_survivingRangeContributes() throws Exception {
        when(spotifyService.getGenresFromTopArtists("token", 50, "short_term"))
                .thenThrow(new RuntimeException("timeout"));
        when(spotifyService.getGenresFromTopArtists("token", 50, "medium_term"))
                .thenThrow(new RuntimeException("timeout"));
        when(spotifyService.getGenresFromTopArtists("token", 50, "long_term"))
                .thenReturn(List.of("classical", "ambient"));

        int distinctCount = service.syncUserGenrePreferences(user, "token");

        assertThat(distinctCount)
                .as("Two failures must not suppress the successful range")
                .isEqualTo(2);
    }

    // =========================================================================
    // 3. All-ranges failure → SpotifyApiException
    // =========================================================================

    @Test
    @DisplayName("All ranges fail → SpotifyApiException is thrown, DB is not touched")
    void syncUserGenrePreferences_allRangesFail_throwsSpotifyApiException() throws Exception {
        when(spotifyService.getGenresFromTopArtists(any(), anyInt(), any()))
                .thenThrow(new RuntimeException("Spotify completely down"));

        assertThatThrownBy(() -> service.syncUserGenrePreferences(user, "token"))
                .isInstanceOf(SpotifyApiException.class)
                .hasMessageContaining("all time ranges unavailable");

        // DB must not be touched when all calls failed
        verify(genreExtractionService, never()).clearSpotifyPreferences(any());
        verify(genreExtractionService, never()).extractAndSaveGenrePreferences(any(), any(), any());
    }

    // =========================================================================
    // 4. HTTP client configuration — connect + read timeouts
    // =========================================================================

    @Test
    @DisplayName("spotifyRestTemplate bean uses SimpleClientHttpRequestFactory (not default no-timeout factory)")
    void spotifyRestTemplate_usesSimpleClientHttpRequestFactory() {
        SpotifyClientConfig config = new SpotifyClientConfig();
        RestTemplate rt = config.spotifyRestTemplate();

        assertThat(rt.getRequestFactory())
                .as("RestTemplate must use SimpleClientHttpRequestFactory so timeouts are applied")
                .isInstanceOf(SimpleClientHttpRequestFactory.class);
    }

    @Test
    @DisplayName("spotifyRestTemplate has 5 s connect timeout and 10 s read timeout")
    void spotifyRestTemplate_hasCorrectTimeouts() throws Exception {
        SpotifyClientConfig config = new SpotifyClientConfig();
        RestTemplate rt = config.spotifyRestTemplate();
        SimpleClientHttpRequestFactory factory =
                (SimpleClientHttpRequestFactory) rt.getRequestFactory();

        // SimpleClientHttpRequestFactory does not expose public getters — use reflection.
        // Field names are stable across Spring 6.x.
        Field connectField = SimpleClientHttpRequestFactory.class.getDeclaredField("connectTimeout");
        connectField.setAccessible(true);
        Duration connectTimeout = (Duration) connectField.get(factory);

        Field readField = SimpleClientHttpRequestFactory.class.getDeclaredField("readTimeout");
        readField.setAccessible(true);
        Duration readTimeout = (Duration) readField.get(factory);

        assertThat(connectTimeout)
                .as("connect timeout should be 5 s")
                .isEqualTo(Duration.ofSeconds(5));
        assertThat(readTimeout)
                .as("read timeout should be 10 s")
                .isEqualTo(Duration.ofSeconds(10));
    }
}
