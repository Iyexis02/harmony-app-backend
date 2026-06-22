package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.models.matching.dao.Match;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.repositories.MatchRepository;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.matching.MatchService;
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

/**
 * Verifies Batch A — Match Creation Transaction Safety.
 *
 * <p>Two threads (and more) calling {@code MatchService.createMatch()} simultaneously for the
 * same user pair must:
 * <ol>
 *   <li>All return a non-null {@link Match} — no exception thrown by any thread.</li>
 *   <li>Produce exactly one row in the {@code matches} table.</li>
 * </ol>
 *
 * <p>Before this fix, the second thread would catch a {@code DataIntegrityViolationException}
 * inside the {@code @Transactional} method, which marked the transaction rollback-only.
 * The subsequent {@code findMatchBetweenUsers()} call inside the same doomed transaction
 * triggered an {@code UnexpectedRollbackException}, surfacing as a 500 to the caller.
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class MatchCreationConcurrencyTest {

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
        userA = userRepository.findByEmail("concurrency.test.a@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("concurrency.test.a@test.com")
                                .name("ConcurrencyTestA")
                                .build()));

        userB = userRepository.findByEmail("concurrency.test.b@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("concurrency.test.b@test.com")
                                .name("ConcurrencyTestB")
                                .build()));

        // Remove any leftover match from a previous test run.
        matchRepository.findMatchBetweenUsers(userA.getId(), userB.getId())
                .ifPresent(m -> matchRepository.deleteById(m.getId()));
    }

    @AfterEach
    void tearDown() {
        matchRepository.findMatchBetweenUsers(userA.getId(), userB.getId())
                .ifPresent(m -> matchRepository.deleteById(m.getId()));
    }

    @Test
    @DisplayName("Concurrent createMatch for the same pair: exactly one DB row, zero exceptions")
    void concurrentCreateMatch_exactlyOneRowAndNoException() throws Exception {
        int threadCount = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        // Barrier forces all threads to begin the createMatch call at the same instant.
        CyclicBarrier barrier = new CyclicBarrier(threadCount);

        List<Future<Match>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                barrier.await();                                    // synchronise start
                return matchService.createMatch(userA, userB, 75.0);
            }));
        }

        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        int errorCount = 0;
        for (Future<Match> future : futures) {
            try {
                Match result = future.get();
                assertNotNull(result, "createMatch must never return null");
            } catch (ExecutionException e) {
                System.err.println("Thread threw an unexpected exception: " + e.getCause());
                errorCount++;
            }
        }

        assertEquals(0, errorCount,
                "No thread should have thrown an exception during concurrent match creation");

        long rowCount = matchRepository.countActiveMatchesByUserId(userA.getId());
        assertEquals(1, rowCount,
                "Exactly one match row must exist in the database after concurrent inserts");
    }
}
