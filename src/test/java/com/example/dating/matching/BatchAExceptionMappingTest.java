package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.exceptions.MatchNotFoundException;
import com.example.dating.exceptions.UserNotFoundException;
import com.example.dating.models.matching.dao.Match;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.MatchRepository;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.matching.BehavioralProfileService;
import com.example.dating.services.matching.MatchRecommendationService;
import com.example.dating.services.matching.MatchService;
import com.example.dating.services.matching.SwipeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Batch A — Verifies that bare RuntimeException throws have been replaced with
 * typed domain exceptions, so GlobalExceptionHandler can map them to the
 * correct HTTP status codes (404 for user/match not found, 500 only for true
 * database inconsistencies).
 *
 * These are unit-style assertions on exception type — no HTTP layer needed.
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class BatchAExceptionMappingTest {

    private static final String GHOST_USER_ID  = "00000000-0000-0000-0000-000000000001";
    private static final String GHOST_MATCH_ID = "00000000-0000-0000-0000-000000000002";

    @Autowired private MatchRecommendationService matchRecommendationService;
    @Autowired private SwipeService               swipeService;
    @Autowired private MatchService               matchService;
    @Autowired private BehavioralProfileService   behavioralProfileService;

    // ─── Helper: a User domain object that references a non-existent ID ───────

    private User ghostUser() {
        // Build the thinnest possible User that just carries an ID.
        // UserMapper.toDomain() requires a UserEntity, but we only need the id
        // field that the services pass to userRepository.findById().
        return User.builder().id(GHOST_USER_ID).build();
    }

    // ─── 1. MatchRecommendationService.findPotentialMatches ──────────────────

    @Test
    @DisplayName("findPotentialMatches with deleted user id → UserNotFoundException (was RuntimeException → 500)")
    void findPotentialMatches_ghostUser_throwsUserNotFoundException() {
        User ghost = ghostUser();

        UserNotFoundException ex = assertThrows(
                UserNotFoundException.class,
                () -> matchRecommendationService.findPotentialMatches(ghost, 10, 0, 0.0, true)
        );

        assertEquals("User not found", ex.getMessage());
        // Must NOT contain the user id (information leak check)
        assertFalse(ex.getMessage().contains(GHOST_USER_ID),
                "Exception message must not leak user id");
    }

    // ─── 2. MatchRecommendationService.getMatchScore ─────────────────────────

    @Test
    @DisplayName("getMatchScore with deleted current-user id → UserNotFoundException")
    void getMatchScore_ghostCurrentUser_throwsUserNotFoundException() {
        User ghost = ghostUser();

        UserNotFoundException ex = assertThrows(
                UserNotFoundException.class,
                () -> matchRecommendationService.getMatchScore(ghost, "any-other-id")
        );

        assertEquals("User not found", ex.getMessage());
        assertFalse(ex.getMessage().contains(GHOST_USER_ID));
    }

    @Test
    @DisplayName("getMatchScore with deleted other-user id → UserNotFoundException")
    void getMatchScore_ghostOtherUser_throwsUserNotFoundException(@Autowired UserJpaRepository userJpaRepository) {
        // Use the first real user in the DB as the current user so only the
        // other-user lookup triggers the exception.
        UserEntity realEntity = userJpaRepository.findAll()
                .stream().findFirst()
                .orElse(null);

        if (realEntity == null) {
            // No seed users present — skip rather than fail
            return;
        }

        User real = User.builder().id(realEntity.getId()).build();

        UserNotFoundException ex = assertThrows(
                UserNotFoundException.class,
                () -> matchRecommendationService.getMatchScore(real, GHOST_USER_ID)
        );

        assertEquals("User not found", ex.getMessage());
        assertFalse(ex.getMessage().contains(GHOST_USER_ID));
    }

    // ─── 3. SwipeService.recordSwipe — swiped-user not found ────────────────

    @Test
    @DisplayName("recordSwipe with deleted swiped-user id → UserNotFoundException (was 500)")
    void recordSwipe_ghostSwipedUser_throwsUserNotFoundException(@Autowired UserJpaRepository userJpaRepository) {
        UserEntity realEntity = userJpaRepository.findAll()
                .stream().findFirst()
                .orElse(null);

        if (realEntity == null) {
            return; // no seed data, skip
        }

        User real = User.builder().id(realEntity.getId()).build();

        UserNotFoundException ex = assertThrows(
                UserNotFoundException.class,
                () -> swipeService.recordSwipe(real, GHOST_USER_ID, "like", null, "test")
        );

        assertEquals("User not found", ex.getMessage());
        assertFalse(ex.getMessage().contains(GHOST_USER_ID));
    }

    // ─── 4. SwipeService.recordSwipe — swiper not found ─────────────────────

    @Test
    @DisplayName("recordSwipe with deleted swiper id → UserNotFoundException (was 500)")
    void recordSwipe_ghostSwiper_throwsUserNotFoundException(@Autowired UserJpaRepository userJpaRepository) {
        UserEntity realEntity = userJpaRepository.findAll()
                .stream().findFirst()
                .orElse(null);

        if (realEntity == null) {
            return;
        }

        // Ghost swiper, real target — swipedUserId lookup succeeds, swiper lookup fails
        User ghost = ghostUser();

        UserNotFoundException ex = assertThrows(
                UserNotFoundException.class,
                () -> swipeService.recordSwipe(ghost, realEntity.getId(), "like", null, "test")
        );

        assertEquals("User not found", ex.getMessage());
    }

    // ─── 5. MatchService.markConversationStarted — match not found ───────────

    @Test
    @DisplayName("markConversationStarted with non-existent match id → MatchNotFoundException (was RuntimeException → 500)")
    void markConversationStarted_ghostMatch_throwsMatchNotFoundException() {
        MatchNotFoundException ex = assertThrows(
                MatchNotFoundException.class,
                () -> matchService.markConversationStarted(GHOST_MATCH_ID)
        );

        assertEquals("Match not found", ex.getMessage());
        // Must NOT leak the match id
        assertFalse(ex.getMessage().contains(GHOST_MATCH_ID),
                "Exception message must not leak match id");
    }

    // ─── 6. Exception type hierarchy — no raw RuntimeException leaks ─────────

    @Test
    @DisplayName("UserNotFoundException is a RuntimeException (global handler still catches Exception supertype)")
    void userNotFoundException_isRuntimeException() {
        // Structural: our typed exceptions must remain unchecked so Spring MVC
        // propagation works without checked-exception declarations on controllers.
        assertTrue(RuntimeException.class.isAssignableFrom(UserNotFoundException.class));
        assertTrue(RuntimeException.class.isAssignableFrom(MatchNotFoundException.class));
    }

    // ─── 7. Concurrent access — exception type is stable under load ──────────

    @Test
    @DisplayName("Concurrent ghost-user lookups all throw UserNotFoundException, never raw RuntimeException")
    void concurrentGhostLookups_allThrowUserNotFoundException() throws InterruptedException {
        int threads = 10;
        CountDownLatch start  = new CountDownLatch(1);
        CountDownLatch done   = new CountDownLatch(threads);
        AtomicInteger correct = new AtomicInteger(0);
        AtomicInteger wrong   = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        User ghost = ghostUser();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    matchRecommendationService.findPotentialMatches(ghost, 5, 0, 0.0, false);
                } catch (UserNotFoundException e) {
                    correct.incrementAndGet();
                } catch (RuntimeException e) {
                    // Raw RuntimeException — the bug we fixed
                    wrong.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown(); // release all threads simultaneously
        done.await();
        pool.shutdown();

        assertEquals(0, wrong.get(),
                "Raw RuntimeException observed — Batch A fix not applied correctly");
        assertEquals(threads, correct.get(),
                "Not all threads received UserNotFoundException");
    }
}
