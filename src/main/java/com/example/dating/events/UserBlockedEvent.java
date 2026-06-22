package com.example.dating.events;

/**
 * Published after a user blocks another user. Consumers can use this to
 * remove existing matches, clear chat history, etc.
 *
 * @param blockerId  ID of the user who performed the block
 * @param blockedId  ID of the user who was blocked
 */
public record UserBlockedEvent(
        String blockerId,
        String blockedId) {
}
