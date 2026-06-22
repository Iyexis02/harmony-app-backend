package com.example.dating.models.matching.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Typed page wrapper for {@code GET /api/v1/matching/matches}.
 * Field names mirror the old {@code Map<String, Object>} response for frontend compatibility.
 */
@Value
@Builder
public class MatchPageResponseDto {
    List<MatchResponseDto> matches;
    long total;
    int limit;
    int offset;
    boolean hasMore;
}
