package com.example.dating.models.matching.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Match score between two users with detailed breakdown
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatchScore {

    private String userId;
    private String otherUserId;

    /**
     * Overall compatibility score (0-100)
     */
    private Double overallScore;

    /**
     * Music-specific compatibility (0-100)
     */
    private Double musicScore;

    /**
     * Lifestyle compatibility score (0-100): relationship goals, kids, smoking, drinking
     */
    private Double lifestyleScore;

    /**
     * Shared interests compatibility score (0-100): Jaccard similarity on interest tags
     */
    private Double interestsScore;

    /**
     * Location proximity score (0-100): distance relative to max preferred distance
     */
    private Double locationScore;

    /**
     * Behavioural learning score (0-100): cosine similarity to swiped-right genre centroid.
     * Null until enough swipe history is available (cold start).
     */
    private Double behavioralScore;

    /**
     * Detailed breakdown of the match
     */
    private MatchBreakdown breakdown;

    /**
     * Human-readable insights about the match
     */
    private List<String> insights;

    /**
     * Compatibility level category
     */
    private CompatibilityLevel compatibilityLevel;

    /**
     * When this score was calculated
     */
    private LocalDateTime calculatedAt;

    /**
     * Compatibility level enum
     */
    public enum CompatibilityLevel {
        LOW(0, 40, "Low Match"),
        MEDIUM(41, 60, "Medium Match"),
        HIGH(61, 80, "High Match"),
        VERY_HIGH(81, 100, "Very High Match");

        private final int minScore;
        private final int maxScore;
        private final String displayName;

        CompatibilityLevel(int minScore, int maxScore, String displayName) {
            this.minScore = minScore;
            this.maxScore = maxScore;
            this.displayName = displayName;
        }

        public static CompatibilityLevel fromScore(double score) {
            if (score <= 40) return LOW;
            if (score <= 60) return MEDIUM;
            if (score <= 80) return HIGH;
            return VERY_HIGH;
        }

        public String getDisplayName() {
            return displayName;
        }
    }
}
