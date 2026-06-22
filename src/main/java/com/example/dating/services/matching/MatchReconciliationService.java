package com.example.dating.services.matching;

import com.example.dating.models.matching.dao.Match;
import com.example.dating.models.matching.dao.UserSwipe;
import com.example.dating.repositories.MatchRepository;
import com.example.dating.repositories.UserSwipeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Periodic reconciliation job that repairs the inconsistent state left behind when
 * {@link MatchLifecycleListener#onUserBlocked} fails to unmatch a pair after a block.
 *
 * <p><b>Failure scenario (Master Batch H):</b>
 * <ol>
 *   <li>User A blocks User B → block swipe committed to DB.</li>
 *   <li>{@code UserBlockedEvent} fires on an async thread (AFTER_COMMIT).</li>
 *   <li>{@code matchService.unmatch()} retries twice on {@code OptimisticLockingFailureException}
 *       — both attempts fail under high contention.</li>
 *   <li>Exception propagates to the async uncaught handler and is logged as ERROR.</li>
 *   <li>The block swipe row exists, but the {@code Match} row remains {@code ACTIVE}.</li>
 * </ol>
 *
 * <p>This service runs every 5 minutes. Each tick processes batches of
 * {@link #BATCH_SIZE} until either {@link #MAX_ROWS_PER_TICK} have been resolved
 * or the {@link #TICK_BUDGET_MS} time budget is reached. The throttling lets the
 * job catch up after a block storm without making any single tick run longer
 * than the budget — the scheduler thread pool size is 1, so over-running ticks
 * would delay every other scheduled job.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchReconciliationService {

    /** Process at most this many block swipes per scheduled tick. */
    private static final int MAX_ROWS_PER_TICK = 1000;
    /** Page size for each DB fetch within a tick. */
    private static final int BATCH_SIZE = 100;
    /** Hard wall-clock budget for a single tick. */
    private static final long TICK_BUDGET_MS = 30_000L;

    private final UserSwipeRepository userSwipeRepository;
    private final MatchRepository matchRepository;
    private final MatchService matchService;

    /**
     * Find block swipes with a stale ACTIVE match and unmatch each pair.
     * Runs every 5 minutes (fixed delay measured from the end of the previous run).
     */
    @Scheduled(fixedDelay = 300_000)
    public void reconcileBlockedMatches() {
        long deadline = System.currentTimeMillis() + TICK_BUDGET_MS;
        int totalProcessed = 0;
        int totalResolved = 0;
        int batchCount = 0;

        while (totalProcessed < MAX_ROWS_PER_TICK && System.currentTimeMillis() < deadline) {
            List<UserSwipe> batch = userSwipeRepository
                    .findBlockSwipesWithActiveMatches(PageRequest.of(0, BATCH_SIZE));
            if (batch.isEmpty()) {
                break;
            }
            batchCount++;
            totalResolved += processBatch(batch);
            totalProcessed += batch.size();
        }

        if (totalProcessed == 0) {
            log.debug("Reconciliation: no stale block-unmatch pairs found");
            return;
        }
        if (totalProcessed >= MAX_ROWS_PER_TICK) {
            log.warn("Reconciliation hit MAX_ROWS_PER_TICK={} — backlog likely; next tick will continue",
                    MAX_ROWS_PER_TICK);
        } else if (System.currentTimeMillis() >= deadline) {
            log.warn("Reconciliation hit TICK_BUDGET_MS={} after processing {} rows — backlog likely",
                    TICK_BUDGET_MS, totalProcessed);
        }
        log.info("Reconciliation complete: {}/{} stale matches resolved across {} batch(es)",
                totalResolved, totalProcessed, batchCount);
    }

    /** Resolves a single batch and returns the number of matches successfully unmatched. */
    private int processBatch(List<UserSwipe> batch) {
        int resolved = 0;
        for (UserSwipe swipe : batch) {
            String swiperId = swipe.getSwiperUser().getId();
            String swipedId = swipe.getSwipedUser().getId();
            try {
                Optional<Match> activeMatch =
                        matchRepository.findActiveMatchBetweenUsers(swiperId, swipedId);
                if (activeMatch.isPresent()) {
                    matchService.unmatch(activeMatch.get().getId(), swiperId);
                    resolved++;
                }
            } catch (Exception e) {
                log.error("Reconciliation: failed to unmatch stale pair swiper={} swiped={}: {}",
                        swiperId, swipedId, e.getMessage(), e);
            }
        }
        return resolved;
    }
}
