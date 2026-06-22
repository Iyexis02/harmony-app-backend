package com.example.dating.services.matching;

import com.example.dating.events.ReverseScoreBackfillEvent;
import com.example.dating.exceptions.UserNotFoundException;
import com.example.dating.models.matching.dto.MatchScore;
import com.example.dating.mappers.UserMapper;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.repositories.MatchRepository;
import com.example.dating.repositories.UserJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Listens for {@link ReverseScoreBackfillEvent} and computes the missing B→A
 * directional score after the originating match transaction has committed.
 *
 * <p>The handler runs {@code @Async} so the HTTP response is not held while scoring
 * executes. The native UPDATE uses {@code WHERE match_score_b IS NULL} so the write
 * is idempotent — safe to retry without double-writing.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReverseScoreBackfillService {

    private final MatchRepository matchRepository;
    private final MatchRecommendationService recommendationService;
    private final UserJpaRepository userRepository;
    private final UserMapper userMapper;

    /**
     * Compute and persist the B→A score for a match that was created without it.
     *
     * <p>Fires {@code AFTER_COMMIT} of the originating swipe transaction so the match
     * row is guaranteed to exist before the UPDATE runs.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void onReverseScoreBackfill(ReverseScoreBackfillEvent event) {
        log.info("Backfilling reverse score for match {} ({}→{})",
                event.matchId(), event.userBId(), event.userAId());
        try {
            UserEntity userBEntity = userRepository.findById(event.userBId())
                    .orElseThrow(() -> new UserNotFoundException("User not found: " + event.userBId()));

            MatchScore score = recommendationService.getMatchScore(
                    userMapper.toDomain(userBEntity), event.userAId());

            int updated = matchRepository.updateMatchScoreBIfNull(
                    event.matchId(), score.getOverallScore());

            if (updated == 1) {
                log.info("Backfilled match_score_b={} for match {}",
                        score.getOverallScore(), event.matchId());
            } else {
                log.debug("match_score_b already set for match {}, skipping backfill", event.matchId());
            }
        } catch (Exception e) {
            log.error("Reverse score backfill failed for match {}: {}", event.matchId(), e.getMessage(), e);
        }
    }
}
