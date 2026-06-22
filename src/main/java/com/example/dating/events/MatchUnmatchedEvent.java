package com.example.dating.events;

/**
 * Published after a user unmatches. The unmatched party can be notified
 * to update their match list in real-time.
 *
 * @param matchId           ID of the Match entity
 * @param unmatchedByUserId ID of the user who initiated the unmatch
 * @param otherUserId       ID of the user who was unmatched
 */
public record MatchUnmatchedEvent(
        String matchId,
        String unmatchedByUserId,
        String otherUserId) {
}
