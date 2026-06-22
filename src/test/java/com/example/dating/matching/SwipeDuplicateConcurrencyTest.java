package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.exceptions.DuplicateSwipeException;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.MatchRepository;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies Batch B — Swipe Duplicate Race Condition.
 *
 * <p>Two threads submitting a swipe for the same {@code (swiper, swiped)} pair
 * simultaneously must:
 * <ol>
 *   <li>Produce exactly one row in {@code user_swipes}.</li>
 *   <li>Have the second request resolved as a {@link DuplicateSwipeException}
 *       (mapped to HTTP 409) — never as an unhandled 500.</li>
 * </ol>
 *
 * <p>A second test verifies that a valid single swipe still triggers a match
 * when the other user has already liked back.
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class SwipeDuplicateConcurrencyTest {

    @Autowired private SwipeService swipeService;
    @Autowired private UserJpaRepository userRepository;
    @Autowired private UserSwipeRepository swipeRepository;
    @Autowired private MatchRepository matchRepository;

    private UserEntity entityA;
    private UserEntity entityB;
    private User domainA;
    private User domainB;

    @BeforeEach
    void setUp() {
        entityA = userRepository.findByEmail("swipe.concurrency.a@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("swipe.concurrency.a@test.com")
                                .name("SwipeConcurrencyA")
                                .build()));

        entityB = userRepository.findByEmail("swipe.concurrency.b@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("swipe.concurrency.b@test.com")
                                .name("SwipeConcurrencyB")
                                .build()));

        domainA = User.builder().id(entityA.getId()).build();
        domainB = User.builder().id(entityB.getId()).build();
    }

    @AfterEach
    void tearDown() {
        // Clean up in dependency order: swipes reference matches.
        swipeRepository.deleteAllInvolvingUser(entityA.getId());
        swipeRepository.deleteAllInvolvingUser(entityB.getId());
        matchRepository.findMatchBetweenUsers(entityA.getId(), entityB.getId())
                .ifPresent(m -> matchRepository.deleteById(m.getId()));
    }

    @Test
    @DisplayName("Concurrent duplicate swipe: exactly one DB row, second gets DuplicateSwipeException (not 500)")
    void concurrentDuplicateSwipe_exactlyOneRowAndNot500() throws Exception {
        int threadCount = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);

        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                barrier.await();
                swipeService.recordSwipe(domainA, entityB.getId(), "like", 75.0, "web");
                return null;
            }));
        }

        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        int duplicateCount = 0;
        int unexpectedErrorCount = 0;

        for (Future<Void> future : futures) {
            try {
                future.get();
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof DuplicateSwipeException) {
                    duplicateCount++;
                } else {
                    System.err.println("Unexpected exception type: " + cause);
                    unexpectedErrorCount++;
                }
            }
        }

        assertEquals(0, unexpectedErrorCount,
                "No thread should have thrown an unexpected (non-409) exception");

        long rowCount = swipeRepository.countSwipesByUserId(entityA.getId());
        assertEquals(1, rowCount,
                "Exactly one swipe row must exist after concurrent inserts");

        assertEquals(threadCount - 1, duplicateCount,
                "All threads except the winner should get DuplicateSwipeException");
    }

    @Test
    @DisplayName("Single swipe still creates a match when there is a mutual like")
    void singleSwipe_createsMatchOnMutualLike() {
        // B likes A first
        swipeService.recordSwipe(domainB, entityA.getId(), "like", 70.0, "web");

        // A likes B back — should trigger a match
        var result = swipeService.recordSwipe(domainA, entityB.getId(), "like", 80.0, "web");

        assertTrue(result.getResultedInMatch(), "Mutual like must create a match");
        assertFalse(result.getMatch() == null, "SwipeResult must contain match details");

        long rowCount = swipeRepository.countSwipesByUserId(entityA.getId());
        assertEquals(1, rowCount, "Exactly one swipe row for userA");
    }
}
