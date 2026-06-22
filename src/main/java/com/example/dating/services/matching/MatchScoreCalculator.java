package com.example.dating.services.matching;

import com.example.dating.models.matching.dao.UserGenrePreference;
import com.example.dating.models.matching.dto.MatchBreakdown;
import com.example.dating.models.matching.dto.MatchScore;
import com.example.dating.models.matching.dto.SharedGenre;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.UserGenrePreferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for calculating match scores between users based on music preferences
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MatchScoreCalculator {

    private final UserGenrePreferenceRepository preferenceRepository;
    private final GenrePrefetchContext genrePrefetchContext;

    // Scoring weights (totals 100%)
    private static final double GENRE_OVERLAP_WEIGHT = 0.70;  // 70% - shared genres
    private static final double WEIGHT_SIMILARITY_WEIGHT = 0.20;  // 20% - how close weights are
    private static final double DIVERSITY_WEIGHT = 0.10;  // 10% - taste diversity similarity

    /**
     * Calculate comprehensive match score between two users
     *
     * @param userA First user
     * @param userB Second user
     * @return MatchScore with overall score and detailed breakdown
     */
    public MatchScore calculateMatchScore(User userA, User userB) {
        log.info("Calculating match score between user {} and user {}", userA.getId(), userB.getId());

        // Get preferences for both users.
        // When a GenrePrefetchContext is active (batch scoring loop), prefs were bulk-loaded
        // before the loop and are served from the ThreadLocal map — zero extra queries.
        // Outside of a prefetch pass (e.g. single score calculation), falls back to DB.
        List<UserGenrePreference> userAPrefs = genrePrefetchContext.find(userA.getId())
                .orElseGet(() -> preferenceRepository.findByUserIdWithGenreOrderByWeightDesc(userA.getId()));
        List<UserGenrePreference> userBPrefs = genrePrefetchContext.find(userB.getId())
                .orElseGet(() -> preferenceRepository.findByUserIdWithGenreOrderByWeightDesc(userB.getId()));

        // Handle edge cases
        if (userAPrefs.isEmpty() || userBPrefs.isEmpty()) {
            log.warn("One or both users have no preferences. UserA: {}, UserB: {}",
                    userAPrefs.size(), userBPrefs.size());
            return buildZeroScoreMatch(userA.getId(), userB.getId());
        }

        // Build genre maps for quick lookup
        Map<String, UserGenrePreference> userAMap = buildGenreMap(userAPrefs);
        Map<String, UserGenrePreference> userBMap = buildGenreMap(userBPrefs);

        // Calculate shared genres
        List<SharedGenre> sharedGenres = calculateSharedGenres(userAMap, userBMap);

        // Calculate genre-only lists
        List<String> userOnlyGenres = calculateUserOnlyGenres(userAMap, userBMap);
        List<String> otherOnlyGenres = calculateUserOnlyGenres(userBMap, userAMap);

        // Calculate component scores
        double genreOverlapScore = calculateGenreOverlapScore(sharedGenres, userAPrefs.size(), userBPrefs.size());
        double weightSimilarityScore = calculateWeightSimilarityScore(sharedGenres);
        double diversityScore = calculateDiversityScore(userAPrefs.size(), userBPrefs.size());

        // Calculate overall music score (weighted average)
        double musicScore = (genreOverlapScore * GENRE_OVERLAP_WEIGHT) +
                           (weightSimilarityScore * WEIGHT_SIMILARITY_WEIGHT) +
                           (diversityScore * DIVERSITY_WEIGHT);

        // Calculate match confidence based on preference confidence
        double matchConfidence = calculateMatchConfidence(userAPrefs, userBPrefs, sharedGenres);

        // Build breakdown
        MatchBreakdown breakdown = MatchBreakdown.builder()
                .sharedGenres(sharedGenres)
                .userOnlyGenres(userOnlyGenres)
                .otherOnlyGenres(otherOnlyGenres)
                .sharedGenreCount(sharedGenres.size())
                .totalUniqueGenres(userAMap.size() + userBMap.size() - sharedGenres.size())
                .genreOverlapScore(genreOverlapScore)
                .weightSimilarityScore(weightSimilarityScore)
                .diversityScore(diversityScore)
                .matchConfidence(matchConfidence)
                .build();

        // Generate insights
        List<String> insights = generateInsights(sharedGenres, userOnlyGenres, otherOnlyGenres, musicScore);

        // Build final match score
        return MatchScore.builder()
                .userId(userA.getId())
                .otherUserId(userB.getId())
                .overallScore(musicScore)
                .musicScore(musicScore)
                .breakdown(breakdown)
                .insights(insights)
                .compatibilityLevel(MatchScore.CompatibilityLevel.fromScore(musicScore))
                .calculatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * Build a map of genre name -> preference for quick lookup
     */
    private Map<String, UserGenrePreference> buildGenreMap(List<UserGenrePreference> preferences) {
        return preferences.stream()
                .collect(Collectors.toMap(
                        pref -> pref.getGenre().getName(),
                        pref -> pref
                ));
    }

    /**
     * Calculate shared genres between two users
     */
    private List<SharedGenre> calculateSharedGenres(
            Map<String, UserGenrePreference> userAMap,
            Map<String, UserGenrePreference> userBMap) {

        List<SharedGenre> sharedGenres = new ArrayList<>();

        for (Map.Entry<String, UserGenrePreference> entry : userAMap.entrySet()) {
            String genreName = entry.getKey();
            UserGenrePreference userAPref = entry.getValue();

            if (userBMap.containsKey(genreName)) {
                UserGenrePreference userBPref = userBMap.get(genreName);

                // Calculate overlap (minimum of both weights)
                double overlap = Math.min(userAPref.getWeight(), userBPref.getWeight());

                // Calculate similarity (how close the weights are)
                double weightDiff = Math.abs(userAPref.getWeight() - userBPref.getWeight());
                double similarity = 1.0 - weightDiff;

                SharedGenre sharedGenre = SharedGenre.builder()
                        .genre(genreName)
                        .genreDisplayName(userAPref.getGenre().getDisplayName())
                        .userWeight(userAPref.getWeight())
                        .otherWeight(userBPref.getWeight())
                        .overlap(overlap)
                        .similarity(similarity)
                        .build();

                sharedGenres.add(sharedGenre);
            }
        }

        // Sort by overlap (highest first)
        sharedGenres.sort((a, b) -> Double.compare(b.getOverlap(), a.getOverlap()));

        return sharedGenres;
    }

    /**
     * Calculate genres that only one user has
     */
    private List<String> calculateUserOnlyGenres(
            Map<String, UserGenrePreference> userMap,
            Map<String, UserGenrePreference> otherUserMap) {

        return userMap.keySet().stream()
                .filter(genre -> !otherUserMap.containsKey(genre))
                .map(genre -> userMap.get(genre).getGenre().getDisplayName())
                .collect(Collectors.toList());
    }

    /**
     * Calculate genre overlap score (0-100)
     * Based on how many genres overlap and their weights
     */
    private double calculateGenreOverlapScore(
            List<SharedGenre> sharedGenres,
            int userAGenreCount,
            int userBGenreCount) {

        if (sharedGenres.isEmpty()) {
            return 0.0;
        }

        // Calculate weighted overlap sum
        double weightedOverlap = sharedGenres.stream()
                .mapToDouble(SharedGenre::getOverlap)
                .sum();

        // Calculate coverage (what % of genres are shared)
        int totalUniqueGenres = userAGenreCount + userBGenreCount - sharedGenres.size();
        double coverage = (double) sharedGenres.size() / totalUniqueGenres;

        // Combine weighted overlap with coverage
        // More shared genres = higher score
        // Higher weights on shared genres = higher score
        double avgOverlap = weightedOverlap / sharedGenres.size();
        double score = (avgOverlap * 0.7) + (coverage * 0.3);

        return Math.min(100.0, score * 100.0);
    }

    /**
     * Calculate weight similarity score (0-100)
     * How similar are the weights on shared genres?
     */
    private double calculateWeightSimilarityScore(List<SharedGenre> sharedGenres) {
        if (sharedGenres.isEmpty()) {
            return 0.0;
        }

        // Average similarity across all shared genres
        double avgSimilarity = sharedGenres.stream()
                .mapToDouble(SharedGenre::getSimilarity)
                .average()
                .orElse(0.0);

        return avgSimilarity * 100.0;
    }

    /**
     * Calculate diversity score (0-100)
     * Do both users have similar taste diversity?
     * (Similar # of genres = more compatible)
     */
    private double calculateDiversityScore(int userAGenreCount, int userBGenreCount) {
        int max = Math.max(userAGenreCount, userBGenreCount);
        int min = Math.min(userAGenreCount, userBGenreCount);

        if (max == 0) return 0.0;

        // Ratio of smaller to larger
        double ratio = (double) min / max;

        return ratio * 100.0;
    }

    /**
     * Calculate overall match confidence
     * Based on the confidence scores of preferences
     */
    private double calculateMatchConfidence(
            List<UserGenrePreference> userAPrefs,
            List<UserGenrePreference> userBPrefs,
            List<SharedGenre> sharedGenres) {

        if (sharedGenres.isEmpty()) {
            return 0.1;
        }

        // Build confidence maps
        Map<String, Double> userAConfidence = userAPrefs.stream()
                .collect(Collectors.toMap(
                        p -> p.getGenre().getName(),
                        UserGenrePreference::getConfidence
                ));

        Map<String, Double> userBConfidence = userBPrefs.stream()
                .collect(Collectors.toMap(
                        p -> p.getGenre().getName(),
                        UserGenrePreference::getConfidence
                ));

        // Average confidence on shared genres
        double totalConfidence = sharedGenres.stream()
                .mapToDouble(sg -> {
                    double confA = userAConfidence.getOrDefault(sg.getGenre(), 1.0);
                    double confB = userBConfidence.getOrDefault(sg.getGenre(), 1.0);
                    return (confA + confB) / 2.0;
                })
                .average()
                .orElse(0.5);

        return Math.max(0.1, Math.min(1.0, totalConfidence));
    }

    /**
     * Generate human-readable insights about the match
     */
    private List<String> generateInsights(
            List<SharedGenre> sharedGenres,
            List<String> userOnlyGenres,
            List<String> otherOnlyGenres,
            double musicScore) {

        List<String> insights = new ArrayList<>();

        if (sharedGenres.isEmpty()) {
            insights.add("You have different music tastes - could be interesting!");
            return insights;
        }

        // Top shared genre
        SharedGenre topGenre = sharedGenres.get(0);
        if (topGenre.getOverlap() >= 0.7) {
            insights.add(String.format("You both love %s music", topGenre.getGenreDisplayName()));
        } else if (topGenre.getOverlap() >= 0.5) {
            insights.add(String.format("You both enjoy %s", topGenre.getGenreDisplayName()));
        }

        // Strong connections
        long strongConnections = sharedGenres.stream()
                .filter(sg -> sg.getOverlap() >= 0.6)
                .count();

        if (strongConnections >= 3) {
            insights.add(String.format("Strong connection across %d genres", strongConnections));
        } else if (strongConnections >= 2) {
            insights.add("Multiple shared music interests");
        }

        // Weight similarity
        double avgSimilarity = sharedGenres.stream()
                .mapToDouble(SharedGenre::getSimilarity)
                .average()
                .orElse(0.0);

        if (avgSimilarity >= 0.8) {
            insights.add("Very similar taste intensity");
        }

        // Complementary tastes
        if (!userOnlyGenres.isEmpty() && !otherOnlyGenres.isEmpty()) {
            if (musicScore >= 60) {
                insights.add("Great match with complementary tastes");
            } else {
                insights.add("Different but potentially complementary tastes");
            }
        }

        // Diverse vs focused
        int sharedCount = sharedGenres.size();
        if (sharedCount >= 8) {
            insights.add("Both have diverse music taste");
        } else if (sharedCount <= 3) {
            insights.add("Both have focused music preferences");
        }

        return insights;
    }

    /**
     * Build a zero-score match for users with no preferences
     */
    private MatchScore buildZeroScoreMatch(String userId, String otherUserId) {
        return MatchScore.builder()
                .userId(userId)
                .otherUserId(otherUserId)
                .overallScore(0.0)
                .musicScore(0.0)
                .breakdown(MatchBreakdown.builder()
                        .sharedGenres(Collections.emptyList())
                        .userOnlyGenres(Collections.emptyList())
                        .otherOnlyGenres(Collections.emptyList())
                        .sharedGenreCount(0)
                        .totalUniqueGenres(0)
                        .genreOverlapScore(0.0)
                        .weightSimilarityScore(0.0)
                        .diversityScore(0.0)
                        .matchConfidence(0.1)
                        .build())
                .insights(List.of("Add music preferences to see compatibility"))
                .compatibilityLevel(MatchScore.CompatibilityLevel.LOW)
                .calculatedAt(LocalDateTime.now())
                .build();
    }
}
