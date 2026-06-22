package com.example.dating.matching;

import com.example.dating.config.DistributedLockService;
import com.example.dating.enums.matching.GenrePreferenceSource;
import com.example.dating.exceptions.SpotifyApiException;
import com.example.dating.exceptions.SpotifyTokenRevokedException;
import com.example.dating.models.user.domain.User;
import com.example.dating.services.SpotifyService;
import com.example.dating.services.matching.GenreExtractionService;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.matching.SpotifyGenreSyncService;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Batch D — Retry Logic and Resilience: unit tests for SpotifyGenreSyncService
 * per-range isolation and exception hierarchy.
 *
 * JwtServiceImpl retry logic requires a mocked HTTP server (e.g. WireMock) for
 * full coverage; see the "Local Verification" section in the PR description.
 */
@ExtendWith(MockitoExtension.class)
class BatchDRetryResilienceTest {

    @Mock
    private SpotifyService spotifyService;

    @Mock
    private GenreExtractionService genreExtractionService;

    // UserRepository is unused in syncUserGenrePreferences; pass null — service does not call it directly
    private SpotifyGenreSyncService syncService;

    private User user;

    @BeforeEach
    void setUp() {
        // DistributedLockService: mock Redis with no stubs — setIfAbsent() returns null by
        // default, triggering the in-memory fallback without requiring any lenient stubs.
        DistributedLockService distributedLockService =
                new DistributedLockService(mock(StringRedisTemplate.class));
        syncService = new SpotifyGenreSyncService(spotifyService, genreExtractionService,
                mock(UserJpaRepository.class), Runnable::run, distributedLockService);
        user = User.builder().id("user-1").build();
    }

