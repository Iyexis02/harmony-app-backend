package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.models.matching.dto.MatchScore;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.repositories.UserMatchScoreRepository;
import com.example.dating.services.matching.MatchScoringService;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies Batch C — Score Cache Transaction Boundaries.
 *
 * <p>Two failure modes are addressed:
 * <ol>
 *   <li><b>Silent cache failure:</b> Before the fix, {@code calculateScore()} had no
 *       {@code @Transactional} annotation and therefore inherited the caller's
 *       {@code readOnly = true} transaction from {@code findPotentialMatches()}. PostgreSQL
 *       rejected the DML in {@code upsertMatchScoreCache()} and the broad
 *       {@code catch(Exception)} swallowed the error — the cache was never written.
 *       Fixed by {@code @Transactional(propagation = REQUIRES_NEW)} on {@code calculateScore()}.</li>
 *   <li><b>Non-atomic upsert race:</b> The old read-then-write in {@code upsertMatchScoreCache()}
 *       could produce a {@code DataIntegrityViolationException} when two threads scored the
 *       same pair concurrently, because both saw an empty cache and both attempted an INSERT.
 *       Fixed by replacing the pattern with a native {@code INSERT … ON CONFLICT DO UPDATE}.</li>
 * </ol>
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class ScoreCacheConcurrencyTest {

    @Autowired
    private MatchScoringService matchScoringService;

    @Autowired
    private UserMatchScoreRepository userMatchScoreRepository;

    @Autowired
    private UserJpaRepository userRepository;

    private UserEntity userA;
    private UserEntity userB;

    @BeforeEach
    void setUp() {
        userA = userRepository.findByEmail("score.cache.test.a@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("score.cache.test.a@test.com")
                                .name("ScoreCacheTestA")
                                .build()));

        userB = userRepository.findByEmail("score.cache.test.b@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("score.cache.test.b@test.com")
                                .name("ScoreCacheTestB")
                                .build()));

        // Clean up any score rows from a previous test run.
        userMatchScoreRepository.deleteByUserId(userA.getId());
    }

    @AfterEach
    void tearDown() {
        userMatchScoreRepository.deleteByUserId(userA.getId());
    }

    // -------------------------------------------------------------------------
    // Test 1 — cache is actually written after calculateScore()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("calculateScore() writes a row to user_match_scores")
    void calculateScore_persistsToCache() {
        MatchScore score = matchScoringService.calculateScore(userA, userB);

        assertNotNull(score, "Score must not be null");
        assertTrue(score.getOverallScore() >= 0.0 && score.getOverallScore() <= 100.0,
                "Score must be in [0, 100]");

        // Verify the cache row was created (this was the main silent failure before the fix).
        assertTrue(
                userMatchScoreRepository
                        .findByUserIdAndMatchedUserId(userA.getId(), userB.getId())
                        .isPresent(),
                "A user_match_scores row must exist after calculateScore() — " +
                "if missing, the REQUIRES_NEW fix did not take effect");
    }

    // -------------------------------------------------------------------------
    // Test 2 — concurrent calls produce exactly one cache row, no exceptions
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Concurrent calculateScore() for same pair: exactly one cache row, zero exceptions")
    void concurrentCalculateScore_exactlyOneCacheRow() throws Exception {
        int threadCount = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        // Barrier forces all threads to start the call at the same instant.
        CyclicBarrier barrier = new CyclicBarrier(threadCount);

        List<Future<MatchScore>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                barrier.await();
                return matchScoringService.calculateScore(userA, userB);
            }));
        }

        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        int errorCount = 0;
        for (Future<MatchScore> future : futures) {
            try {
                MatchScore result = future.get();
                assertNotNull(result, "calculateScore must never return null");
                assertTrue(result.getOverallScore() >= 0.0 && result.getOverallScore() <= 100.0,
                        "Score must be in [0, 100]");
            } catch (ExecutionException e) {
                System.err.println("Thread threw an unexpected exception: " + e.getCause());
                errorCount++;
            }
        }

        assertEquals(0, errorCount,
                "No thread should throw during concurrent score calculation");

        // The unique constraint (user_id, matched_user_id) guarantees at most one row.
        // isPresent() therefore means exactly one row — the ON CONFLICT DO UPDATE
        // serialised all concurrent writers correctly.
        assertTrue(
                userMatchScoreRepository
                        .findByUserIdAndMatchedUserId(userA.getId(), userB.getId())
                        .isPresent(),
                "Exactly one cache row must exist after concurrent scoring");
    }
}
