package com.example.dating.models.matching.dto;

import com.example.dating.enums.matching.MatchSource;
import com.example.dating.enums.matching.MatchStatus;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * Typed presentation DTO for a single match entry.
 * Produced by {@link com.example.dating.mappers.MatchDtoMapper}.
 * Field names intentionally mirror the old {@code Map<String, Object>} response
 * to preserve frontend API compatibility.
 */
@Value
@Builder
public class MatchResponseDto {
    String matchId;
    Double matchScore;
    MatchStatus status;
    Boolean conversationStarted;
    MatchSource matchSource;
    LocalDateTime matchedAt;
    String otherUserId;
    String otherUserName;
    String otherUserPhoto;
}