    // -------------------------------------------------------------------------
    // Exception hierarchy
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("SpotifyTokenRevokedException is a SpotifyApiException")
    void exceptionHierarchy_revokedIsSubtypeOfApi() {
        SpotifyTokenRevokedException revoked = new SpotifyTokenRevokedException("revoked");
        assertThat(revoked).isInstanceOf(SpotifyApiException.class);
        assertThat(revoked).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("SpotifyApiException and SpotifyTokenRevokedException carry cause")
    void exceptionHierarchy_causePreserved() {
        RuntimeException cause = new RuntimeException("original");
        SpotifyApiException api = new SpotifyApiException("wrapped", cause);
        SpotifyTokenRevokedException revoked = new SpotifyTokenRevokedException("revoked", cause);

        assertThat(api.getCause()).isSameAs(cause);
        assertThat(revoked.getCause()).isSameAs(cause);
    }

    // -------------------------------------------------------------------------
    // Per-range isolation — partial failures
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Partial sync succeeds when medium_term fails but other ranges return data")
    void partialSync_mediumTermFails_savesPartialData() throws JsonProcessingException {
        when(spotifyService.getGenresFromTopArtists(any(), eq(50), eq("short_term")))
                .thenReturn(List.of("pop", "rock"));
        when(spotifyService.getGenresFromTopArtists(any(), eq(50), eq("medium_term")))
                .thenThrow(new RuntimeException("Spotify 503"));
        when(spotifyService.getGenresFromTopArtists(any(), eq(50), eq("long_term")))
                .thenReturn(List.of("jazz"));

        int count = syncService.syncUserGenrePreferences(user, "token");

        // Verify save was still called with the partial data from short + long term
        verify(genreExtractionService).extractAndSaveGenrePreferences(
                eq(user), anyList(), eq(GenrePreferenceSource.SPOTIFY_DERIVED));
        assertThat(count).isGreaterThan(0);
    }

    @Test
    @DisplayName("Partial sync succeeds when short_term fails but other ranges return data")
    void partialSync_shortTermFails_savesPartialData() throws JsonProcessingException {
        when(spotifyService.getGenresFromTopArtists(any(), eq(50), eq("short_term")))
                .thenThrow(new RuntimeException("timeout"));
        when(spotifyService.getGenresFromTopArtists(any(), eq(50), eq("medium_term")))
                .thenReturn(List.of("indie"));
        when(spotifyService.getGenresFromTopArtists(any(), eq(50), eq("long_term")))
                .thenReturn(List.of("classical"));

        int count = syncService.syncUserGenrePreferences(user, "token");

        verify(genreExtractionService).extractAndSaveGenrePreferences(
                eq(user), anyList(), eq(GenrePreferenceSource.SPOTIFY_DERIVED));
        assertThat(count).isGreaterThan(0);
    }

    @Test
    @DisplayName("Partial sync: genres from surviving ranges are passed to extraction service")
    void partialSync_capturesCorrectGenres() throws JsonProcessingException {
        when(spotifyService.getGenresFromTopArtists(any(), eq(50), eq("short_term")))
                .thenReturn(List.of("pop"));                         // weight 3 → 3 copies
        when(spotifyService.getGenresFromTopArtists(any(), eq(50), eq("medium_term")))
                .thenThrow(new RuntimeException("503"));             // fails
        when(spotifyService.getGenresFromTopArtists(any(), eq(50), eq("long_term")))
                .thenReturn(List.of("rock"));                        // weight 1 → 1 copy

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> genreCaptor = ArgumentCaptor.forClass(List.class);

        syncService.syncUserGenrePreferences(user, "token");

        verify(genreExtractionService).extractAndSaveGenrePreferences(
                eq(user), genreCaptor.capture(), any());

        List<String> captured = genreCaptor.getValue();
        // short_term weight=3 gives 3× "pop", long_term weight=1 gives 1× "rock"
        assertThat(captured).containsExactlyInAnyOrder("pop", "pop", "pop", "rock");
    }

    // -------------------------------------------------------------------------
    // All ranges fail → SpotifyApiException
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("All ranges fail → SpotifyApiException is thrown")
    void allRangesFail_throwsSpotifyApiException() throws JsonProcessingException {
        RuntimeException networkError = new RuntimeException("connection refused");
        when(spotifyService.getGenresFromTopArtists(any(), anyInt(), anyString()))
                .thenThrow(networkError);

        assertThatThrownBy(() -> syncService.syncUserGenrePreferences(user, "token"))
                .isInstanceOf(SpotifyApiException.class)
                .hasMessageContaining("all time ranges unavailable");
    }

    @Test
    @DisplayName("All ranges fail → extractAndSave is never called")
    void allRangesFail_noSaveAttempted() throws JsonProcessingException {
        when(spotifyService.getGenresFromTopArtists(any(), anyInt(), anyString()))
                .thenThrow(new RuntimeException("Spotify down"));

        assertThatThrownBy(() -> syncService.syncUserGenrePreferences(user, "token"))
                .isInstanceOf(SpotifyApiException.class);

        verify(genreExtractionService, never()).extractAndSaveGenrePreferences(any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // clearSpotifyPreferences is always called first (existing behaviour preserved)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("clearSpotifyPreferences is called even when all ranges fail")
    void clearAlwaysCalledFirst() throws JsonProcessingException {
        when(spotifyService.getGenresFromTopArtists(any(), anyInt(), anyString()))
                .thenThrow(new RuntimeException("503"));

        assertThatThrownBy(() -> syncService.syncUserGenrePreferences(user, "token"))
                .isInstanceOf(SpotifyApiException.class);

        verify(genreExtractionService).clearSpotifyPreferences(user);
    }

    // -------------------------------------------------------------------------
    // Concurrent calls — verifies no shared-state corruption across threads
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Concurrent syncs for different users do not interfere")
    void concurrentSyncs_doNotShareState() throws InterruptedException {
        int threadCount = 10;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        // All ranges succeed for every call
        try {
            when(spotifyService.getGenresFromTopArtists(any(), anyInt(), anyString()))
                    .thenReturn(List.of("pop", "rock"));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        for (int i = 0; i < threadCount; i++) {
            final String userId = "user-" + i;
            pool.submit(() -> {
                try {
                    User u = User.builder().id(userId).build();
                    int result = syncService.syncUserGenrePreferences(u, "token-" + userId);
                    if (result > 0) successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(successCount.get()).isEqualTo(threadCount);
        assertThat(failureCount.get()).isZero();
    }
}
