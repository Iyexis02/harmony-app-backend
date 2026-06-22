package com.example.dating.services.matching;

import org.springframework.stereotype.Component;

/**
 * Calculates weights and confidence scores for genre preferences
 */
@Component
public class GenreWeightCalculator {

    /**
     * Calculate weight for a genre based on its frequency
     *
     * Uses logarithmic scaling to prevent over-weighting of very frequent genres
     *
     * @param frequency How many times this genre appeared
     * @param totalGenres Total number of genre occurrences
     * @return Weight between 0.0 and 1.0
     */
    public double calculateWeight(int frequency, int totalGenres) {
        if (totalGenres == 0) {
            return 0.0;
        }

        // Calculate raw frequency ratio
        double rawRatio = (double) frequency / totalGenres;

        // Apply logarithmic scaling to compress the range
        // This prevents a genre that appears 100 times from being 100x more important
        // than one that appears once
        double logScaled = Math.log1p(frequency) / Math.log1p(totalGenres);

        // Blend raw ratio with log-scaled value (70% log, 30% raw)
        // This keeps some distinction between frequencies while preventing extreme values
        double weight = (0.7 * logScaled) + (0.3 * rawRatio);

        // Ensure weight is between 0 and 1
        return Math.min(1.0, Math.max(0.0, weight));
    }

    /**
     * Calculate confidence score for a genre match
     *
     * Lower confidence when:
     * - Multiple canonical genres match a single Spotify genre (ambiguous)
     * - Genre appears very infrequently (might be noise)
     *
     * @param matchCount How many canonical genres matched this Spotify genre
     * @param frequency How many times this genre appeared
     * @return Confidence between 0.0 and 1.0
     */
    public double calculateConfidence(int matchCount, int frequency) {
        // Start with base confidence
        double confidence = 1.0;

        // Reduce confidence for ambiguous matches
        if (matchCount > 1) {
            // Each additional match reduces confidence
            confidence *= (1.0 / matchCount);
        }

        // Reduce confidence for very low frequency (likely noise)
        if (frequency == 1) {
            confidence *= 0.7; // Single occurrence is less confident
        } else if (frequency == 2) {
            confidence *= 0.85; // Two occurrences slightly better
        }

        return Math.min(1.0, Math.max(0.1, confidence)); // Min 0.1, max 1.0
    }

    /**
     * Calculate recency weight for time-based scoring
     * More recent activity has higher weight
     *
     * @param daysAgo How many days ago this data was from
     * @param maxDays Maximum days to consider (e.g., 180 for 6 months)
     * @return Recency multiplier between 0.5 and 1.0
     */
    public double calculateRecencyWeight(int daysAgo, int maxDays) {
        if (daysAgo < 0) {
            return 1.0;
        }

        if (daysAgo >= maxDays) {
            return 0.5; // Minimum weight for very old data
        }

        // Linear decay from 1.0 (today) to 0.5 (maxDays ago)
        double decay = 1.0 - (0.5 * ((double) daysAgo / maxDays));

        return Math.max(0.5, Math.min(1.0, decay));
    }

    /**
     * Combine multiple weights into a final score
     *
     * @param baseWeight The base genre weight
     * @param confidence The confidence score
     * @param recencyWeight The recency multiplier (optional, use 1.0 if not applicable)
     * @return Combined score between 0.0 and 1.0
     */
    public double combineWeights(double baseWeight, double confidence, double recencyWeight) {
        // Multiply all factors together
        double combined = baseWeight * confidence * recencyWeight;

        return Math.min(1.0, Math.max(0.0, combined));
    }
}
