package com.example.dating.services.matching;

import com.example.dating.enums.matching.GenrePreferenceSource;
import com.example.dating.models.user.domain.User;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.matching.dao.CanonicalGenre;
import com.example.dating.models.matching.dao.UserGenrePreference;
import com.example.dating.repositories.CanonicalGenreRepository;
import com.example.dating.repositories.UserGenrePreferenceRepository;
import com.example.dating.repositories.UserJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for extracting and managing user genre preferences from Spotify data
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GenreExtractionService {

    private final CanonicalGenreRepository canonicalGenreRepository;
    private final UserGenrePreferenceRepository userGenrePreferenceRepository;
    private final GenreWeightCalculator weightCalculator;
    private final UserJpaRepository userJpaRepository;

    /**
     * Extract and save genre preferences from Spotify top tracks/artists
     *
     * @param user The user to extract preferences for
     * @param spotifyGenres List of genre strings from Spotify API
     * @param source The source of the data
     */
    @Transactional
    public void extractAndSaveGenrePreferences(User user, List<String> spotifyGenres, GenrePreferenceSource source) {
        log.info("Extracting genre preferences for user {} from {} genres", user.getId(), spotifyGenres.size());

        // Batch E: load all canonical genres once (1 query) instead of up to 3 queries per
        // Spotify genre in the loop below (was up to 600 queries for 200 unique genres).
        List<CanonicalGenre> allGenres = canonicalGenreRepository.findAll();
        Map<String, CanonicalGenre> genresByName = allGenres.stream()
                .collect(Collectors.toMap(g -> g.getName().toLowerCase(), g -> g, (a, b) -> a));
        Map<String, List<CanonicalGenre>> genresByAlias = buildAliasMap(allGenres);

        // Count genre frequency
        Map<String, Integer> genreFrequency = new HashMap<>();
        for (String genre : spotifyGenres) {
            genreFrequency.merge(genre.toLowerCase().trim(), 1, Integer::sum);
        }

        // Map Spotify genres to canonical genres and calculate weights
        List<UserGenrePreference> preferences = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : genreFrequency.entrySet()) {
            String spotifyGenre = entry.getKey();
            int frequency = entry.getValue();

            // In-memory lookup — no DB queries per genre
            List<CanonicalGenre> matchedGenres = findMatchingCanonicalGenres(
                    spotifyGenre, genresByName, genresByAlias, allGenres);

            for (CanonicalGenre canonicalGenre : matchedGenres) {
                double weight = weightCalculator.calculateWeight(frequency, spotifyGenres.size());
                double confidence = weightCalculator.calculateConfidence(matchedGenres.size(), frequency);

                UserGenrePreference preference = findOrCreatePreference(user, canonicalGenre);
                preference.setWeight(weight);
                preference.setConfidence(confidence);
                preference.setSource(source);
                preference.setUpdatedAt(LocalDateTime.now());

                preferences.add(preference);
            }
        }

        // Rank preferences by weight
        preferences.sort((a, b) -> Double.compare(b.getWeight(), a.getWeight()));
        for (int i = 0; i < preferences.size(); i++) {
            preferences.get(i).setRank(i + 1);
        }

        // Save all preferences
        userGenrePreferenceRepository.saveAll(preferences);

        log.info("Saved {} genre preferences for user {}", preferences.size(), user.getId());
    }

    /**
     * Build a lookup map from Spotify alias → list of canonical genres.
     * Used by {@link #findMatchingCanonicalGenres} to avoid per-genre DB queries.
     */
    private Map<String, List<CanonicalGenre>> buildAliasMap(List<CanonicalGenre> allGenres) {
        Map<String, List<CanonicalGenre>> byAlias = new HashMap<>();
        for (CanonicalGenre g : allGenres) {
            if (g.getSpotifyAliases() != null) {
                for (String alias : g.getSpotifyAliases().split(",")) {
                    byAlias.computeIfAbsent(alias.trim().toLowerCase(), k -> new ArrayList<>()).add(g);
                }
            }
        }
        return byAlias;
    }

    /**
     * Create a UserEntity reference from User domain object (for JPA relationships)
     */
    private UserEntity toUserEntityReference(User user) {
        UserEntity userEntity = new UserEntity();
        userEntity.setId(user.getId());
        return userEntity;
    }

    /**
     * Find or create a UserGenrePreference
     */
    private UserGenrePreference findOrCreatePreference(User user, CanonicalGenre genre) {
        UserEntity userEntity = toUserEntityReference(user);
        return userGenrePreferenceRepository
                .findByUserAndGenre(userEntity, genre)
                .orElseGet(() -> {
                    UserGenrePreference pref = new UserGenrePreference();
                    pref.setUser(userEntity);
                    pref.setGenre(genre);
                    pref.setCreatedAt(LocalDateTime.now());
                    return pref;
                });
    }

    /**
     * Find matching canonical genres for a Spotify genre string using pre-loaded in-memory maps.
     * Replaces the previous per-genre DB calls (up to 3 queries per genre) with O(1) map lookups.
     *
     * Matching strategies (in order):
     * 1. Exact canonical name match
     * 2. Spotify alias match
     * 3. In-memory fuzzy search (name/displayName contains the normalized term)
     */
    private List<CanonicalGenre> findMatchingCanonicalGenres(
            String spotifyGenre,
            Map<String, CanonicalGenre> genresByName,
            Map<String, List<CanonicalGenre>> genresByAlias,
            List<CanonicalGenre> allGenres) {

        String normalized = normalizeGenreName(spotifyGenre);
        String lower      = spotifyGenre.toLowerCase().trim();

        // 1. Exact name match
        CanonicalGenre exactMatch = genresByName.get(normalized);
        if (exactMatch != null) {
            return List.of(exactMatch);
        }

        // 2. Spotify alias match
        List<CanonicalGenre> aliasMatches = genresByAlias.get(lower);
        if (aliasMatches != null && !aliasMatches.isEmpty()) {
            return aliasMatches;
        }

        // 3. In-memory fuzzy search (replaces searchByName DB query)
        List<CanonicalGenre> fuzzyMatches = allGenres.stream()
                .filter(g -> g.getName().toLowerCase().contains(normalized)
                        || (g.getDisplayName() != null
                                && g.getDisplayName().toLowerCase().contains(normalized)))
                .limit(2)
                .collect(Collectors.toList());

        if (!fuzzyMatches.isEmpty()) {
            return fuzzyMatches;
        }

        log.warn("No canonical genre found for Spotify genre: {}", spotifyGenre);
        return Collections.emptyList();
    }

    /**
     * Normalize genre name for matching (lowercase, replace spaces with hyphens)
     */
    private String normalizeGenreName(String genre) {
        return genre.toLowerCase()
                .trim()
                .replaceAll("\\s+", "-")
                .replaceAll("[^a-z0-9-]", "");
    }

    /**
     * Get top N genre preferences for a user.
     * Uses JOIN FETCH so that {@code pref.getGenre()} is accessible in callers that have no
     * active transaction (controllers, test endpoints).
     */
    public List<UserGenrePreference> getTopGenres(User user, int limit) {
        return userGenrePreferenceRepository.findTopNByUserIdWithGenre(user.getId(), limit);
    }

    /**
     * Clear all Spotify-derived preferences for a user
     * Useful before re-syncing
     */
    @Transactional
    public void clearSpotifyPreferences(User user) {
        userGenrePreferenceRepository.deleteByUserIdAndSource(user.getId(), GenrePreferenceSource.SPOTIFY_DERIVED);
        log.info("Cleared Spotify preferences for user {}", user.getId());
    }

    /**
     * Atomically replaces all SPOTIFY_DERIVED genre preferences for a user.
     *
     * <p>The delete and save run in a single transaction (REQUIRED propagation joins any
     * outer transaction opened by the caller). If {@code extractAndSaveGenrePreferences}
     * throws, the whole transaction rolls back and the user retains their previous
     * preferences — no partial-delete data loss window.
     *
     * @return number of distinct genres saved
     */
    @Transactional
    public int replaceSpotifyPreferences(User user, List<String> genres) {
        userGenrePreferenceRepository.deleteByUserIdAndSource(
                user.getId(), GenrePreferenceSource.SPOTIFY_DERIVED);
        extractAndSaveGenrePreferences(user, genres, GenrePreferenceSource.SPOTIFY_DERIVED);
        return (int) genres.stream().distinct().count();
    }

    /**
     * Atomically replaces Spotify-derived genre preferences AND invalidates cached match scores
     * for the user — all within a single transaction.
     *
     * <p>This is the write-phase entry point called by {@code SpotifyGenreSyncService} after
     * all HTTP calls to Spotify are complete. Keeping the transaction here (rather than on the
     * sync entry points) ensures the DB connection is not held during the Spotify HTTP calls.
     *
     * @return number of distinct genres saved
     */
    @Transactional
    public int persistGenreSync(User user, List<String> allGenres) {
        int count = replaceSpotifyPreferences(user, allGenres);
        userJpaRepository.touchUpdatedAt(user.getId());
        return count;
    }

    /**
     * Replaces a user's MANUAL_SELECTION genre preferences with the supplied genre tokens.
     *
     * <p><b>Option 2 (server-side onboarding persistence).</b> Called from the onboarding
     * music-preferences step so the client no longer fans out N gated
     * {@code POST /preferences/genres} calls (which the email-verification filter blocks for
     * unverified users). Folding the weighted-record creation into the already-exempt
     * {@code PUT /onboarding/music-preferences} write removes the partial-failure path entirely.
     *
     * <p>Each token is resolved through the same matching pipeline used for Spotify genres
     * ({@link #findMatchingCanonicalGenres}), so it accepts canonical names ({@code "rock"}),
     * display labels ({@code "Rock"}, {@code "Hip Hop"}) and common aliases ({@code "rap"}).
     * Tokens that resolve to no canonical genre are logged and skipped — a stray label must
     * never fail the onboarding step.
     *
     * <p>Delete-then-recreate makes re-submitting the music step idempotent (mirrors
     * {@link #replaceSpotifyPreferences}). Runs in one transaction so a partial failure rolls
     * back to the user's previous manual preferences. The unique constraint
     * {@code uk_user_genre} guarantees at most one preference row per genre, so reusing
     * {@link #findOrCreatePreference} is safe.
     *
     * @return number of distinct canonical genres persisted
     */
    @Transactional
    public int replaceManualPreferences(User user, List<String> genreTokens) {
        userGenrePreferenceRepository.deleteByUserIdAndSource(
                user.getId(), GenrePreferenceSource.MANUAL_SELECTION);

        if (genreTokens == null || genreTokens.isEmpty()) {
            log.info("Onboarding music step: no genre tokens for user {} — cleared manual preferences", user.getId());
            return 0;
        }

        List<CanonicalGenre> allGenres = canonicalGenreRepository.findAll();
        Map<String, CanonicalGenre> genresByName = allGenres.stream()
                .collect(Collectors.toMap(g -> g.getName().toLowerCase(), g -> g, (a, b) -> a));
        Map<String, List<CanonicalGenre>> genresByAlias = buildAliasMap(allGenres);

        // Resolve tokens to distinct canonical genres, preserving the order the user picked them
        // (drives the rank below). LinkedHashMap keyed by genre id de-duplicates fuzzy matches.
        Map<String, CanonicalGenre> resolved = new LinkedHashMap<>();
        for (String token : genreTokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            List<CanonicalGenre> matches =
                    findMatchingCanonicalGenres(token, genresByName, genresByAlias, allGenres);
            if (matches.isEmpty()) {
                log.warn("Onboarding music step: no canonical genre for '{}' (user {}) — skipped",
                        token, user.getId());
                continue;
            }
            for (CanonicalGenre g : matches) {
                resolved.putIfAbsent(g.getId(), g);
            }
        }

        List<UserGenrePreference> preferences = new ArrayList<>();
        int rank = 1;
        for (CanonicalGenre genre : resolved.values()) {
            UserGenrePreference preference = findOrCreatePreference(user, genre);
            preference.setWeight(1.0);          // manual selections are equally weighted
            preference.setConfidence(1.0);      // explicit user choice → high confidence
            preference.setSource(GenrePreferenceSource.MANUAL_SELECTION);
            preference.setRank(rank++);
            preference.setUpdatedAt(LocalDateTime.now());
            preferences.add(preference);
        }

        userGenrePreferenceRepository.saveAll(preferences);
        log.info("Onboarding music step: saved {} manual genre preferences for user {} (from {} tokens)",
                preferences.size(), user.getId(), genreTokens.size());
        return preferences.size();
    }

    /**
     * Add manual genre preference
     */
    @Transactional
    public UserGenrePreference addManualPreference(User user, String genreName, Double weight) {
        CanonicalGenre genre = canonicalGenreRepository.findByName(genreName)
                .orElseThrow(() -> new IllegalArgumentException("Genre not found: " + genreName));

        UserGenrePreference preference = findOrCreatePreference(user, genre);
        preference.setWeight(weight != null ? weight : 1.0);
        preference.setConfidence(1.0); // Manual selections have high confidence
        preference.setSource(GenrePreferenceSource.MANUAL_SELECTION);
        preference.setUpdatedAt(LocalDateTime.now());

        return userGenrePreferenceRepository.save(preference);
    }

    /**
     * Remove a genre preference
     */
    @Transactional
    public void removePreference(User user, String genreName) {
        CanonicalGenre genre = canonicalGenreRepository.findByName(genreName)
                .orElseThrow(() -> new IllegalArgumentException("Genre not found: " + genreName));

        UserEntity userEntity = toUserEntityReference(user);
        Optional<UserGenrePreference> preference = userGenrePreferenceRepository.findByUserAndGenre(userEntity, genre);
        preference.ifPresent(userGenrePreferenceRepository::delete);

        log.info("Removed genre preference {} for user {}", genreName, user.getId());
    }
}
