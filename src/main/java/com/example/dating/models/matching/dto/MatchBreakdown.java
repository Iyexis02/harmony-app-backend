package com.example.dating.models.matching.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Detailed breakdown of match score components
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatchBreakdown {

    /**
     * Genres that both users enjoy
     */
    private List<SharedGenre> sharedGenres;

    /**
     * Genres only the first user has
     */
    private List<String> userOnlyGenres;

    /**
     * Genres only the second user has
     */
    private List<String> otherOnlyGenres;

    /**
     * Number of shared genres
     */
    private Integer sharedGenreCount;

    /**
     * Number of total unique genres between both users
     */
    private Integer totalUniqueGenres;

    /**
     * Genre overlap score (0-100)
     */
    private Double genreOverlapScore;

    /**
     * Weight similarity score (0-100)
     */
    private Double weightSimilarityScore;

    /**
     * Diversity match score (0-100)
     */
    private Double diversityScore;

    /**
     * Confidence in this match (based on preference confidence)
     */
    private Double matchConfidence;

    /**
     * Shared interest tags (e.g. ["hiking", "cooking"]) for breakdown UI
     */
    private List<String> sharedInterests;

    /**
     * Lifestyle compatibility score mirrored here for breakdown UI
     */
    private Double lifestyleCompatibilityScore;
}
