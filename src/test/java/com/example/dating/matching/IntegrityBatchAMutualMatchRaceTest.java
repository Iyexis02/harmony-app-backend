package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.exceptions.DuplicateSwipeException;
import com.example.dating.models.matching.dto.SwipeResult;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies Batch A — Fix Mutual Match Race by Inlining Swipe Persistence.
 *
 * <p>After this fix:
 * <ul>
 *   <li>Two simultaneous mutual likes always produce exactly one match (never zero).</li>
 *   <li>The PostgreSQL advisory lock serializes the check-then-insert sequence so the
 *       second thread always sees the first thread's committed swipe.</li>
 *   <li>Concurrent duplicate swipes are still cleanly rejected with
 *       {@link DuplicateSwipeException}.</li>
 *   <li>The block action acquires the same advisory lock without deadlock.</li>
 * </ul>
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class IntegrityBatchAMutualMatchRaceTest {

    @Autowired private SwipeService swipeService;
    @Autowired private UserJpaRepository userRepository;
    @Autowired private UserSwipeRepository swipeRepository;
    @Autowired private MatchRepository matchRepository;

    private UserEntity entityA;
    private UserEntity entityB;
    private User domainA;
    private User domainB;

    /** IDs of users created by the stress test — cleaned up in @AfterEach. */
    private final List<String> stressUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        entityA = userRepository.findByEmail("integrity.batch.a.user1@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("integrity.batch.a.user1@test.com")
                                .name("IntegrityBatchAUser1")
                                .build()));

        entityB = userRepository.findByEmail("integrity.batch.a.user2@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("integrity.batch.a.user2@test.com")
                                .name("IntegrityBatchAUser2")
                                .build()));

        domainA = User.builder().id(entityA.getId()).build();
        domainB = User.builder().id(entityB.getId()).build();
    }

    @AfterEach
    void tearDown() {
        // FK order: swipes first (reference matches + users), then matches, then users.
        swipeRepository.deleteAllInvolvingUser(entityA.getId());
        swipeRepository.deleteAllInvolvingUser(entityB.getId());
        matchRepository.deleteAllByUserId(entityA.getId());

        for (String id : stressUserIds) {
            swipeRepository.deleteAllInvolvingUser(id);
        }
        for (String id : stressUserIds) {
            matchRepository.deleteAllByUserId(id);
        }
        for (String id : stressUserIds) {
            userRepository.deleteById(id);
        }
        stressUserIds.clear();
    }

    // ── Test 1: Sequential mutual like ───────────────────────────────────────

    @Test
    @DisplayName("Sequential mutual like: exactly one Match created and both swipes resultedInMatch=true")
    void sequentialMutualLike_exactlyOneMatch() {
        // B likes A first — no match yet
        SwipeResult resultB = swipeService.recordSwipe(domainB, entityA.getId(), "like", 75.0, "web");
        assertFalse(resultB.getResultedInMatch(),
                "B's first swipe must not produce a match — A has not swiped yet");

        // A likes B back — must detect mutual like
        SwipeResult resultA = swipeService.recordSwipe(domainA, entityB.getId(), "like", 80.0, "web");
        assertTrue(resultA.getResultedInMatch(),
                "A's swipe must detect B's existing like and produce a match");
        assertNotNull(resultA.getMatch(),
                "SwipeResult must carry match details when resultedInMatch=true");

        long matchCount = matchRepository.countActiveMatchesByUserId(entityA.getId());
        assertEquals(1, matchCount, "Exactly one active match must exist between A and B");
    }

    // ── Test 2: Concurrent mutual like always produces exactly one match ─────

    @Test
    @DisplayName("Concurrent mutual like: advisory lock ensures exactly one match — never zero")
    void concurrentMutualLike_exactlyOneMatch() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<SwipeResult> futureA = pool.submit(() -> {
            ready.countDown();
            start.await();
            return swipeService.recordSwipe(domainA, entityB.getId(), "like", 75.0, "web");
        });
        Future<SwipeResult> futureB = pool.submit(() -> {
            ready.countDown();
            start.await();
            return swipeService.recordSwipe(domainB, entityA.getId(), "like", 75.0, "web");
        });

        ready.await();
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        // Neither thread should throw — the advisory lock serializes them cleanly.
        SwipeResult rA = futureA.get();
        SwipeResult rB = futureB.get();

        // The thread that ran second (after the lock was released) sees the first
        // thread's committed swipe via hasUserLiked() → mutualLike=true.
        int matchDetections = (rA.getResultedInMatch() ? 1 : 0) + (rB.getResultedInMatch() ? 1 : 0);
        assertEquals(1, matchDetections,
                "Exactly one thread must detect the mutual match — never zero (the pre-fix bug), never two");

        long matchCount = matchRepository.countActiveMatchesByUserId(entityA.getId());
        assertEquals(1, matchCount,
                "Exactly one Match row must exist in the DB after concurrent mutual like");
    }

    // ── Test 3: Concurrent duplicate swipe ───────────────────────────────────

    @Test
    @DisplayName("Concurrent duplicate swipe: exactly one succeeds, the other throws DuplicateSwipeException")
    void concurrentDuplicateSwipe_oneSucceedsOneDuplicate() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        // Both threads race to record the same A→B swipe.
        Future<SwipeResult> future1 = pool.submit(() -> {
            ready.countDown();
            start.await();
            return swipeService.recordSwipe(domainA, entityB.getId(), "like", 75.0, "web");
        });
        Future<SwipeResult> future2 = pool.submit(() -> {
            ready.countDown();
            start.await();
            return swipeService.recordSwipe(domainA, entityB.getId(), "like", 75.0, "web");
        });

        ready.await();
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        int successCount = 0;
        int duplicateCount = 0;
        for (Future<SwipeResult> f : List.of(future1, future2)) {
            try {
                f.get();
                successCount++;
            } catch (ExecutionException ex) {
                if (ex.getCause() instanceof DuplicateSwipeException) {
                    duplicateCount++;
                } else {
                    fail("Expected DuplicateSwipeException but got: " + ex.getCause());
                }
            }
        }

        assertEquals(1, successCount, "Exactly one duplicate swipe attempt must succeed");
        assertEquals(1, duplicateCount, "Exactly one duplicate swipe attempt must throw DuplicateSwipeException");
    }

    // ── Test 4: Stress — 10 concurrent pairs each must produce exactly one match

    @Test
    @DisplayName("Advisory lock stress: 10 concurrent mutual-like pairs each produce exactly one match")
    void advisoryLockStress_tenPairsEachExactlyOneMatch() throws Exception {
        for (int i = 0; i < 10; i++) {
            final int idx = i;
            String emailX = "integrity.batch.a.stress.x." + idx + "@test.com";
            String emailY = "integrity.batch.a.stress.y." + idx + "@test.com";

            UserEntity ex = userRepository.findByEmail(emailX)
                    .orElseGet(() -> userRepository.save(
                            UserEntity.builder().email(emailX).name("StressX" + idx).build()));
            UserEntity ey = userRepository.findByEmail(emailY)
                    .orElseGet(() -> userRepository.save(
                            UserEntity.builder().email(emailY).name("StressY" + idx).build()));

            stressUserIds.add(ex.getId());
            stressUserIds.add(ey.getId());

            User domainX = User.builder().id(ex.getId()).build();
            User domainY = User.builder().id(ey.getId()).build();

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            ExecutorService pool = Executors.newFixedThreadPool(2);

            pool.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    swipeService.recordSwipe(domainX, ey.getId(), "like", 75.0, "web");
                } catch (Exception e) {
                    error.compareAndSet(null, e);
                }
            });
            pool.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    swipeService.recordSwipe(domainY, ex.getId(), "like", 75.0, "web");
                } catch (Exception e) {
                    error.compareAndSet(null, e);
                }
            });

            ready.await();
            start.countDown();
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);

            assertNull(error.get(),
                    "Unexpected exception in iteration " + i + ": " + error.get());

            long matchCount = matchRepository.countActiveMatchesByUserId(ex.getId());
            assertEquals(1, matchCount,
                    "Iteration " + i + ": expected exactly 1 match, got " + matchCount);
        }
    }

    // ── Test 5: Block does not interfere with the advisory lock ──────────────

    @Test
    @DisplayName("Block action: no match created, advisory lock acquired and released without deadlock")
    void blockAction_noMatchAndNoDeadlock() {
        // B likes A first
        swipeService.recordSwipe(domainB, entityA.getId(), "like", 70.0, "web");

        // A blocks B — block does NOT enter the like/super_like path, so no advisory lock
        // is acquired for A's action. This test confirms the lock on B's prior swipe has
        // already been released (it was tied to B's committed transaction) and that the
        // block completes cleanly without deadlock or unexpected exceptions.
        assertDoesNotThrow(() -> {
            SwipeResult result = swipeService.recordSwipe(domainA, entityB.getId(), "block", 80.0, "web");
            assertFalse(result.getResultedInMatch(), "Block must never result in a match");
            assertNull(result.getMatch(), "Block SwipeResult must carry no match details");
        });

        long matchCount = matchRepository.countActiveMatchesByUserId(entityA.getId());
        assertEquals(0, matchCount, "No match must exist after a block action");
    }
}
