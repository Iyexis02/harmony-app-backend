package com.example.dating.services.matching;

import com.example.dating.config.DistributedLockService;
import com.example.dating.exceptions.SpotifyApiException;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.SpotifyService;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Service to sync Spotify listening data to user genre preferences
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpotifyGenreSyncService {

    private final SpotifyService spotifyService;
    private final GenreExtractionService genreExtractionService;
    private final UserJpaRepository userJpaRepository;
    @Qualifier("taskExecutor")
    private final Executor taskExecutor;
    private final DistributedLockService distributedLockService;

    /**
     * Sync genre preferences for a user from their Spotify data
     * Fetches top artists from multiple time ranges and extracts genres
     *
     * @param user The user to sync preferences for
     * @param accessToken User's Spotify access token
     * @return Number of genre preferences created/updated
     */
    @CircuitBreaker(name = "spotify-sync", fallbackMethod = "syncFallback")
    public int syncUserGenrePreferences(User user, String accessToken) throws JsonProcessingException {
        String lockKey = "lock:spotify:sync:" + user.getId();
        String ownerId = distributedLockService.tryLock(lockKey, Duration.ofSeconds(60));
        if (ownerId == null) {
            log.info("Genre sync already in progress for user {}, skipping", user.getId());
            return 0;
        }
        try {
            log.info("Starting genre sync for user {}", user.getId());

            // Phase 1: HTTP calls — no transaction is open at this point.
            // All 3 Spotify calls run in parallel, reducing wall time from ~1.5 s to ~500 ms.
            // No DB connection is held while these futures are running.
            CompletableFuture<List<String>> shortTermFuture = CompletableFuture.supplyAsync(
                    () -> {
                        try { return fetchGenresWithWeight(accessToken, 50, "short_term", 3); }
                        catch (JsonProcessingException e) { throw new RuntimeException(e); }
                    }, taskExecutor);

            CompletableFuture<List<String>> mediumTermFuture = CompletableFuture.supplyAsync(
                    () -> {
                        try { return fetchGenresWithWeight(accessToken, 50, "medium_term", 2); }
                        catch (JsonProcessingException e) { throw new RuntimeException(e); }
                    }, taskExecutor);

            CompletableFuture<List<String>> longTermFuture = CompletableFuture.supplyAsync(
                    () -> {
                        try { return fetchGenresWithWeight(accessToken, 50, "long_term", 1); }
                        catch (JsonProcessingException e) { throw new RuntimeException(e); }
                    }, taskExecutor);

            // Collect results. Each .get() has an independent 15 s safety timeout (beyond the
            // 10 s HTTP read timeout configured on the RestTemplate). Because all three futures
            // were started above, they run concurrently — the sequential .get() calls don't
            // add latency; they just observe whichever futures are already done.
            List<String> allGenres = new ArrayList<>();
            int failedRanges = 0;

            try {
                allGenres.addAll(shortTermFuture.get(15, TimeUnit.SECONDS));
            } catch (Exception e) {
                failedRanges++;
                log.warn("Failed to fetch short_term genres for user {}: {}", user.getId(), e.getMessage());
            }

            try {
                allGenres.addAll(mediumTermFuture.get(15, TimeUnit.SECONDS));
            } catch (Exception e) {
                failedRanges++;
                log.warn("Failed to fetch medium_term genres for user {}: {}", user.getId(), e.getMessage());
            }

            try {
                allGenres.addAll(longTermFuture.get(15, TimeUnit.SECONDS));
            } catch (Exception e) {
                failedRanges++;
                log.warn("Failed to fetch long_term genres for user {}: {}", user.getId(), e.getMessage());
            }

            if (failedRanges == 3) {
                log.error("All time ranges failed for user {}", user.getId());
                throw new SpotifyApiException("Spotify genre sync failed: all time ranges unavailable");
            }

            if (allGenres.isEmpty()) {
                log.warn("No genres found for user {}", user.getId());
                return 0;
            }

            // Phase 2: DB writes — open a fresh transaction only for the write phase.
            // replaceSpotifyPreferences (delete + save) and touchUpdatedAt run together
            // in one transaction inside persistGenreSync. The Spotify HTTP calls above
            // completed without holding any DB connection.
            return genreExtractionService.persistGenreSync(user, allGenres);
        } finally {
            distributedLockService.unlock(lockKey, ownerId);
        }
    }

    /**
     * Fetch genres with weighted duplicates based on time range importance
     *
     * @param weight How many times to duplicate each genre (for weighting)
     */
    private List<String> fetchGenresWithWeight(String accessToken, int limit, String timeRange, int weight) throws JsonProcessingException {
        List<String> genres = spotifyService.getGenresFromTopArtists(accessToken, limit, timeRange);

        // Duplicate genres based on weight to increase their importance
        List<String> weightedGenres = new ArrayList<>();
        for (String genre : genres) {
            for (int i = 0; i < weight; i++) {
                weightedGenres.add(genre);
            }
        }

        return weightedGenres;
    }

    /**
     * Quick sync - only uses short-term data
     * Faster but less comprehensive
     */
    @CircuitBreaker(name = "spotify-sync", fallbackMethod = "syncFallback")
    public int quickSyncUserGenrePreferences(User user, String accessToken) throws JsonProcessingException {
        String lockKey = "lock:spotify:sync:" + user.getId();
        String ownerId = distributedLockService.tryLock(lockKey, Duration.ofSeconds(60));
        if (ownerId == null) {
            log.info("Genre sync already in progress for user {}, skipping", user.getId());
            return 0;
        }
        try {
            log.info("Starting quick genre sync for user {}", user.getId());

            List<String> genres = spotifyService.getGenresFromTopArtists(accessToken, 50, "short_term");

            if (genres.isEmpty()) {
                log.warn("No genres found for user {}", user.getId());
                return 0;
            }

            // Phase 2: DB writes — open a fresh transaction only for the write phase.
            // HTTP call above completed without holding any DB connection.
            return genreExtractionService.persistGenreSync(user, genres);
        } finally {
            distributedLockService.unlock(lockKey, ownerId);
        }
    }

    private int syncFallback(User user, String accessToken, Throwable t) {
        log.warn("Spotify circuit open, skipping genre sync for user {}: {}", user.getId(), t.getMessage());
        return 0;
    }

    /**
     * Sync for all users who have Spotify connected
     * Useful for batch processing or migrations
     */
    public void syncAllUsersWithSpotify() {
        log.info("Starting batch genre sync for all users with Spotify");

        // Note: This would need findAll() method in UserRepository
        log.warn("syncAllUsersWithSpotify not yet implemented - requires UserRepository.findAll()");

        // TODO: Implement after adding findAll() to UserRepository
        /*
        List<User> usersWithSpotify = userRepository.findAll().stream()
                .filter(user -> user.getSpotifyAccessToken() != null && !user.getSpotifyAccessToken().isEmpty())
                .collect(Collectors.toList());

        log.info("Found {} users with Spotify connected", usersWithSpotify.size());

        int successCount = 0;
        int failureCount = 0;

        for (User user : usersWithSpotify) {
            try {
                syncUserGenrePreferences(user, user.getSpotifyAccessToken());
                successCount++;
                log.info("Successfully synced genres for user {} ({}/{})",
                        user.getId(), successCount + failureCount, usersWithSpotify.size());
            } catch (Exception e) {
                failureCount++;
                log.error("Failed to sync genres for user {}: {}", user.getId(), e.getMessage());
            }
        }

        log.info("Batch sync complete: {} succeeded, {} failed", successCount, failureCount);
        */
    }
}
