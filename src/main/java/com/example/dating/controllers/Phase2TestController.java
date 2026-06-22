package com.example.dating.controllers;

import com.example.dating.mappers.UserMapper;
import com.example.dating.models.user.domain.User;
import com.example.dating.models.matching.dao.UserGenrePreference;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.matching.GenreExtractionService;
import com.example.dating.services.matching.GenreWeightCalculator;
import com.example.dating.enums.matching.GenrePreferenceSource;
import com.example.dating.services.matching.SpotifyGenreSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Test endpoints for Phase 2: Genre Extraction
 * WARNING: Remove or secure these endpoints in production!
 */
@Profile("dev")
@RestController
@RequestMapping("/api/test/phase2")
@RequiredArgsConstructor
@Slf4j
public class Phase2TestController {

    private final GenreExtractionService genreExtractionService;
    private final GenreWeightCalculator weightCalculator;
    private final UserJpaRepository userJpaRepository;
    private final UserMapper userMapper;
    private final SpotifyGenreSyncService spotifyGenreSyncService;

    /**
     * Test genre extraction with mock Spotify data
     * POST /api/test/phase2/extract-mock
     */
    @PostMapping("/extract-mock")
    public Map<String, Object> testGenreExtraction(@RequestParam String userId) {
        User user = userJpaRepository.findById(userId).map(userMapper::toDomain)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        // Mock Spotify genres
        List<String> mockGenres = Arrays.asList(
                "rock", "rock", "rock", "rock", "rock",
                "indie rock", "indie rock", "indie rock",
                "alternative rock", "alternative rock",
                "pop", "pop",
                "indie pop",
                "electronic", "electronic",
                "hip hop",
                "jazz"
        );

        // Extract and save
        genreExtractionService.extractAndSaveGenrePreferences(user, mockGenres, GenrePreferenceSource.SEED_DATA);

        // Get saved preferences
        List<UserGenrePreference> preferences = genreExtractionService.getTopGenres(user, 20);

        Map<String, Object> response = new HashMap<>();
        response.put("userId", userId);
        response.put("mockGenresCount", mockGenres.size());
        response.put("uniqueGenres", mockGenres.stream().distinct().count());
        response.put("savedPreferences", preferences.size());
        response.put("preferences", preferences.stream()
                .map(p -> Map.of(
                        "genre", p.getGenre().getDisplayName(),
                        "weight", p.getWeight(),
                        "confidence", p.getConfidence(),
                        "rank", p.getRank(),
                        "source", p.getSource()
                ))
                .toList());

        return response;
    }

    /**
     * Test weight calculator
     * GET /api/test/phase2/calculate-weight
     */
    @GetMapping("/calculate-weight")
    public Map<String, Object> testWeightCalculator(
            @RequestParam int frequency,
            @RequestParam int total
    ) {
        double weight = weightCalculator.calculateWeight(frequency, total);
        double confidence = weightCalculator.calculateConfidence(1, frequency);

        return Map.of(
                "frequency", frequency,
                "total", total,
                "weight", weight,
                "confidence", confidence,
                "percentage", (frequency * 100.0 / total) + "%"
        );
    }

    /**
     * Add manual genre preference for testing
     * POST /api/test/phase2/add-manual
     */
    @PostMapping("/add-manual")
    public Map<String, Object> testAddManualPreference(
            @RequestParam String userId,
            @RequestParam String genreName,
            @RequestParam(defaultValue = "1.0") Double weight
    ) {
        User user = userJpaRepository.findById(userId).map(userMapper::toDomain)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        UserGenrePreference preference = genreExtractionService.addManualPreference(user, genreName, weight);

        return Map.of(
                "success", true,
                "preference", Map.of(
                        "genre", preference.getGenre().getDisplayName(),
                        "weight", preference.getWeight(),
                        "confidence", preference.getConfidence(),
                        "source", preference.getSource()
                )
        );
    }

