package com.example.dating.models.matching.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Represents a potential match for recommendation
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PotentialMatch {

    /**
     * User ID of the potential match
     */
    private String userId;

    /**
     * User's name
     */
    private String name;

    /**
     * User's age
     */
    private Integer age;

    /**
     * Match score (0-100)
     */
    private Double matchScore;

    /**
     * Top 3 shared genres
     */
    private List<String> topSharedGenres;

    /**
     * Preview insight (one-liner)
     */
    private String previewInsight;

    /**
     * Distance in kilometers (optional)
     */
    private Double distance;

    /**
     * User's photo URLs
     */
    private List<String> photos;

    /**
     * Number of shared genres
     */
    private Integer sharedGenreCount;

    /**
     * Compatibility level
     */
    private MatchScore.CompatibilityLevel compatibilityLevel;
}
