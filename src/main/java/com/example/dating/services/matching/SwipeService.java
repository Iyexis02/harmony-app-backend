package com.example.dating.services.matching;

import com.example.dating.enums.matching.MatchSource;
import com.example.dating.events.MatchCreatedEvent;
import com.example.dating.events.ReverseScoreBackfillEvent;
import com.example.dating.events.SwipeRecordedEvent;
import com.example.dating.events.UserBlockedEvent;
import com.example.dating.exceptions.BadRequestException;
import com.example.dating.exceptions.DuplicateSwipeException;
import com.example.dating.exceptions.SwipeNotFoundException;
import com.example.dating.exceptions.SwipeUndoNotAllowedException;
import com.example.dating.exceptions.UserNotFoundException;
import com.example.dating.models.matching.dao.Match;
import com.example.dating.models.matching.dao.UserSwipe;
import com.example.dating.models.matching.dto.AnalyticsResponseDto;
import com.example.dating.models.matching.dto.MatchScore;
import com.example.dating.models.matching.dto.SwipeResult;
import com.example.dating.mappers.UserMapper;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.repositories.UserSwipeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service for handling swipe actions
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SwipeService {

    private static final Set<String> VALID_ACTIONS = Set.of("like", "pass", "super_like", "block");

    private final UserSwipeRepository swipeRepository;
    private final UserJpaRepository userRepository;
    private final MatchService matchService;
    private final SwipePersistenceHelper swipePersistenceHelper;
    private final ApplicationEventPublisher eventPublisher;
    private final MatchRecommendationService recommendationService;
    private final UserMapper userMapper;

    /**
     * Record a swipe action
     *
     * @param swiper User performing the swipe
     * @param swipedUserId ID of user being swiped on
     * @param action "like", "super_like", "pass", or "block"
     * @param matchScore Match score at time of swipe
     * @return SwipeResult with match info if mutual like
     */
    @Transactional
    public SwipeResult recordSwipe(User swiper, String swipedUserId, String action, Double matchScore, String platform) {
        // Reject unknown actions before touching the database.
        String normalizedAction = action != null ? action.toLowerCase() : "";
        if (!VALID_ACTIONS.contains(normalizedAction)) {
            throw new BadRequestException(
                    "Invalid swipe action '" + action + "'. Allowed values: like, pass, super_like, block");
        }

        log.info("Recording swipe: user {} {} user {}",
                swiper.getId(), normalizedAction, swipedUserId);

        // Get swiped user
        UserEntity swipedUserEntity = userRepository.findById(swipedUserId)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        // Master Batch E: reject swipes targeting a user whose account is being deleted.
        // deleteAccount() sets deleted=true and flushes before cleaning up child entities,
        // so any swipe that reads the entity after that commit will see the flag here.
        if (swipedUserEntity.isDeleted()) {
            throw new UserNotFoundException("User not found");
        }

        UserEntity swiperEntity = userRepository.findById(swiper.getId())
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        // Batch C: fast-path duplicate check BEFORE score computation.
        // A duplicate swipe must never trigger an expensive scoring round-trip.
        if (swipeRepository.hasUserSwipedOn(swiper.getId(), swipedUserId)) {
            throw new DuplicateSwipeException(swiper.getId(), swipedUserId);
        }

        // Batch C: skip score computation for block actions — the block branch never uses
        // the score (no behavioral update, no match creation).
        // Batch E: for all other actions, compute server-side so the client cannot inject
        // arbitrary values that would poison UserSwipe.matchScoreAtSwipe, Match.matchScore,
        // or the behavioral profile's effectiveScoreThreshold EMA.
        // Uses the cache-first path from MatchScoringService, so hot paths are cheap.
        // The client-supplied matchScore parameter is intentionally unused from here onwards.
        double computedMatchScore = 0.0;
        if (!"block".equals(normalizedAction)) {
            MatchScore computed = recommendationService.getMatchScore(swiper, swipedUserId);
            computedMatchScore = computed.getOverallScore();
        }

        // Batch A — Acquire a PostgreSQL advisory transaction lock before checking for a
        // mutual like.  Both A→B and B→A sort their IDs the same way, so both directions
        // resolve to the same lock integer and are fully serialized:
        //   1. Thread A acquires the lock, checks hasUserLiked (false), INSERTs A→B,
        //      commits → lock released.
        //   2. Thread B acquires the lock, checks hasUserLiked (true — A's row now
        //      committed), INSERTs B→A with mutualLike=true, creates the Match, commits.
        // Without this lock the REQUIRES_NEW window (Batch C) allowed both threads to
        // read hasUserLiked=false before either INSERT committed, producing zero matches.
        boolean mutualLike = false;
        MatchSource matchSource = null;
        if ("like".equals(normalizedAction) || "super_like".equals(normalizedAction)) {
            String pairKey = Stream.of(swiper.getId(), swipedUserId)
                    .sorted()
                    .collect(Collectors.joining(":"));
            swipeRepository.acquirePairLock(pairKey);

            mutualLike = swipeRepository.hasUserLiked(swipedUserId, swiper.getId());
            if (mutualLike) {
                matchSource = "super_like".equals(normalizedAction)
                        ? MatchSource.SUPER_LIKE
                        : MatchSource.MUTUAL_SWIPE;
            }
        }

        // Create swipe record; resultedInMatch is set from the pre-insert mutual-like check.
        UserSwipe swipe = UserSwipe.builder()
                .swiperUser(swiperEntity)
                .swipedUser(swipedUserEntity)
                .action(normalizedAction)
                .matchScoreAtSwipe(computedMatchScore)
                .platform(platform != null ? platform : "web")
                .swipedAt(LocalDateTime.now())
                .resultedInMatch(mutualLike)
                .build();

        // Batch A — saveSwipe now uses REQUIRED propagation (joins the outer transaction).
        // flush() forces the uk_swiper_swiped constraint check immediately so a concurrent
        // duplicate surfaces here as DataIntegrityViolationException rather than at commit
        // time, allowing us to rethrow it as DuplicateSwipeException before any match or
        // behavioral update logic runs.
        try {
            swipe = swipePersistenceHelper.saveSwipe(swipe);
            swipeRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateSwipeException(swiper.getId(), swipedUserId);
        }
        log.info("Swipe recorded with ID: {}", swipe.getId());

        // Block: no behavioral update, no mutual check
        if ("block".equals(normalizedAction)) {
            eventPublisher.publishEvent(new UserBlockedEvent(swiper.getId(), swipedUserId));
            return SwipeResult.builder()
                    .swipeId(swipe.getId())
                    .action(normalizedAction)
                    .matchScore(computedMatchScore)
                    .resultedInMatch(false)
                    .build();
        }

        // Create match if a mutual like was detected before the insert.
        SwipeResult.MatchDetails matchDetails = null;

        if (mutualLike) {
            log.info("Mutual like detected! Creating match (source: {})...", matchSource.getValue());

            // Integrity Batch H: compute reverse-direction score (swiped→swiper)
            // so the Match stores both perspectives.
            // Master Batch J: 2-attempt retry before giving up — transient DB/cache
            // misses often resolve on a second attempt. If both fail, the match is
            // created with matchScoreB=null and a ReverseScoreBackfillEvent is
            // published to fill it in asynchronously after this transaction commits.
            Double reverseMatchScore = null;
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    MatchScore reverseComputed = recommendationService.getMatchScore(
                            userMapper.toDomain(swipedUserEntity), swiper.getId());
                    reverseMatchScore = reverseComputed.getOverallScore();
                    break;
                } catch (Exception e) {
                    log.warn("Reverse score attempt {}/2 failed for {}→{}: {}",
                            attempt, swipedUserId, swiper.getId(), e.getMessage());
                }
            }

            Match match = matchService.createMatch(
                    swiperEntity,
                    swipedUserEntity,
                    computedMatchScore,
                    reverseMatchScore,
                    matchSource
            );

            // Link this swipe to the match (resultedInMatch already true on the entity).
            swipe.setMatch(match);
            swipeRepository.save(swipe);

            // Also update the other user's swipe.
            Optional<UserSwipe> otherSwipe = swipeRepository.findByUserIds(swipedUserId, swiper.getId());
            if (otherSwipe.isPresent()) {
                UserSwipe otherUserSwipe = otherSwipe.get();
                otherUserSwipe.setResultedInMatch(true);
                otherUserSwipe.setMatch(match);
                swipeRepository.save(otherUserSwipe);
            }

            matchDetails = SwipeResult.MatchDetails.builder()
                    .matchId(match.getId())
                    .userId(swipedUserEntity.getId())
                    .name(swipedUserEntity.getName())
                    .matchScore(computedMatchScore)
                    .matchedAt(match.getMatchedAt().toString())
                    .build();

            log.info("Match created with ID: {}", match.getId());

            // Master Batch J: schedule async backfill if reverse score was unavailable.
            if (reverseMatchScore == null) {
                eventPublisher.publishEvent(new ReverseScoreBackfillEvent(
                        match.getId(), swipedUserId, swiper.getId()));
                log.info("Scheduled reverse score backfill for match {}", match.getId());
            }

            eventPublisher.publishEvent(new MatchCreatedEvent(
                    match.getId(),
                    match.getUserA().getId(),
                    match.getUserB().getId(),
                    computedMatchScore,
                    matchSource.getValue(),
                    swiper.getId()));
        }

        // Publish event so BehavioralProfileService updates the profile AFTER this transaction
        // commits.  @TransactionalEventListener(phase = AFTER_COMMIT) on the listener guarantees
        // the behavioral profile is never mutated for a swipe that was never persisted.
        eventPublisher.publishEvent(new SwipeRecordedEvent(swiper.getId(), swipedUserId, normalizedAction, computedMatchScore));

        return SwipeResult.builder()
                .swipeId(swipe.getId())
                .action(normalizedAction)
                .matchScore(computedMatchScore)
                .resultedInMatch(mutualLike)
                .match(matchDetails)
                .build();
    }

    /**
     * Undo (delete) the swipe the current user made on {@code swipedUserId}, so the candidate
     * can resurface in recommendations.
     *
     * <p>There is at most one swipe per (swiper, swiped) pair (uk_swiper_swiped), so this removes
     * that single row regardless of action (like / pass / super_like / block — undoing a block
     * row simply un-blocks, since there is no separate unblock endpoint).
     *
     * <p>A swipe that already formed a match cannot be undone — the user must unmatch instead
     * (→ {@link SwipeUndoNotAllowedException}, 409). Undoing a non-existent swipe →
     * {@link SwipeNotFoundException} (404).
     *
     * <p><b>Concurrency:</b> acquires the same {@code pairKey} advisory lock that
     * {@link #recordSwipe}'s mutual-like section uses, so an in-flight reciprocal like cannot
     * slip a match in between our read and delete. The match guard is therefore evaluated under
     * the lock against committed state.
     *
     * <p><b>Note:</b> this does not reverse the behavioral-profile EMA update the original swipe
     * triggered (that update is probabilistic and decays); and the caller is responsible for
     * invalidating the candidate-pool cache <em>after</em> this transaction commits.
     */
    @Transactional
    public void undoSwipe(User swiper, String swipedUserId) {
        String swiperId = swiper.getId();

        String pairKey = Stream.of(swiperId, swipedUserId)
                .sorted()
                .collect(Collectors.joining(":"));
        swipeRepository.acquirePairLock(pairKey);

        UserSwipe swipe = swipeRepository.findByUserIds(swiperId, swipedUserId)
                .orElseThrow(() -> new SwipeNotFoundException("No swipe to undo for this user"));

        if (Boolean.TRUE.equals(swipe.getResultedInMatch()) || swipe.getMatch() != null) {
            throw new SwipeUndoNotAllowedException("Cannot undo a swipe that resulted in a match");
        }

        swipeRepository.delete(swipe);
        log.info("Undid swipe: user {} -> user {} (action was '{}')",
                swiperId, swipedUserId, swipe.getAction());
    }

    /**
     * Check if user has already swiped on another user
     */
    @Transactional(readOnly = true)
    public boolean hasSwipedOn(String userId, String otherUserId) {
        return swipeRepository.hasUserSwipedOn(userId, otherUserId);
    }

    /**
     * Get swipe count for user
     */
    @Transactional(readOnly = true)
    public long getSwipeCount(String userId) {
        return swipeRepository.countSwipesByUserId(userId);
    }

    /**
     * Get like count for user (includes super-likes).
     * Uses a COUNT query — does not load entities into memory.
     */
    @Transactional(readOnly = true)
    public long getLikeCount(String userId) {
        return swipeRepository.countLikesByUserId(userId);
    }

    /**
     * Calculate swipe-through rate (likes / total swipes)
     */
    @Transactional(readOnly = true)
    public Double getSwipeThroughRate(String userId) {
        return swipeRepository.calculateSwipeThroughRate(userId);
    }

    /**
     * Compute analytics for a user.
     * matchRate and totalPasses are derived here — not in the controller (Batch I).
     *
     * @param userId user ID
     * @return typed analytics DTO
     */
    @Transactional(readOnly = true)
    public AnalyticsResponseDto getAnalytics(String userId) {
        long totalSwipes = getSwipeCount(userId);
        long totalLikes = getLikeCount(userId);
        long totalMatches = matchService.getActiveMatchCount(userId);
        Double swipeThroughRate = getSwipeThroughRate(userId);
        Double matchRate = totalLikes > 0 ? (double) totalMatches / totalLikes : 0.0;

        return AnalyticsResponseDto.builder()
                .totalSwipes(totalSwipes)
                .totalLikes(totalLikes)
                .totalPasses(totalSwipes - totalLikes)
                .totalMatches(totalMatches)
                .swipeThroughRate(swipeThroughRate)
                .matchRate(matchRate)
                .build();
    }
}
