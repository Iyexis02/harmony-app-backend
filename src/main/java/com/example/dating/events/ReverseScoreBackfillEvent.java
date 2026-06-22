package com.example.dating.events;

/**
 * Published by {@code SwipeService} when the reverse-direction score (B→A) could not
 * be computed during mutual match creation after 2 attempts.
 *
 * <p>The listener in {@code ReverseScoreBackfillService} is annotated with
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} so it fires only after
 * the match transaction commits. The backfill runs {@code @Async} and writes
 * {@code match_score_b} via a guarded native UPDATE ({@code WHERE match_score_b IS NULL})
 * so it is safe to retry and will not overwrite a score set by another path.
 *
 * @param matchId  ID of the newly created match that is missing {@code matchScoreB}
 * @param userBId  ID of the user whose perspective needs scoring (the swiped user)
 * @param userAId  ID of the other participant (the swiper)
 */
public record ReverseScoreBackfillEvent(
        String matchId,
        String userBId,
        String userAId) {
}
