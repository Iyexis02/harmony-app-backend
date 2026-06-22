package com.example.dating.services.matching;

import com.example.dating.enums.matching.MatchSource;
import com.example.dating.enums.matching.MatchStatus;
import com.example.dating.exceptions.MatchNotFoundException;
import com.example.dating.exceptions.UnauthorizedMatchAccessException;
import com.example.dating.models.matching.dao.Match;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.MatchRepository;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.events.MatchUnmatchedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing matches between users
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MatchService {

    private final MatchRepository matchRepository;
    private final UserJpaRepository userRepository;
    private final ApplicationContext applicationContext;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Create a mutual match between two users.
     *
     * @param userA      First user
     * @param userB      Second user
     * @param matchScore Match score
     * @return Created or existing match
     */
    @Transactional
    public Match createMatch(UserEntity userA, UserEntity userB, Double matchScore) {
        return createMatch(userA, userB, matchScore, null, MatchSource.MUTUAL_SWIPE);
    }

    /**
     * Create a match between two users with a specific match source.
     * Delegates to the full overload with {@code matchScoreB = null}.
     */
    @Transactional
    public Match createMatch(UserEntity userA, UserEntity userB, Double matchScore, MatchSource matchSource) {
        return createMatch(userA, userB, matchScore, null, matchSource);
    }

    /**
     * Create a match between two users with bidirectional scores.
     *
     * <p>Uses a native {@code INSERT … ON CONFLICT (user_a_id, user_b_id) DO NOTHING} so that
     * concurrent calls never raise a {@code DataIntegrityViolationException} and never poison
     * the surrounding transaction.  The returned entity is always fetched from the database,
     * which ensures callers get fully-hydrated associations regardless of whether this call
     * inserted a new row or resolved a concurrency conflict.
     *
     * <p><b>Integrity Batch H:</b> {@code swiperScore} is the score from the calling user's
     * perspective (swiper→swiped). {@code reverseScore} is the score from the swiped user's
     * perspective (swiped→swiper). After ID reordering to enforce the {@code userA.id < userB.id}
     * convention, the two scores are assigned to {@code match_score} (A→B) and
     * {@code match_score_b} (B→A) accordingly.
     *
     * @param swiper       The user whose swipe triggered the match (first liker or second liker)
     * @param swiped       The other user in the match
     * @param swiperScore  Score from the swiper's perspective (swiper→swiped)
     * @param reverseScore Score from the swiped user's perspective (swiped→swiper), may be null
     * @param matchSource  Source of the match
     * @return Created or existing match
     */
    @Transactional
    public Match createMatch(UserEntity swiper, UserEntity swiped, Double swiperScore,
                             Double reverseScore, MatchSource matchSource) {
        log.info("Creating match between user {} and user {} (source: {})",
                swiper.getId(), swiped.getId(), matchSource);

        // Ensure userA_id < userB_id (database convention for the unique constraint).
        // Assign directional scores to the correct columns after reordering:
        //   match_score   = A→B direction
        //   match_score_b = B→A direction
        String aId = swiper.getId();
        String bId = swiped.getId();
        Double scoreA; // A→B
        Double scoreB; // B→A
        if (aId.compareTo(bId) > 0) {
            // swiper becomes userB, swiped becomes userA after reorder
            String tmp = aId; aId = bId; bId = tmp;
            scoreA = reverseScore; // swiped→swiper = A→B
            scoreB = swiperScore;  // swiper→swiped = B→A
        } else {
            // swiper is already userA
            scoreA = swiperScore;  // swiper→swiped = A→B
            scoreB = reverseScore; // swiped→swiper = B→A
        }

        // Generate UUID here because @GeneratedValue cannot be used with native DML.
        String newId       = UUID.randomUUID().toString();
        LocalDateTime now  = LocalDateTime.now();

        // Atomic upsert — 1 row inserted, 0 if the pair already existed.
        // Native query takes a String, so convert the enum to its DB representation.
        int inserted = matchRepository.insertMatchIfAbsent(
                newId, aId, bId, scoreA, scoreB, matchSource.getValue(), now, now, now);

        if (inserted == 1) {
            log.info("Match created or reactivated between users {} and {} (id: {})",
                    swiper.getId(), swiped.getId(), newId);
        } else {
            log.info("Match already active between users {} and {}, returning existing",
                    swiper.getId(), swiped.getId());
        }

        // Always fetch from DB: gives a fully-hydrated entity and works for both the
        // insert and the conflict case.
        return matchRepository.findMatchBetweenUsers(swiper.getId(), swiped.getId())
                .orElseThrow(() -> new IllegalStateException("Match creation failed — database inconsistency"));
    }

    /**
     * Get a paginated page of active matches for a user.
     *
     * @param userId User ID
     * @param limit  Page size (must be ≥ 1)
     * @param offset Zero-based row offset; converted to a page number via offset/limit
     * @return Page containing matches and total count
     */
    @Transactional(readOnly = true)
    public Page<Match> getActiveMatches(String userId, int limit, int offset) {
        PageRequest pageRequest = PageRequest.of(offset / limit, limit);
        return matchRepository.findActiveMatchesByUserId(userId, pageRequest);
    }

    /**
     * Get a paginated page of all matches for a user (any status).
     *
     * @param userId User ID
     * @param limit  Page size (must be ≥ 1)
     * @param offset Zero-based row offset; converted to a page number via offset/limit
     * @return Page containing matches and total count
     */
    @Transactional(readOnly = true)
    public Page<Match> getAllMatches(String userId, int limit, int offset) {
        PageRequest pageRequest = PageRequest.of(offset / limit, limit);
        return matchRepository.findAllMatchesByUserId(userId, pageRequest);
    }

    /**
     * Get match between two specific users
     *
     * @param userId1 First user ID
     * @param userId2 Second user ID
     * @return Match if exists
     */
    @Transactional(readOnly = true)
    public Optional<Match> getMatchBetweenUsers(String userId1, String userId2) {
        return matchRepository.findMatchBetweenUsers(userId1, userId2);
    }

    /**
     * Check if two users are matched
     *
     * @param userId1 First user ID
     * @param userId2 Second user ID
     * @return True if matched
     */
    @Transactional(readOnly = true)
    public boolean areUsersMatched(String userId1, String userId2) {
        return matchRepository.areUsersMatched(userId1, userId2);
    }

    /**
     * Get count of active matches for user
     *
     * @param userId User ID
     * @return Count of active matches
     */
    @Transactional(readOnly = true)
    public long getActiveMatchCount(String userId) {
        return matchRepository.countActiveMatchesByUserId(userId);
    }

    /**
     * Get matches without conversations (new matches), capped at 100.
     *
     * @param userId User ID
     * @return List of matches without conversation
     */
    @Transactional(readOnly = true)
    public List<Match> getNewMatches(String userId) {
        return matchRepository.findMatchesWithoutConversations(userId, PageRequest.of(0, 100));
    }

    /**
     * Mark conversation as started.
     * Retries once on optimistic-lock conflict so concurrent first-message events
     * do not surface a 500 to callers.
     *
     * @param matchId Match ID
     */
    public void markConversationStarted(String matchId) {
        MatchService self = applicationContext.getBean(MatchService.class);
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                self.doMarkConversationStarted(matchId);
                return;
            } catch (OptimisticLockingFailureException e) {
                if (attempt == 2) throw e;
                log.debug("Optimistic lock conflict on markConversationStarted {} (attempt 1/2), retrying...", matchId);
            }
        }
    }

    @Transactional
    public void doMarkConversationStarted(String matchId) {
        Match match = matchRepository.findById(matchId)
                .orElseThrow(() -> new MatchNotFoundException("Match not found"));

        match.setConversationStarted(true);
        matchRepository.save(match);

        log.info("Marked conversation as started for match {}", matchId);
    }

    /**
     * Unmatch users (set status to unmatched).
     * Only participants of the match may unmatch.
     * Retries once on optimistic-lock conflict so two simultaneous unmatch requests
     * both succeed cleanly instead of one surfacing a 500.
     *
     * @param matchId          Match ID
     * @param requestingUserId ID of the authenticated user performing the action
     * @throws MatchNotFoundException           if the match does not exist
     * @throws UnauthorizedMatchAccessException if the requester is not a participant
     */
    public void unmatch(String matchId, String requestingUserId) {
        MatchService self = applicationContext.getBean(MatchService.class);
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                self.doUnmatch(matchId, requestingUserId);
                return;
            } catch (OptimisticLockingFailureException e) {
                if (attempt == 2) throw e;
                log.debug("Optimistic lock conflict on unmatch {} (attempt 1/2), retrying...", matchId);
            }
        }
    }

    @Transactional
    public void doUnmatch(String matchId, String requestingUserId) {
        Match match = matchRepository.findById(matchId)
                .orElseThrow(() -> new MatchNotFoundException("Match not found: " + matchId));

        // Master Batch E2: idempotency guard — if the match is already UNMATCHED,
        // return early without touching unmatchedBy/unmatchedAt or publishing a
        // duplicate MatchUnmatchedEvent. This covers two races:
        //   1. The unmatch() retry loop re-enters after an optimistic-lock conflict;
        //      by then the first attempt has committed, so status=UNMATCHED.
        //   2. MatchLifecycleListener.onUserBlocked() races with a direct user unmatch.
        if (match.getStatus() == MatchStatus.UNMATCHED) {
            log.debug("Match {} already unmatched, skipping", matchId);
            return;
        }

        boolean isParticipant = match.getUserA().getId().equals(requestingUserId)
                || match.getUserB().getId().equals(requestingUserId);

        if (!isParticipant) {
            throw new UnauthorizedMatchAccessException("You are not a participant in this match");
        }

        match.setStatus(MatchStatus.UNMATCHED);
        match.setUnmatchedAt(java.time.LocalDateTime.now());
        match.setUnmatchedBy(requestingUserId);
        matchRepository.save(match);

        String otherUserId = match.getUserA().getId().equals(requestingUserId)
                ? match.getUserB().getId()
                : match.getUserA().getId();
        eventPublisher.publishEvent(new MatchUnmatchedEvent(matchId, requestingUserId, otherUserId));

        log.info("Unmatched: {}", matchId);
    }
}
