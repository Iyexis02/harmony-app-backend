package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.models.matching.dao.Match;
import com.example.dating.models.matching.dao.UserSwipe;
import com.example.dating.models.matching.dto.SwipeResult;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.MatchRepository;
import com.example.dating.repositories.UserBehavioralProfileRepository;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.repositories.UserSwipeRepository;
import com.example.dating.services.matching.SwipeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies Batch E — Server-Side Match Score Computation.
 *
 * <p>The client-supplied {@code matchScore} in the swipe request must be ignored.
 * The server must compute the score from the scoring algorithm and use that value for:
 * <ul>
 *   <li>{@code UserSwipe.matchScoreAtSwipe}</li>
 *   <li>{@code Match.matchScore} (on mutual like)</li>
 *   <li>The {@code SwipeResult.matchScore} returned to the caller</li>
 *   <li>The behavioral-profile {@code effectiveScoreThreshold} EMA</li>
 * </ul>
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class SwipeServerSideScoreTest {

    @Autowired private SwipeService swipeService;
    @Autowired private UserJpaRepository userRepository;
    @Autowired private UserSwipeRepository swipeRepository;
    @Autowired private MatchRepository matchRepository;
    @Autowired private UserBehavioralProfileRepository behavioralProfileRepository;

    private UserEntity entityA;
    private UserEntity entityB;
    private User domainA;
    private User domainB;

    @BeforeEach
    void setUp() {
        entityA = userRepository.findByEmail("batch.e.score.a@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("batch.e.score.a@test.com")
                                .name("BatchEScoreA")
                                .build()));

        entityB = userRepository.findByEmail("batch.e.score.b@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("batch.e.score.b@test.com")
                                .name("BatchEScoreB")
                                .build()));

        domainA = User.builder().id(entityA.getId()).build();
        domainB = User.builder().id(entityB.getId()).build();
    }

    @AfterEach
    void tearDown() {
        swipeRepository.deleteAllInvolvingUser(entityA.getId());
        swipeRepository.deleteAllInvolvingUser(entityB.getId());
        matchRepository.findMatchBetweenUsers(entityA.getId(), entityB.getId())
                .ifPresent(m -> matchRepository.deleteById(m.getId()));
        behavioralProfileRepository.findByUserId(entityA.getId())
                .ifPresent(p -> behavioralProfileRepository.delete(p));
        behavioralProfileRepository.findByUserId(entityB.getId())
                .ifPresent(p -> behavioralProfileRepository.delete(p));
    }

    @Test
    @DisplayName("Client matchScore=999 is ignored: stored swipe score must be in [0,100]")
    void clientScore999_isIgnored_storedScoreInValidRange() {
        double poisonScore = 999.0;

        SwipeResult result = swipeService.recordSwipe(domainA, entityB.getId(), "like", poisonScore, "web");

        // Response must not echo the poison value
        assertNotEquals(poisonScore, result.getMatchScore(),
                "SwipeResult.matchScore must not be the client-supplied value");
        assertTrue(result.getMatchScore() >= 0.0 && result.getMatchScore() <= 100.0,
                "SwipeResult.matchScore must be in [0,100], was: " + result.getMatchScore());

        // Stored swipe row must not contain the poison value
        Optional<UserSwipe> swipeOpt = swipeRepository.findByUserIds(entityA.getId(), entityB.getId());
        assertTrue(swipeOpt.isPresent(), "Swipe row must exist");
        double stored = swipeOpt.get().getMatchScoreAtSwipe();
        assertNotEquals(poisonScore, stored,
                "UserSwipe.matchScoreAtSwipe must not be the client-supplied value");
        assertTrue(stored >= 0.0 && stored <= 100.0,
                "UserSwipe.matchScoreAtSwipe must be in [0,100], was: " + stored);
    }

    @Test
    @DisplayName("Client matchScore=-1 is ignored: stored swipe score must be in [0,100]")
    void clientScoreNegative_isIgnored_storedScoreInValidRange() {
        double poisonScore = -1.0;

        SwipeResult result = swipeService.recordSwipe(domainA, entityB.getId(), "pass", poisonScore, "web");

        assertNotEquals(poisonScore, result.getMatchScore(),
                "SwipeResult.matchScore must not be the client-supplied value");
        assertTrue(result.getMatchScore() >= 0.0 && result.getMatchScore() <= 100.0,
                "SwipeResult.matchScore must be in [0,100], was: " + result.getMatchScore());
    }

    @Test
    @DisplayName("Mutual like with client matchScore=999: Match.matchScore must be in [0,100]")
    void mutualLike_matchScoreIsServerComputed() {
        double poisonScore = 999.0;

        // B likes A first
        swipeService.recordSwipe(domainB, entityA.getId(), "like", poisonScore, "web");

        // A likes B back — mutual like, creates a match
        SwipeResult result = swipeService.recordSwipe(domainA, entityB.getId(), "like", poisonScore, "web");

        assertTrue(result.getResultedInMatch(), "Mutual like must create a match");
        assertNotNull(result.getMatch(), "SwipeResult must contain MatchDetails");

        // MatchDetails score must not be the poison value
        assertNotEquals(poisonScore, result.getMatch().getMatchScore(),
                "MatchDetails.matchScore must not be the client-supplied value");
        assertTrue(result.getMatch().getMatchScore() >= 0.0 && result.getMatch().getMatchScore() <= 100.0,
                "MatchDetails.matchScore must be in [0,100], was: " + result.getMatch().getMatchScore());

        // The persisted Match entity score must also be valid
        Optional<Match> matchOpt = matchRepository.findMatchBetweenUsers(entityA.getId(), entityB.getId());
        assertTrue(matchOpt.isPresent(), "Match row must exist");
        double storedMatchScore = matchOpt.get().getMatchScore();
        assertNotEquals(poisonScore, storedMatchScore,
                "Match.matchScore must not be the client-supplied value");
        assertTrue(storedMatchScore >= 0.0 && storedMatchScore <= 100.0,
                "Match.matchScore must be in [0,100], was: " + storedMatchScore);
    }

    @Test
    @DisplayName("Behavioral profile effectiveScoreThreshold must not be poisoned by client score 999")
    void behavioralProfile_thresholdNotPoisoned() throws InterruptedException {
        double poisonScore = 999.0;

        swipeService.recordSwipe(domainA, entityB.getId(), "like", poisonScore, "web");

        // The behavioral update is async (AFTER_COMMIT via @TransactionalEventListener + @Async).
        // Give it a moment to complete before asserting.
        Thread.sleep(500);

        behavioralProfileRepository.findByUserId(entityA.getId()).ifPresent(profile -> {
            if (profile.getEffectiveScoreThreshold() != null) {
                assertNotEquals(poisonScore, profile.getEffectiveScoreThreshold(),
                        "effectiveScoreThreshold must not equal the poison value 999");
                assertTrue(profile.getEffectiveScoreThreshold() >= 0.0
                                && profile.getEffectiveScoreThreshold() <= 100.0,
                        "effectiveScoreThreshold must be in [0,100], was: "
                                + profile.getEffectiveScoreThreshold());
            }
            // If null: no threshold was set yet (EMA only applied on like with non-null score) — OK
        });
    }
}
