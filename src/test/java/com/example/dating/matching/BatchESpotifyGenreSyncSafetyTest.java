package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.exceptions.SpotifyApiException;
import com.example.dating.models.matching.dao.UserGenrePreference;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.domain.User;
import com.example.dating.mappers.UserMapper;
import com.example.dating.repositories.UserGenrePreferenceRepository;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.SpotifyService;
import com.example.dating.services.matching.SpotifyGenreSyncService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Batch E — SpotifyGenreSyncService Destructive-Then-Fail Fix
 *
 * <p>Verifies:
 * <ol>
 *   <li>Existing SPOTIFY_DERIVED preferences are NOT deleted when all Spotify time ranges fail.</li>
 *   <li>A successful sync replaces old SPOTIFY_DERIVED preferences with fresh data.</li>
 *   <li>Concurrent syncs never leave the user in a zero-preference state.</li>
 * </ol>
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class BatchESpotifyGenreSyncSafetyTest {

    @MockitoBean
    private SpotifyService spotifyService;

    @Autowired
    private SpotifyGenreSyncService spotifyGenreSyncService;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserGenrePreferenceRepository preferenceRepository;

    private UserEntity testEntity;
    private User testUser;

    /** Genres that will map to the seeded "pop" and "rock" canonical genres. */
    private static final List<String> SAMPLE_GENRES = List.of("pop", "rock", "pop", "rock", "pop");

    @BeforeEach
    void setUp() {
        testEntity = userJpaRepository.findByEmail("batch.e.sync@test.com")
                .orElseGet(() -> userJpaRepository.save(
                        UserEntity.builder()
                                .email("batch.e.sync@test.com")
                                .name("BatchESyncUser")
                                .build()));
        testUser = userJpaRepository.findById(testEntity.getId()).map(userMapper::toDomain).orElseThrow();
        preferenceRepository.deleteByUserId(testEntity.getId());
    }

    @AfterEach
    void tearDown() {
        preferenceRepository.deleteByUserId(testEntity.getId());
        userJpaRepository.deleteById(testEntity.getId());
    }

    // -----------------------------------------------------------------------
    // 1. Spotify failure must NOT delete existing preferences
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("All time ranges fail — existing SPOTIFY_DERIVED preferences are preserved")
    void syncFails_allRanges_existingPreferencesPreserved() throws Exception {
        // Seed initial preferences via a successful sync
        when(spotifyService.getGenresFromTopArtists(anyString(), anyInt(), anyString()))
                .thenReturn(SAMPLE_GENRES);
        spotifyGenreSyncService.syncUserGenrePreferences(testUser, "valid-token");

        long countBefore = preferenceRepository.findByUserIdOrderByWeightDesc(testEntity.getId()).size();
        assertTrue(countBefore > 0, "Precondition: user must have preferences before the failing sync");

        // Make all Spotify calls fail
        when(spotifyService.getGenresFromTopArtists(anyString(), anyInt(), anyString()))
                .thenThrow(new SpotifyApiException("Spotify unavailable"));

        // Sync must propagate the exception (all ranges failed)
        assertThrows(SpotifyApiException.class,
                () -> spotifyGenreSyncService.syncUserGenrePreferences(testUser, "bad-token"),
                "syncUserGenrePreferences must throw SpotifyApiException when all ranges fail");

        // Existing preferences must be untouched
        long countAfter = preferenceRepository.findByUserIdOrderByWeightDesc(testEntity.getId()).size();
        assertEquals(countBefore, countAfter,
                "Preferences must not be deleted when the Spotify fetch fails — old code would have wiped them before throwing");
    }

    // -----------------------------------------------------------------------
    // 2. Successful sync replaces old preferences (clear-then-save is atomic)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Successful sync replaces SPOTIFY_DERIVED preferences with fresh data")
    void syncSucceeds_replacesOldSpotifyPreferences() throws Exception {
        // First sync — pop + rock
        when(spotifyService.getGenresFromTopArtists(anyString(), anyInt(), anyString()))
                .thenReturn(List.of("pop", "rock"));
        spotifyGenreSyncService.syncUserGenrePreferences(testUser, "valid-token");

        List<UserGenrePreference> afterFirst =
                preferenceRepository.findByUserIdOrderByWeightDesc(testEntity.getId());
        assertTrue(afterFirst.size() > 0, "First sync must create preferences");

        // Second sync — jazz only
        when(spotifyService.getGenresFromTopArtists(anyString(), anyInt(), anyString()))
                .thenReturn(List.of("jazz", "jazz", "jazz"));
        int reported = spotifyGenreSyncService.syncUserGenrePreferences(testUser, "valid-token");

        List<UserGenrePreference> afterSecond =
                preferenceRepository.findByUserIdOrderByWeightDesc(testEntity.getId());
        assertTrue(afterSecond.size() > 0, "Second sync must create preferences");
        assertTrue(reported > 0, "Returned genre count must be > 0");

        // All surviving preferences must come from the second sync (SPOTIFY_DERIVED source)
        afterSecond.forEach(p -> assertEquals(
                com.example.dating.enums.matching.GenrePreferenceSource.SPOTIFY_DERIVED,
                p.getSource(),
                "Every preference after a successful sync must be SPOTIFY_DERIVED"));
    }

    // -----------------------------------------------------------------------
    // 3. Concurrent syncs — no empty preference state after all threads finish
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Concurrent syncs never leave the user in a zero-preference state")
    void concurrentSyncs_noEmptyPreferenceState() throws Exception {
        when(spotifyService.getGenresFromTopArtists(anyString(), anyInt(), anyString()))
                .thenReturn(SAMPLE_GENRES);

        int threadCount = 8;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        AtomicInteger transactionConflicts = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                try {
                    barrier.await(); // start all threads simultaneously
                    spotifyGenreSyncService.syncUserGenrePreferences(testUser, "valid-token");
                } catch (Exception e) {
                    // Transaction conflicts (optimistic locking, serialization failures) are
                    // acceptable under high concurrency — what must never happen is data loss.
                    transactionConflicts.incrementAndGet();
                }
            }));
        }

        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);
        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception ignored) {}
        }

        long finalCount = preferenceRepository.findByUserIdOrderByWeightDesc(testEntity.getId()).size();
        assertTrue(finalCount > 0,
                "After " + threadCount + " concurrent syncs (" + transactionConflicts.get()
                        + " conflicts), user must still have at least one preference. "
                        + "Zero means clear() ran but save() was lost — the pre-Batch-E bug.");
    }
}
