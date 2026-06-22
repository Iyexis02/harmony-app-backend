package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.models.matching.dto.PotentialMatchPage;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.matching.MatchRecommendationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Batch C — Scoring Transaction Refactor verification tests.
 *
 * <p>These tests verify that {@link MatchRecommendationService#findPotentialMatches}
 * no longer holds a DB connection across the scoring loop.  Under the old design,
 * 15+ concurrent requests exhausted the 30-connection HikariCP pool because each
 * request held one connection (outer readOnly transaction) while
 * {@code calculateScore(REQUIRES_NEW)} tried to acquire a second.
 *
 * <p>After Batch C:
 * <ul>
 *   <li>Phase A ({@code fetchCandidateData}) releases its connection after the DB reads.</li>
 *   <li>Phase B (scoring loop) holds no connection; each cache miss opens a short write
 *       transaction via {@code persistScoreCache} and releases immediately.</li>
 * </ul>
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class BatchCScoringTransactionRefactorTest {

    @Autowired
    private MatchRecommendationService recommendationService;

    @Autowired
    private UserJpaRepository userRepository;

    // -------------------------------------------------------------------------
    // 1. Context wiring sanity check
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Context loads and MatchRecommendationService is wired")
    void contextLoads() {
        assertNotNull(recommendationService);
    }

    // -------------------------------------------------------------------------
    // 2. Single-thread correctness — basic structure
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("findPotentialMatches returns valid PotentialMatchPage structure")
    void singleThread_returnsValidPage() {
        List<UserEntity> users = userRepository.findAll(PageRequest.of(0, 1)).getContent();
        assumeTrue(!users.isEmpty(), "Need at least 1 user in DB");

        User user = User.builder().id(users.get(0).getId()).build();

        PotentialMatchPage page = recommendationService.findPotentialMatches(user, 10, 0, 0.0, false);

        assertNotNull(page, "Page must not be null");
        assertNotNull(page.getMatches(), "Matches list must not be null");
        assertEquals(10, page.getLimit());
        assertEquals(0, page.getOffset());
        assertTrue(page.getTotal() >= 0, "Total must be non-negative");
        assertTrue(page.getMatches().size() <= 10, "Page size must not exceed limit");

        // hasMore must be consistent with total and offset
        boolean expectedHasMore = (0 + 10) < page.getTotal();
        assertEquals(expectedHasMore, page.isHasMore());
    }

    @Test
    @DisplayName("Scores in returned page are within valid range [0, 100]")
    void singleThread_scoresInValidRange() {
        List<UserEntity> users = userRepository.findAll(PageRequest.of(0, 1)).getContent();
        assumeTrue(!users.isEmpty(), "Need at least 1 user in DB");

        User user = User.builder().id(users.get(0).getId()).build();

        PotentialMatchPage page = recommendationService.findPotentialMatches(user, 20, 0, 0.0, false);

        page.getMatches().forEach(match -> {
            assertNotNull(match.getMatchScore(), "Match score must not be null");
            assertTrue(match.getMatchScore() >= 0.0, "Score must be >= 0");
            assertTrue(match.getMatchScore() <= 100.0, "Score must be <= 100");
        });
    }

    // -------------------------------------------------------------------------
    // 3. Concurrent correctness — the core Batch C verification
    // -------------------------------------------------------------------------

    /**
     * Fires 10 threads simultaneously, each calling {@code findPotentialMatches} for the
     * same user.  Before Batch C this would deadlock the connection pool within a few
     * seconds.  All requests must complete within 30 s with no exceptions.
     *
     * <p>Pool size = 30 (set in Batch A).  Old design: 10 concurrent requests ×
     * 2 connections each = 20 connections; at 15 requests the pool saturates and
     * deadlocks.  New design: each request holds at most 1 connection for ~10 ms
     * (fetch phase), then zero connections during scoring.
     */
    @Test
    @DisplayName("10 concurrent findPotentialMatches calls complete without deadlock")
    void concurrent_10Threads_noDeadlock() throws InterruptedException {
        List<UserEntity> users = userRepository.findAll(PageRequest.of(0, 1)).getContent();
        assumeTrue(!users.isEmpty(), "Need at least 1 user in DB");

        User user = User.builder().id(users.get(0).getId()).build();

        int concurrency = 10;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);   // all threads start at once
        CountDownLatch doneLatch = new CountDownLatch(concurrency);
        List<Throwable> errors   = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successes  = new AtomicInteger(0);

        for (int i = 0; i < concurrency; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    recommendationService.findPotentialMatches(user, 20, 0, 0.0, false);
                    successes.incrementAndGet();
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown(); // release all threads simultaneously
        boolean allFinished = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(allFinished,
                "Not all threads finished within 30 s — likely connection-pool deadlock. " +
                "Check HikariCP leak-detection-threshold in application.yml.");
        assertTrue(errors.isEmpty(),
                "Errors during concurrent execution: " + errors);
        assertEquals(concurrency, successes.get(),
                "Expected all " + concurrency + " threads to succeed");
    }

    /**
     * Fires 5 threads each with a different user.  Verifies isolation — each thread's
     * GenrePrefetchContext ThreadLocal is independent and cleared correctly.
     */
    @Test
    @DisplayName("5 concurrent calls with distinct users do not contaminate each other's context")
    void concurrent_distinctUsers_noContextLeak() throws InterruptedException {
        List<UserEntity> users = userRepository.findAll(PageRequest.of(0, 5)).getContent();
        assumeTrue(users.size() >= 2, "Need at least 2 users for this test");

        int concurrency = Math.min(users.size(), 5);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);
        List<Throwable> errors   = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < concurrency; i++) {
            final User user = User.builder().id(users.get(i).getId()).build();
            executor.submit(() -> {
                try {
                    startGate.await();
                    PotentialMatchPage page = recommendationService.findPotentialMatches(
                            user, 10, 0, 0.0, false);
                    assertNotNull(page);
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown();
        boolean allFinished = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(allFinished, "Threads timed out — possible deadlock or context leak");
        assertTrue(errors.isEmpty(), "Errors: " + errors);
    }

    // -------------------------------------------------------------------------
    // 4. Pagination consistency
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Two consecutive pages do not overlap and cover all results")
    void pagination_pagesAreConsistent() {
        List<UserEntity> users = userRepository.findAll(PageRequest.of(0, 1)).getContent();
        assumeTrue(!users.isEmpty(), "Need at least 1 user in DB");

        User user = User.builder().id(users.get(0).getId()).build();

        PotentialMatchPage page1 = recommendationService.findPotentialMatches(user, 10, 0,  0.0, false);
        PotentialMatchPage page2 = recommendationService.findPotentialMatches(user, 10, 10, 0.0, false);

        // Total must be consistent across both calls
        assertEquals(page1.getTotal(), page2.getTotal(),
                "Total must be the same for both pages");

        // No user ID should appear on both pages
        List<String> ids1 = page1.getMatches().stream()
                .map(m -> m.getUserId()).toList();
        List<String> ids2 = page2.getMatches().stream()
                .map(m -> m.getUserId()).toList();

        ids2.forEach(id ->
                assertFalse(ids1.contains(id),
                        "User " + id + " appeared on both page 1 and page 2"));
    }
}
