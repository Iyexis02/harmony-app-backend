package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.exceptions.DuplicateSwipeException;
import com.example.dating.models.matching.dao.UserSwipe;
import com.example.dating.models.matching.dto.MatchScore;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.repositories.UserSwipeRepository;
import com.example.dating.services.matching.MatchRecommendationService;
import com.example.dating.services.matching.SwipeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verifies Batch C — Score Computation Ordering in SwipeService.
 *
 * <p>Covers:
 * <ol>
 *   <li>Duplicate swipe does not trigger score computation.</li>
 *   <li>Block action does not trigger score computation.</li>
 *   <li>Like action triggers score computation exactly once.</li>
 *   <li>Score returned by the engine is stored on the swipe record.</li>
 * </ol>
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class IntegrityBatchCScoreOrderingTest {

    @Autowired private SwipeService swipeService;
    @Autowired private UserJpaRepository userRepository;
    @Autowired private UserSwipeRepository swipeRepository;

    // SpyBean wraps the real bean so we can verify interaction counts
    // while still executing the real scoring logic for test 3 & 4.
    @SpyBean private MatchRecommendationService recommendationService;

    private UserEntity entityA;
    private UserEntity entityB;
    private User domainA;
    private User domainB;

    @BeforeEach
    void setUp() {
        entityA = userRepository.findByEmail("integrity.c.score.a@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("integrity.c.score.a@test.com")
                                .name("IntegrityCScoreA")
                                .build()));

        entityB = userRepository.findByEmail("integrity.c.score.b@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("integrity.c.score.b@test.com")
                                .name("IntegrityCScoreB")
                                .build()));

        domainA = User.builder().id(entityA.getId()).build();
        domainB = User.builder().id(entityB.getId()).build();
    }

    @AfterEach
    void tearDown() {
        swipeRepository.findAll().stream()
                .filter(s -> s.getSwiperUser().getId().equals(entityA.getId())
                        || s.getSwiperUser().getId().equals(entityB.getId())
                        || s.getSwipedUser().getId().equals(entityA.getId())
                        || s.getSwipedUser().getId().equals(entityB.getId()))
                .forEach(swipeRepository::delete);
        // Reset spy call counts between tests
        reset(recommendationService);
    }

    // -----------------------------------------------------------------------
    // Test 1 — duplicate swipe must NOT call getMatchScore
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("Duplicate swipe throws DuplicateSwipeException without calling scoring engine")
    void duplicateSwipe_doesNotCallScoring() {
        // First swipe — legitimate
        swipeService.recordSwipe(domainA, entityB.getId(), "pass", null, "web");

        // Spy must have been called exactly once for the first swipe
        verify(recommendationService, times(1)).getMatchScore(any(), any());

        // Reset so we can isolate the duplicate attempt
        reset(recommendationService);

        // Second swipe — duplicate
        assertThrows(DuplicateSwipeException.class, () ->
                swipeService.recordSwipe(domainA, entityB.getId(), "pass", null, "web"));

        // Scoring must NOT have been called for the duplicate
        verify(recommendationService, never()).getMatchScore(any(), any());
    }

    // -----------------------------------------------------------------------
    // Test 2 — block action must NOT call getMatchScore
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("Block action does not call scoring engine")
    void blockAction_doesNotCallScoring() {
        swipeService.recordSwipe(domainA, entityB.getId(), "block", null, "web");

        verify(recommendationService, never()).getMatchScore(any(), any());
    }

    // -----------------------------------------------------------------------
    // Test 3 — like action MUST call getMatchScore exactly once
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("Like action calls scoring engine exactly once")
    void likeAction_callsScoringExactlyOnce() {
        swipeService.recordSwipe(domainA, entityB.getId(), "like", null, "web");

        verify(recommendationService, times(1)).getMatchScore(eq(domainA), eq(entityB.getId()));
    }

    // -----------------------------------------------------------------------
    // Test 4 — the server-computed score is stored on the swipe record
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("Server-computed score is stored on the swipe record")
    void likeAction_serverScoreStoredOnSwipe() {
        // Stub the spy to return a known score so the assertion is deterministic.
        double stubbedScore = 77.5;
        MatchScore fakeScore = MatchScore.builder()
                .overallScore(stubbedScore)
                .musicScore(0.0)
                .lifestyleScore(0.0)
                .interestsScore(0.0)
                .locationScore(0.0)
                .behavioralScore(0.0)
                .build();
        doReturn(fakeScore).when(recommendationService).getMatchScore(any(), any());

        swipeService.recordSwipe(domainA, entityB.getId(), "like", null, "web");

        Optional<UserSwipe> swipe = swipeRepository.findByUserIds(
                entityA.getId(), entityB.getId());
        assertTrue(swipe.isPresent(), "Swipe record must exist");
        assertEquals(stubbedScore, swipe.get().getMatchScoreAtSwipe(), 0.001,
                "matchScoreAtSwipe must equal the server-computed score");
    }
}
