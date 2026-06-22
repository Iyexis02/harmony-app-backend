package com.example.dating.models.matching.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of a swipe action
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SwipeResult {

    /**
     * ID of the swipe record
     */
    private String swipeId;

    /**
     * Action taken (like, pass, superlike)
     */
    private String action;

    /**
     * Match score at the time of swipe
     */
    private Double matchScore;

    /**
     * Whether this swipe resulted in a mutual match
     */
    private Boolean resultedInMatch;

    /**
     * Match details (if mutual match occurred)
     */
    private MatchDetails match;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MatchDetails {
        private String matchId;
        private String userId;
        private String name;
        private Double matchScore;
        private String matchedAt;
    }
}
