package com.example.dating.mappers;

import com.example.dating.models.matching.dao.Match;
import com.example.dating.models.matching.dto.MatchResponseDto;
import com.example.dating.models.user.common.dao.UserEntity;
import org.springframework.stereotype.Component;

/**
 * Maps a {@link Match} entity to a {@link MatchResponseDto}.
 * Extracted from {@code MatchingController.buildMatchDTO()} as part of Batch I cleanup.
 */
@Component
public class MatchDtoMapper {

    /**
     * Build a {@link MatchResponseDto} from the perspective of {@code currentUserId}.
     * The "other user" is whichever participant is not {@code currentUserId}.
     *
     * @param match         persisted match entity (userA and userB must be accessible)
     * @param currentUserId ID of the authenticated user making the request
     * @return typed response DTO
     */
    public MatchResponseDto toDto(Match match, String currentUserId) {
        boolean isUserA = match.getUserA().getId().equals(currentUserId);
        UserEntity other = isUserA ? match.getUserB() : match.getUserA();

        // Integrity Batch H: return the score from the requesting user's perspective.
        // matchScore = A→B direction, matchScoreB = B→A direction.
        // Master Batch J: when matchScoreB is null (score still being backfilled),
        // return null rather than substituting the A→B score — showing an incorrect
        // directional score is worse than showing a pending/unknown score.
        Double myScore;
        if (isUserA) {
            myScore = match.getMatchScore(); // A→B
        } else {
            myScore = match.getMatchScoreB(); // B→A, null = score pending backfill
        }

        String primaryPhoto = null;
        if (other.getPhotos() != null && !other.getPhotos().isEmpty()) {
            primaryPhoto = other.getPhotos().stream()
                    .filter(p -> p.getIsPrimary() != null && p.getIsPrimary())
                    .findFirst()
                    .map(p -> p.getImageUrl())
                    .orElse(other.getPhotos().get(0).getImageUrl());
        }

        return MatchResponseDto.builder()
                .matchId(match.getId())
                .matchScore(myScore)
                .status(match.getStatus())
                .conversationStarted(match.getConversationStarted())
                .matchSource(match.getMatchSource())
                .matchedAt(match.getMatchedAt())
                .otherUserId(other.getId())
                .otherUserName(other.getName())
                .otherUserPhoto(primaryPhoto)
                .build();
    }
}
