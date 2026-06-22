package com.example.dating.services.matching;

import com.example.dating.events.MatchCreatedEvent;
import com.example.dating.events.MatchUnmatchedEvent;
import com.example.dating.events.UserBlockedEvent;
import com.example.dating.repositories.MatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Reacts to match lifecycle events. Currently logs for future push-notification
 * and real-time feed integration. When a WebSocket or push notification service
 * is added, this listener is the integration point.
 *
 * <p>All handlers use {@code @TransactionalEventListener(phase = AFTER_COMMIT)}
 * so they only fire after the originating transaction has successfully committed.
 * {@code @Async} ensures the publishing thread is not blocked by listener work.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MatchLifecycleListener {

    private final MatchRepository matchRepository;
    private final MatchService matchService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onMatchCreated(MatchCreatedEvent event) {
        log.info("Match created: {} between {} and {} (source: {}, initiator: {})",
                event.matchId(), event.userAId(), event.userBId(),
                event.matchSource(), event.initiatorId());
        // TODO: Send push notification to the non-initiator user
        // TODO: Update real-time feed via WebSocket
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onMatchUnmatched(MatchUnmatchedEvent event) {
        log.info("Match {} unmatched by user {} (other user: {})",
                event.matchId(), event.unmatchedByUserId(), event.otherUserId());
        // TODO: Send push notification to the unmatched user
        // TODO: Update real-time feed via WebSocket
    }

    /**
     * Inline retry budget for the unmatch on block. After both attempts fail with
     * {@link OptimisticLockingFailureException} the row is left for
     * {@link MatchReconciliationService} to repair on its next scheduled tick.
     * Keeping the value tight (2 attempts × 100 ms) bounds the work each block
     * does on the async pool while still absorbing common contention spikes.
     */
    private static final int UNMATCH_MAX_ATTEMPTS = 2;
    private static final long UNMATCH_RETRY_SLEEP_MS = 100L;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onUserBlocked(UserBlockedEvent event) {
        log.info("User {} blocked user {}", event.blockerId(), event.blockedId());
        matchRepository.findActiveMatchBetweenUsers(event.blockerId(), event.blockedId())
                .ifPresent(match -> unmatchWithRetry(match.getId(), event.blockerId()));
    }

    /**
     * Attempts {@code matchService.unmatch} up to {@link #UNMATCH_MAX_ATTEMPTS} times,
     * sleeping {@link #UNMATCH_RETRY_SLEEP_MS} ms between attempts. Only
     * {@link OptimisticLockingFailureException} is retried — every other exception
     * propagates to the {@code @Async} uncaught handler so it is not swallowed.
     *
     * <p>If both attempts lose the optimistic-lock race, the method returns
     * normally; the failure is logged at WARN and {@link MatchReconciliationService}
     * picks up the stale ACTIVE match on its next 5-minute tick.
     */
    private void unmatchWithRetry(String matchId, String unmatcherId) {
        for (int attempt = 1; attempt <= UNMATCH_MAX_ATTEMPTS; attempt++) {
            try {
                matchService.unmatch(matchId, unmatcherId);
                return;
            } catch (OptimisticLockingFailureException ex) {
                if (attempt == UNMATCH_MAX_ATTEMPTS) {
                    log.warn("Block→unmatch lost optimistic-lock race after {} attempts for match {} — " +
                                    "MatchReconciliationService will retry on next tick",
                            UNMATCH_MAX_ATTEMPTS, matchId);
                    return;
                }
                try {
                    Thread.sleep(UNMATCH_RETRY_SLEEP_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("Block→unmatch retry interrupted for match {}", matchId);
                    return;
                }
            }
        }
    }
}
