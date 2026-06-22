package com.example.dating.events;

/**
 * Published by {@code SwipeService} immediately after persisting a swipe row,
 * while the outer {@code @Transactional} is still open.
 *
 * <p>The listener in {@code BehavioralProfileService} is annotated with
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}, so it fires only
 * after the swipe transaction has committed successfully.  If the swipe
 * transaction rolls back, this event is discarded and the behavioral profile
 * is never touched — preventing phantom preference updates for unperisted swipes.
 *
 * @param swiperId     ID of the user who performed the swipe
 * @param swipedUserId ID of the user who was swiped on
 * @param action       normalised swipe action ("like", "super_like", "pass")
 * @param matchScore   overall match score at the time of the swipe (may be null)
 */
public record SwipeRecordedEvent(
        String swiperId,
        String swipedUserId,
        String action,
        Double matchScore) {
}
