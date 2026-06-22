package com.example.dating.events;

/**
 * Published after a mutual match is created and the swipe transaction commits.
 * Consumers can use this to trigger push notifications, update real-time feeds, etc.
 *
 * @param matchId     ID of the newly created Match entity
 * @param userAId     ID of the first user in the match (alphabetically first)
 * @param userBId     ID of the second user in the match
 * @param matchScore  Overall match score at time of match creation
 * @param matchSource Source of the match ("mutual_swipe" or "super_like")
 * @param initiatorId ID of the user whose swipe triggered the match
 */
public record MatchCreatedEvent(
        String matchId,
        String userAId,
        String userBId,
        Double matchScore,
        String matchSource,
        String initiatorId) {
}
