package com.example.dating.services.matching;

import com.example.dating.models.matching.dao.UserBehavioralProfile;
import com.example.dating.models.matching.dao.UserGenrePreference;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.repositories.UserGenrePreferenceRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Calculates a behavioural compatibility score between a candidate and the current user's
 * learned genre centroid using cosine similarity.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BehavioralScoreCalculator {

    private final UserGenrePreferenceRepository genrePreferenceRepository;
    private final ObjectMapper objectMapper;
    private final GenrePrefetchContext genrePrefetchContext;

    private static final TypeReference<Map<String, Double>> MAP_TYPE = new TypeReference<>() {};

    /** Minimum likes required before the behavioural component is activated. */
    private static final int MIN_LIKES_FOR_ACTIVATION = 5;

    /**
     * Cache for deserialized learnedGenreWeights maps.
     * Key: profileId only — explicit invalidation via {@link #invalidateCache(String)} after
     * each profile update keeps the entry fresh without creating an orphaned entry for every
     * {@code lastUpdatedAt} timestamp change.
     * Avoids repeated JSON deserialization for the same profile during a feed-scoring pass.
     */
    private final Cache<String, Map<String, Double>> genreWeightCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();

    /**
     * Calculate cosine similarity between the candidate's genre profile and the user's learned centroid.
     *
     * @param candidate candidate user entity
     * @param profile   the viewing user's behavioural profile
     * @return score 0-100 (50 = cold-start neutral)
     */
    public double calculate(UserEntity candidate, UserBehavioralProfile profile) {
        if (profile == null || profile.getTotalLikes() < MIN_LIKES_FOR_ACTIVATION) {
            return 50.0; // cold start — insufficient data
        }

        Map<String, Double> learned = resolveGenreWeights(profile);
        if (learned.isEmpty()) {
            return 50.0;
        }

        // Build candidate genre weight map.
        // Served from GenrePrefetchContext when a batch scoring pass is active — no extra query.
        // Falls back to a direct JOIN FETCH query outside a prefetch pass.
        List<UserGenrePreference> candidatePrefs = genrePrefetchContext.find(candidate.getId())
                .orElseGet(() -> genrePreferenceRepository.findByUserIdWithGenreOrderByWeightDesc(candidate.getId()));

        Map<String, Double> candidateWeights = new HashMap<>();
        for (UserGenrePreference pref : candidatePrefs) {
            candidateWeights.put(pref.getGenre().getName(), pref.getWeight());
        }

        // Cosine similarity
        double dot = 0.0;
        double magA = 0.0;

        for (Map.Entry<String, Double> entry : learned.entrySet()) {
            double a = entry.getValue();
            double b = candidateWeights.getOrDefault(entry.getKey(), 0.0);
            dot  += a * b;
            magA += a * a;
        }

        double magB = 0.0;
        for (double v : candidateWeights.values()) {
            magB += v * v;
        }

        if (magA == 0 || magB == 0) {
            return 50.0;
        }

        double cosine = dot / (Math.sqrt(magA) * Math.sqrt(magB));
        double score  = cosine * 100.0;

        log.debug("Behavioural cosine score for candidate {}: {}", candidate.getId(), score);
        return Math.max(0.0, Math.min(100.0, score));
    }

    /**
     * Explicitly removes the cached genre-weight map for the given profile.
     *
     * <p>Must be called by {@code BehavioralProfileService} after saving an updated profile
     * so that the next scoring pass re-deserializes the fresh JSON rather than returning
     * a stale cached value.
     *
     * @param profileId the {@link UserBehavioralProfile#getId()} of the updated profile;
     *                  null-safe (no-op when {@code profileId} is null)
     */
    public void invalidateCache(String profileId) {
        if (profileId != null) {
            genreWeightCache.invalidate(profileId);
        }
    }

    /**
     * Returns the deserialized learnedGenreWeights for the given profile, using the
     * Caffeine cache to skip JSON deserialization when the profile hasn't changed.
     * The cache key is {@code profileId} only; the entry is explicitly invalidated
     * by {@link #invalidateCache(String)} after each profile save.
     */
    private Map<String, Double> resolveGenreWeights(UserBehavioralProfile profile) {
        if (profile.getId() != null) {
            Map<String, Double> cached = genreWeightCache.getIfPresent(profile.getId());
            if (cached != null) {
                return cached;
            }
            Map<String, Double> deserialized = deserializeMap(profile.getLearnedGenreWeights());
            genreWeightCache.put(profile.getId(), deserialized);
            return deserialized;
        }
        return deserializeMap(profile.getLearnedGenreWeights());
    }

    private Map<String, Double> deserializeMap(String json) {
        if (json == null || json.isBlank()) return new HashMap<>();
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            log.warn("Failed to deserialize behavioural genre map: {}", e.getMessage());
            return new HashMap<>();
        }
    }
}
