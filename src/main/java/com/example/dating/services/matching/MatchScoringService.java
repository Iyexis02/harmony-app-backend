package com.example.dating.services.matching;

import com.example.dating.mappers.UserMapper;
import com.example.dating.models.matching.dao.UserBehavioralProfile;
import com.example.dating.models.matching.dao.UserMatchScore;
import com.example.dating.models.matching.dto.MatchBreakdown;
import com.example.dating.models.matching.dto.MatchScore;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.dating.dao.UserDatingPreferences;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.UserBehavioralProfileRepository;
import com.example.dating.repositories.UserMatchScoreRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.UUID;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Orchestrates multi-dimensional match scoring between two users.
 * Combines music, lifestyle, interests, location and (when available) behavioural dimensions.
 * Results are cached in user_match_scores with algorithm version "v2.0".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MatchScoringService {

    private final MatchScoreCalculator matchScoreCalculator;
    private final LifestyleScoreCalculator lifestyleScoreCalculator;
    private final InterestsScoreCalculator interestsScoreCalculator;
    private final UserMatchScoreRepository userMatchScoreRepository;
    private final UserMapper userMapper;
    private final UserBehavioralProfileRepository behavioralProfileRepository;
    private final ObjectMapper objectMapper;

    // Lazy to avoid circular dependency with BehavioralScoreCalculator if any
    @Autowired
    @Lazy
    private BehavioralScoreCalculator behavioralScoreCalculator;

    private static final String ALGORITHM_VERSION = "v2.0";
    private static final double EARTH_RADIUS_KM = 6371.0;

    /**
     * Calculate a fully-dimensional match score between userA (the viewing user) and userB (candidate).
     * Score is directional: userA's preferences drive the weighting.
     *
     * <p><b>Batch C — no transaction on this method.</b>  The previous
     * {@code REQUIRES_NEW} was removed to eliminate the two-connection-per-request
     * pattern that caused connection-pool exhaustion under concurrent load.
     * Cache writes are now handled by the caller via {@link #persistScoreCache},
     * which opens its own short write transaction per cache miss.
     *
     * <p>Each repository call inside this method ({@code findByUserIdAndMatchedUserId},
     * {@code findByUserId}) runs in its own short auto-transaction via Spring Data JPA's
     * default {@code @Transactional(readOnly=true)}.  No long-held connection is involved.
     */
    public MatchScore calculateScore(UserEntity userA, UserEntity userB) {
        log.debug("Calculating multi-dim score: {} → {}", userA.getId(), userB.getId());

        // --- Step 1: Cache check ---
        Optional<UserMatchScore> cached = userMatchScoreRepository
                .findByUserIdAndMatchedUserId(userA.getId(), userB.getId());

        LocalDateTime staleAfter = Stream.of(userA.getUpdatedAt(), userB.getUpdatedAt())
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);

        if (cached.isPresent() && ALGORITHM_VERSION.equals(cached.get().getAlgorithmVersion())) {
            UserMatchScore c = cached.get();
            if (staleAfter == null || c.getComputedAt().isAfter(staleAfter)) {
                log.debug("Cache hit for {} → {}", userA.getId(), userB.getId());
                return buildMatchScoreFromCache(c, userA.getId(), userB.getId());
            }
        }

        // --- Step 2: Compute all dimensions ---
        User domainA = userMapper.toDomain(userA);
        User domainB = userMapper.toDomain(userB);

        MatchScore musicResult  = matchScoreCalculator.calculateMatchScore(domainA, domainB);
        double lifestyleScore   = lifestyleScoreCalculator.calculate(userA, userB);
        double interestsScore   = interestsScoreCalculator.calculate(userA, userB);
        double locationScore    = calculateLocationScore(userA, userB);
        List<String> sharedInterests = interestsScoreCalculator.getSharedInterests(userA, userB);

        // --- Step 3: Dynamic weighting via musicMatchImportance ---
        int importance = Optional.ofNullable(userA.getDatingPreferences())
                .map(UserDatingPreferences::getMusicMatchImportance)
                .orElse(70);

        double musicWeight     = 0.30 + (importance / 100.0) * 0.50; // 0.30 – 0.80
        double remaining       = 1.0 - musicWeight;
        double lifestyleWeight = remaining * 0.45;
        double interestsWeight = remaining * 0.30;
        double locationWeight  = remaining * 0.25;

        double profileScore = (musicResult.getMusicScore()  * musicWeight)
                + (lifestyleScore                           * lifestyleWeight)
                + (interestsScore                           * interestsWeight)
                + (locationScore                            * locationWeight);

        // --- Step 3b: Behavioural blend ---
        double finalScore      = profileScore;
        double behavioralScore = 50.0;

        Optional<UserBehavioralProfile> profileOpt =
                behavioralProfileRepository.findByUserId(userA.getId());

        if (profileOpt.isPresent()) {
            UserBehavioralProfile profile = profileOpt.get();
            behavioralScore = behavioralScoreCalculator.calculate(userB, profile);
            double behavioralWeight = profile.getConfidenceLevel() * 0.40; // max 40 %
            double profileWeight    = 1.0 - behavioralWeight;
            finalScore = (profileScore * profileWeight) + (behavioralScore * behavioralWeight);
            log.debug("Behavioural blend — bScore={}, bWeight={}, finalScore={}",
                    behavioralScore, behavioralWeight, finalScore);
        }

        // --- Step 4: Return enriched MatchScore ---
        // Cache persistence is now the caller's responsibility via persistScoreCache().
        List<String> insights = new ArrayList<>(musicResult.getInsights());
        if (lifestyleScore >= 70) {
            insights.add("Great lifestyle compatibility");
        } else if (lifestyleScore < 40) {
            insights.add("Different lifestyle values");
        }
        if (!sharedInterests.isEmpty()) {
            insights.add("Shared interests: " +
                    String.join(", ", sharedInterests.stream().limit(3).toList()));
        }

        MatchBreakdown enrichedBreakdown = copyBreakdownWithExtras(
                musicResult.getBreakdown(), sharedInterests, lifestyleScore);

        return MatchScore.builder()
                .userId(userA.getId())
                .otherUserId(userB.getId())
                .overallScore(finalScore)
                .musicScore(musicResult.getMusicScore())
                .lifestyleScore(lifestyleScore)
                .interestsScore(interestsScore)
                .locationScore(locationScore)
                .behavioralScore(behavioralScore)
                .breakdown(enrichedBreakdown)
                .insights(insights)
                .compatibilityLevel(MatchScore.CompatibilityLevel.fromScore(finalScore))
                .calculatedAt(LocalDateTime.now())
                .build();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private double calculateLocationScore(UserEntity userA, UserEntity userB) {
        if (userA.getLocationLat() == null || userA.getLocationLon() == null
                || userB.getLocationLat() == null || userB.getLocationLon() == null) {
            return 50.0;
        }

        double distKm = haversineKm(
                userA.getLocationLat().doubleValue(), userA.getLocationLon().doubleValue(),
                userB.getLocationLat().doubleValue(), userB.getLocationLon().doubleValue());

        double maxDistA = Optional.ofNullable(userA.getDatingPreferences())
                .map(UserDatingPreferences::getMaxDistanceKm)
                .map(Integer::doubleValue)
                .orElse(100.0);
        double maxDistB = Optional.ofNullable(userB.getDatingPreferences())
                .map(UserDatingPreferences::getMaxDistanceKm)
                .map(Integer::doubleValue)
                .orElse(100.0);

        double effectiveMax = Math.min(maxDistA, maxDistB);
        double score = (1.0 - distKm / effectiveMax) * 100.0;
        return Math.max(0.0, Math.min(100.0, score));
    }

    /**
     * Haversine formula — shared with MatchRecommendationService.
     */
    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }

    private MatchBreakdown copyBreakdownWithExtras(
            MatchBreakdown source, List<String> sharedInterests, double lifestyleScore) {
        if (source == null) {
            return MatchBreakdown.builder()
                    .sharedInterests(sharedInterests)
                    .lifestyleCompatibilityScore(lifestyleScore)
                    .build();
        }
        return MatchBreakdown.builder()
                .sharedGenres(source.getSharedGenres())
                .userOnlyGenres(source.getUserOnlyGenres())
                .otherOnlyGenres(source.getOtherOnlyGenres())
                .sharedGenreCount(source.getSharedGenreCount())
                .totalUniqueGenres(source.getTotalUniqueGenres())
                .genreOverlapScore(source.getGenreOverlapScore())
                .weightSimilarityScore(source.getWeightSimilarityScore())
                .diversityScore(source.getDiversityScore())
                .matchConfidence(source.getMatchConfidence())
                .sharedInterests(sharedInterests)
                .lifestyleCompatibilityScore(lifestyleScore)
                .build();
    }

    MatchScore buildMatchScoreFromCache(UserMatchScore c, String userId, String otherUserId) {
        MatchBreakdown breakdown = fromJson(c.getBreakdownJson(), MatchBreakdown.class);
        List<String> insights    = fromJson(c.getInsightsJson(), new TypeReference<List<String>>() {});
        return MatchScore.builder()
                .userId(userId)
                .otherUserId(otherUserId)
                .overallScore(c.getOverallScore())
                .musicScore(c.getMusicScore())
                .lifestyleScore(c.getLifestyleScore())
                .interestsScore(c.getInterestsScore())
                .locationScore(c.getLocationScore())
                .behavioralScore(c.getBehavioralScore())
                .breakdown(breakdown)
                .insights(insights)
                .compatibilityLevel(MatchScore.CompatibilityLevel.fromScore(c.getOverallScore()))
                .calculatedAt(c.getComputedAt())
                .build();
    }

    private <T> T fromJson(String json, Class<T> type) {
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("Failed to deserialize score cache field from JSON: {}", e.getMessage());
            return null;
        }
    }

    private <T> T fromJson(String json, TypeReference<T> typeRef) {
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (Exception e) {
            log.warn("Failed to deserialize score cache field from JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Persist a computed score to the cache in its own short write transaction.
     * Called per cache-miss from {@link com.example.dating.services.matching.MatchRecommendationService}
     * after scoring completes outside any outer transaction.
     * The transaction commits immediately after the upsert, releasing the connection.
     */
    @Transactional
    public void persistScoreCache(UserEntity userA, UserEntity userB, MatchScore score) {
        String breakdownJson = toJson(score.getBreakdown());
        String insightsJson  = toJson(score.getInsights());
        upsertMatchScoreCache(
                userA, userB,
                score.getMusicScore()     != null ? score.getMusicScore()     : 0.0,
                score.getLifestyleScore() != null ? score.getLifestyleScore() : 0.0,
                score.getInterestsScore() != null ? score.getInterestsScore() : 0.0,
                score.getLocationScore()  != null ? score.getLocationScore()  : 0.0,
                score.getBehavioralScore()!= null ? score.getBehavioralScore(): 50.0,
                score.getOverallScore()   != null ? score.getOverallScore()   : 0.0,
                breakdownJson, insightsJson);
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize score cache field to JSON: {}", e.getMessage());
            return null;
        }
    }

    private void upsertMatchScoreCache(
            UserEntity userA, UserEntity userB,
            double musicScore, double lifestyleScore,
            double interestsScore, double locationScore,
            double behavioralScore, double overallScore,
            String breakdownJson, String insightsJson) {
        try {
            // Single atomic INSERT … ON CONFLICT DO UPDATE — no separate read needed.
            // Safe under concurrent access: PostgreSQL serialises conflicting writers
            // via the uk_user_match unique constraint.
            userMatchScoreRepository.upsertScore(
                    UUID.randomUUID().toString(),
                    userA.getId(), userB.getId(),
                    musicScore, lifestyleScore, interestsScore,
                    locationScore, behavioralScore, overallScore,
                    ALGORITHM_VERSION, LocalDateTime.now(),
                    breakdownJson, insightsJson);
        } catch (Exception e) {
            // Unexpected failure (e.g. transient DB error). Score was computed correctly;
            // a missing cache entry just means the next request recomputes instead of
            // serving from cache — not a user-visible error. Log at ERROR so it is
            // visible in monitoring and does not get lost the way a WARN might.
            log.error("Failed to persist match score cache for {} → {}: {}",
                    userA.getId(), userB.getId(), e.getMessage(), e);
        }
    }
}
