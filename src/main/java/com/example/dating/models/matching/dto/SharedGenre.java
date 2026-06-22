package com.example.dating.models.matching.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a genre that both users share with their respective weights
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SharedGenre {

    /**
     * Genre name
     */
    private String genre;

    /**
     * Display name of the genre
     */
    private String genreDisplayName;

    /**
     * First user's preference weight for this genre (0-1)
     */
    private Double userWeight;

    /**
     * Second user's preference weight for this genre (0-1)
     */
    private Double otherWeight;

    /**
     * Overlap score for this genre (minimum of both weights)
     */
    private Double overlap;

    /**
     * How similar the weights are (1.0 = identical, 0.0 = very different)
     */
    private Double similarity;
}
