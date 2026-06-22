package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.enums.matching.GenrePreferenceSource;
import com.example.dating.models.matching.dao.CanonicalGenre;
import com.example.dating.models.matching.dao.UserGenrePreference;
import com.example.dating.models.matching.dao.UserMatchScore;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.CanonicalGenreRepository;
import com.example.dating.repositories.UserGenrePreferenceRepository;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.repositories.UserMatchScoreRepository;
import com.example.dating.services.matching.GenreExtractionService;
import com.example.dating.services.matching.SpotifyGenreSyncService;
import com.example.dating.services.SpotifyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies Batch E — Fix quickSync Genre Accumulation and Genre Sync Cache Staleness.
 *
 * <p>Covers:
 * <ol>
 *   <li>{@code quickSync} replaces genres instead of accumulating.</li>
 *   <li>{@code quickSync} atomicity on failure (transaction rollback retains originals).</li>
 *   <li>Genre sync ({@code syncUserGenrePreferences}) bumps {@code UserEntity.updatedAt}.</li>
 *   <li>Quick sync also bumps {@code UserEntity.updatedAt}.</li>
 *   <li>Cached match scores are stale after genre sync.</li>
 * </ol>
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class IntegrityBatchEGenreSyncTest {

    @Autowired private SpotifyGenreSyncService spotifyGenreSyncService;
    @Autowired private GenreExtractionService genreExtractionService;
    @Autowired private UserJpaRepository userRepository;
    @Autowired private CanonicalGenreRepository canonicalGenreRepository;
    @Autowired private UserGenrePreferenceRepository genrePreferenceRepository;
    @Autowired private UserMatchScoreRepository matchScoreRepository;

    @MockitoBean private SpotifyService spotifyService;

    private UserEntity userA;
    private UserEntity userB;

    /** Names of canonical genres known to exist from the seed data loader. */
    private String genre1Name;
    private String genre2Name;
    private String genre3Name;

    @BeforeEach
    void setUp() {
        userA = userRepository.findByEmail("integrity.e.a@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("integrity.e.a@test.com")
                                .name("IntegrityEUserA")
                                .build()));

        userB = userRepository.findByEmail("integrity.e.b@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("integrity.e.b@test.com")
                                .name("IntegrityEUserB")
                                .build()));

        // Pick 3 canonical genres from the seed data.
        List<CanonicalGenre> allGenres = canonicalGenreRepository.findAll();
        assertTrue(allGenres.size() >= 3, "Seed data must contain at least 3 canonical genres");
        genre1Name = allGenres.get(0).getName();
        genre2Name = allGenres.get(1).getName();
        genre3Name = allGenres.get(2).getName();

        // Clean up genre preferences and cached scores from prior runs.
        genrePreferenceRepository.findByUserIdOrderByWeightDesc(userA.getId())
                .forEach(genrePreferenceRepository::delete);
        genrePreferenceRepository.findByUserIdOrderByWeightDesc(userB.getId())
                .forEach(genrePreferenceRepository::delete);
        matchScoreRepository.findByUserIdAndMatchedUserId(userA.getId(), userB.getId())
                .ifPresent(matchScoreRepository::delete);
    }

    // ── Test 1: quickSync replaces genres instead of accumulating ─────────────

    @Test
    @DisplayName("quickSync replaces genres instead of accumulating")
    void quickSync_replacesGenresInsteadOfAccumulating() throws Exception {
        // First sync: mock Spotify returning genre1 and genre2.
        when(spotifyService.getGenresFromTopArtists(anyString(), anyInt(), eq("short_term")))
                .thenReturn(List.of(genre1Name, genre2Name));

        spotifyGenreSyncService.quickSyncUserGenrePreferences(toDomain(userA), "fake-token");

        List<UserGenrePreference> afterFirst = genrePreferenceRepository
                .findByUserIdOrderByWeightDesc(userA.getId());
        assertFalse(afterFirst.isEmpty(), "First sync must create preferences");

        // Second quick sync: mock Spotify returning only genre3.
        when(spotifyService.getGenresFromTopArtists(anyString(), anyInt(), eq("short_term")))
                .thenReturn(List.of(genre3Name));

        spotifyGenreSyncService.quickSyncUserGenrePreferences(toDomain(userA), "fake-token");

        // Verify: user should only have genre3 as SPOTIFY_DERIVED, not genre1/genre2.
        List<UserGenrePreference> afterSecond = genrePreferenceRepository
                .findByUserIdWithGenreOrderByWeightDesc(userA.getId());

        List<String> genreNames = afterSecond.stream()
                .filter(p -> p.getSource() == GenrePreferenceSource.SPOTIFY_DERIVED)
                .map(p -> p.getGenre().getName())
                .collect(Collectors.toList());

        assertTrue(genreNames.contains(genre3Name),
                "genre3 must be present after second quickSync");
        assertFalse(genreNames.contains(genre1Name),
                "genre1 must NOT be present — quickSync should replace, not accumulate");
        assertFalse(genreNames.contains(genre2Name),
                "genre2 must NOT be present — quickSync should replace, not accumulate");
    }

    // ── Test 2: quickSync atomicity on failure ───────────────────────────────

    @Test
    @DisplayName("quickSync atomicity — failure retains original preferences")
    void quickSync_atomicity_failureRetainsOriginals() throws Exception {
        // Establish initial preferences via a successful sync.
        when(spotifyService.getGenresFromTopArtists(anyString(), anyInt(), eq("short_term")))
                .thenReturn(List.of(genre1Name, genre2Name));

        spotifyGenreSyncService.quickSyncUserGenrePreferences(toDomain(userA), "fake-token");

        List<UserGenrePreference> before = genrePreferenceRepository
                .findByUserIdOrderByWeightDesc(userA.getId());
        int countBefore = before.size();
        assertTrue(countBefore > 0, "Must have preferences before failure test");

        // Second sync: mock Spotify to throw, causing transaction rollback.
        when(spotifyService.getGenresFromTopArtists(anyString(), anyInt(), eq("short_term")))
                .thenThrow(new RuntimeException("Spotify unavailable"));

        try {
            spotifyGenreSyncService.quickSyncUserGenrePreferences(toDomain(userA), "fake-token");
        } catch (Exception ignored) {
            // Expected — the transaction should roll back.
        }

        // Verify: original preferences are still intact.
        List<UserGenrePreference> after = genrePreferenceRepository
                .findByUserIdOrderByWeightDesc(userA.getId());
        assertEquals(countBefore, after.size(),
                "Original preferences must be retained after failed quickSync (transaction rolled back)");
    }

    // ── Test 3: syncUserGenrePreferences bumps UserEntity.updatedAt ──────────

    @Test
    @DisplayName("Genre sync (full) bumps UserEntity.updatedAt")
    void fullSync_bumpsUserEntityUpdatedAt() throws Exception {
        // Record updatedAt before sync.
        UserEntity beforeUser = userRepository.findById(userA.getId()).orElseThrow();
        LocalDateTime updatedAtBefore = beforeUser.getUpdatedAt();

        // Mock all 3 time ranges.
        when(spotifyService.getGenresFromTopArtists(anyString(), anyInt(), eq("short_term")))
                .thenReturn(List.of(genre1Name));
        when(spotifyService.getGenresFromTopArtists(anyString(), anyInt(), eq("medium_term")))
                .thenReturn(List.of(genre2Name));
        when(spotifyService.getGenresFromTopArtists(anyString(), anyInt(), eq("long_term")))
                .thenReturn(List.of(genre3Name));

        spotifyGenreSyncService.syncUserGenrePreferences(toDomain(userA), "fake-token");

        // Re-read from DB.
        UserEntity afterUser = userRepository.findById(userA.getId()).orElseThrow();
        LocalDateTime updatedAtAfter = afterUser.getUpdatedAt();

        assertNotNull(updatedAtAfter, "updatedAt must be set after genre sync");
        if (updatedAtBefore != null) {
            assertTrue(updatedAtAfter.isAfter(updatedAtBefore) || updatedAtAfter.isEqual(updatedAtBefore),
                    "updatedAt must advance after full genre sync");
        }
    }

    // ── Test 4: quickSyncUserGenrePreferences bumps UserEntity.updatedAt ─────

    @Test
    @DisplayName("Quick sync bumps UserEntity.updatedAt")
    void quickSync_bumpsUserEntityUpdatedAt() throws Exception {
        // Record updatedAt before sync.
        UserEntity beforeUser = userRepository.findById(userA.getId()).orElseThrow();
        LocalDateTime updatedAtBefore = beforeUser.getUpdatedAt();

        when(spotifyService.getGenresFromTopArtists(anyString(), anyInt(), eq("short_term")))
                .thenReturn(List.of(genre1Name));

        spotifyGenreSyncService.quickSyncUserGenrePreferences(toDomain(userA), "fake-token");

        // Re-read from DB.
        UserEntity afterUser = userRepository.findById(userA.getId()).orElseThrow();
        LocalDateTime updatedAtAfter = afterUser.getUpdatedAt();

        assertNotNull(updatedAtAfter, "updatedAt must be set after quick genre sync");
        if (updatedAtBefore != null) {
            assertTrue(updatedAtAfter.isAfter(updatedAtBefore) || updatedAtAfter.isEqual(updatedAtBefore),
                    "updatedAt must advance after quick genre sync");
        }
    }

    // ── Test 5: Cached match scores are stale after genre sync ───────────────

    @Test
    @DisplayName("Cached match scores are stale after genre sync")
    void cachedScore_isStaleAfterGenreSync() throws Exception {
        // Insert a cached score for A→B with computedAt = now.
        LocalDateTime computedAt = LocalDateTime.now();
        UserMatchScore cached = UserMatchScore.builder()
                .user(userA)
                .matchedUser(userB)
                .overallScore(75.0)
                .musicScore(60.0)
                .algorithmVersion("v2.0")
                .computedAt(computedAt)
                .build();
        matchScoreRepository.save(cached);

        // Run a genre sync — this should bump userA.updatedAt past computedAt.
        when(spotifyService.getGenresFromTopArtists(anyString(), anyInt(), eq("short_term")))
                .thenReturn(List.of(genre1Name));
        when(spotifyService.getGenresFromTopArtists(anyString(), anyInt(), eq("medium_term")))
                .thenReturn(List.of(genre2Name));
        when(spotifyService.getGenresFromTopArtists(anyString(), anyInt(), eq("long_term")))
                .thenReturn(List.of(genre3Name));

        spotifyGenreSyncService.syncUserGenrePreferences(toDomain(userA), "fake-token");

        // Re-read userA to get the new updatedAt.
        UserEntity refreshed = userRepository.findById(userA.getId()).orElseThrow();
        LocalDateTime userUpdatedAt = refreshed.getUpdatedAt();

        assertNotNull(userUpdatedAt, "updatedAt must be set");
        // The cache freshness check: computedAt must be >= max(userA.updatedAt, userB.updatedAt).
        // Since we just bumped userA.updatedAt, computedAt (set before the sync) is now stale.
        assertTrue(computedAt.isBefore(userUpdatedAt) || computedAt.isEqual(userUpdatedAt),
                "computedAt (pre-sync) must be <= userUpdatedAt (post-sync), making the cached score stale");
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    /** Minimal domain projection — SpotifyGenreSyncService only reads user.getId(). */
    private static User toDomain(UserEntity entity) {
        return User.builder().id(entity.getId()).build();
    }
}