    /**
     * Get top genres for a user
     * GET /api/test/phase2/top-genres
     */
    @GetMapping("/top-genres")
    public Map<String, Object> getTopGenres(
            @RequestParam String userId,
            @RequestParam(defaultValue = "10") int limit
    ) {
        User user = userJpaRepository.findById(userId).map(userMapper::toDomain)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        List<UserGenrePreference> preferences = genreExtractionService.getTopGenres(user, limit);

        return Map.of(
                "userId", userId,
                "totalPreferences", preferences.size(),
                "topGenres", preferences.stream()
                        .map(p -> Map.of(
                                "rank", p.getRank(),
                                "genre", p.getGenre().getDisplayName(),
                                "weight", p.getWeight(),
                                "confidence", p.getConfidence(),
                                "source", p.getSource()
                        ))
                        .toList()
        );
    }

    /**
     * Clear preferences for a user
     * DELETE /api/test/phase2/clear
     */
    @DeleteMapping("/clear")
    public Map<String, Object> clearPreferences(@RequestParam String userId) {
        User user = userJpaRepository.findById(userId).map(userMapper::toDomain)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        genreExtractionService.clearSpotifyPreferences(user);

        return Map.of(
                "success", true,
                "message", "Preferences cleared for user " + userId
        );
    }

    /**
     * Test Spotify sync (requires user to have Spotify access token)
     * POST /api/test/phase2/sync-spotify
     */
    @PostMapping("/sync-spotify")
    public Map<String, Object> testSpotifySync(
            @RequestParam String userId,
            @RequestParam(defaultValue = "false") boolean quick
    ) {
        try {
            User user = userJpaRepository.findById(userId).map(userMapper::toDomain)
                    .orElseThrow(() -> new RuntimeException("User not found: " + userId));

            if (user.getSpotifyAccessToken() == null || user.getSpotifyAccessToken().isEmpty()) {
                return Map.of(
                        "error", "User does not have Spotify access token",
                        "userId", userId
                );
            }

            int genreCount;
            if (quick) {
                genreCount = spotifyGenreSyncService.quickSyncUserGenrePreferences(user, user.getSpotifyAccessToken());
            } else {
                genreCount = spotifyGenreSyncService.syncUserGenrePreferences(user, user.getSpotifyAccessToken());
            }

            // Get top preferences after sync
            List<UserGenrePreference> preferences = genreExtractionService.getTopGenres(user, 10);

            return Map.of(
                    "success", true,
                    "userId", userId,
                    "genreCount", genreCount,
                    "top10Genres", preferences.stream()
                            .map(p -> p.getGenre().getDisplayName())
                            .toList()
            );
        } catch (Exception e) {
            log.error("Error syncing Spotify genres: {}", e.getMessage());
            return Map.of(
                    "error", "Failed to sync: " + e.getMessage(),
                    "userId", userId
            );
        }
    }

    /**
     * Get Phase 2 statistics
     * GET /api/test/phase2/stats
     */
    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        // Note: UserRepository doesn't have findAll(), so return placeholder stats
        return Map.of(
                "message", "Stats endpoint requires UserRepository.findAll() method",
                "status", "not_implemented"
        );

        // TODO: Implement after adding findAll() to UserRepository
        /*
        List<User> allUsers = userRepository.findAll();

        long usersWithPreferences = allUsers.stream()
                .filter(user -> !genreExtractionService.getTopGenres(user, 1).isEmpty())
                .count();

        long usersWithSpotify = allUsers.stream()
                .filter(user -> user.getSpotifyAccessToken() != null && !user.getSpotifyAccessToken().isEmpty())
                .count();

        return Map.of(
                "totalUsers", allUsers.size(),
                "usersWithGenrePreferences", usersWithPreferences,
                "usersWithSpotifyConnected", usersWithSpotify,
                "coveragePercentage", (usersWithPreferences * 100.0 / Math.max(allUsers.size(), 1)) + "%"
        );
        */
    }
}
