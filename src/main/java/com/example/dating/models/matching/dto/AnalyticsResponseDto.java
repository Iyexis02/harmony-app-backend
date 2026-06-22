package com.example.dating.models.matching.dto;

import lombok.Builder;
import lombok.Value;

/**
 * Typed response DTO for {@code GET /api/v1/matching/analytics}.
 * Field names mirror the old {@code Map<String, Object>} response for frontend compatibility.
 * Computed by {@link com.example.dating.services.matching.SwipeService#getAnalytics(String)}.
 */
@Value
@Builder
public class AnalyticsResponseDto {
    long totalSwipes;
    long totalLikes;
    long totalPasses;
    long totalMatches;
    Double swipeThroughRate;
    Double matchRate;
}
