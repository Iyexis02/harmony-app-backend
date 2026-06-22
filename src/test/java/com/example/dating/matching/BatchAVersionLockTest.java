package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.enums.matching.MatchStatus;
import com.example.dating.models.matching.dao.Match;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.repositories.MatchRepository;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.matching.MatchService;
import jakarta.persistence.Version;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Field;
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
 * Verifies Batch A — Optimistic Locking (@Version) on UserEntity and Match.
 *
 * <p>Covers:
 * <ol>
 *   <li>Structural: {@code UserEntity} has a field annotated with {@link Version}.</li>
 *   <li>Structural: {@code Match} has a field annotated with {@link Version}.</li>
 *   <li>Native INSERT: {@code insertMatchIfAbsent} sets {@code version = 0}.</li>
 *   <li>Concurrent {@code unmatch()}: both threads succeed; exactly one DB write wins per
 *       attempt cycle — no unhandled 500, final status = UNMATCHED.</li>
 *   <li>Concurrent {@code markConversationStarted()}: both threads succeed; final state =
 *       {@code conversationStarted = true}.</li>
 * </ol>
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class BatchAVersionLockTest {

    @Autowired
    private MatchService matchService;

    @Autowired
    private MatchRepository matchRepository;

    @Autowired
    private UserJpaRepository userRepository;

    private UserEntity userA;
    private UserEntity userB;

    @BeforeEach
    void setUp() {
        userA = userRepository.findByEmail("batch.a.lock.a@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("batch.a.lock.a@test.com")
                                .name("BatchALockUserA")
                                .build()));

        userB = userRepository.findByEmail("batch.a.lock.b@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("batch.a.lock.b@test.com")
                                .name("BatchALockUserB")
                                .build()));

        // Clean up any leftover match from a previous run.
        matchRepository.findMatchBetweenUsers(userA.getId(), userB.getId())
                .ifPresent(m -> matchRepository.deleteById(m.getId()));
    }

    @AfterEach
    void tearDown() {
        matchRepository.findMatchBetweenUsers(userA.getId(), userB.getId())
                .ifPresent(m -> matchRepository.deleteById(m.getId()));
    }

    // ── Test 1: structural — UserEntity ──────────────────────────────────────

    @Test
    @DisplayName("UserEntity has exactly one @Version field")
    void userEntity_hasVersionField() {
        long count = countVersionFields(UserEntity.class);
        assertEquals(1, count,
                "UserEntity must have exactly one @Version field (Batch A)");
    }

    // ── Test 2: structural — Match ────────────────────────────────────────────

    @Test
    @DisplayName("Match has exactly one @Version field")
    void match_hasVersionField() {
        long count = countVersionFields(Match.class);
        assertEquals(1, count,
                "Match must have exactly one @Version field (Batch A)");
    }

    // ── Test 3: native INSERT sets version = 0 ────────────────────────────────

    @Test
    @DisplayName("insertMatchIfAbsent initialises version to 0")
    void createMatch_setsVersionToZero() {
        Match match = matchService.createMatch(userA, userB, 80.0);

        Match fromDb = matchRepository.findById(match.getId())
                .orElseThrow(() -> new AssertionError("Match must exist after createMatch"));

        assertNotNull(fromDb.getVersion(), "version column must not be null after insert");
        assertEquals(0L, fromDb.getVersion(),
                "version must be 0 immediately after the native INSERT");
    }

    // ── Test 4: concurrent unmatch — retry absorbs OptimisticLockingFailureException ──

    @Test
    @DisplayName("Concurrent unmatch by both participants: no exception, final status = UNMATCHED")
    void concurrentUnmatch_bothSucceedWithoutException() throws Exception {
        Match match = matchService.createMatch(userA, userB, 70.0);
        String matchId = match.getId();

        int threadCount = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);

        // Both users try to unmatch the same match simultaneously.
        Future<?> futureA = pool.submit(() -> {
            barrier.await();
            matchService.unmatch(matchId, userA.getId());
            return null;
        });
        Future<?> futureB = pool.submit(() -> {
            barrier.await();
            matchService.unmatch(matchId, userB.getId());
            return null;
        });

        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        int errorCount = 0;
        for (Future<?> f : List.of(futureA, futureB)) {
            try {
                f.get();
            } catch (ExecutionException e) {
                System.err.println("Unexpected exception in concurrent unmatch: " + e.getCause());
                errorCount++;
            }
        }

        assertEquals(0, errorCount,
                "Neither thread should throw — the retry wrapper must absorb the OptimisticLockingFailureException");

        Match finalMatch = matchRepository.findById(matchId)
                .orElseThrow(() -> new AssertionError("Match must still exist"));
        assertEquals(MatchStatus.UNMATCHED, finalMatch.getStatus(),
                "Match status must be UNMATCHED after concurrent unmatch");
    }

    // ── Test 5: concurrent markConversationStarted ────────────────────────────

    @Test
    @DisplayName("Concurrent markConversationStarted: no exception, final conversationStarted = true")
    void concurrentMarkConversationStarted_bothSucceedWithoutException() throws Exception {
        Match match = matchService.createMatch(userA, userB, 65.0);
        String matchId = match.getId();

        int threadCount = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                barrier.await();
                matchService.markConversationStarted(matchId);
                return null;
            }));
        }

        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        int errorCount = 0;
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (ExecutionException e) {
                System.err.println("Unexpected exception in concurrent markConversationStarted: " + e.getCause());
                errorCount++;
            }
        }

        assertEquals(0, errorCount,
                "No thread should throw — the retry wrapper must absorb the OptimisticLockingFailureException");

        Match finalMatch = matchRepository.findById(matchId)
                .orElseThrow(() -> new AssertionError("Match must still exist"));
        assertTrue(finalMatch.getConversationStarted(),
                "conversationStarted must be true after concurrent markConversationStarted");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private long countVersionFields(Class<?> clazz) {
        long count = 0;
        for (Field f : clazz.getDeclaredFields()) {
            if (f.isAnnotationPresent(Version.class)) {
                count++;
            }
        }
        return count;
    }
}
