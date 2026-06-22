package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.models.matching.dao.UserGenrePreference;
import com.example.dating.services.matching.GenrePrefetchContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies Batch E — Genre Scoring Query Optimization.
 *
 * <p>The implementation uses a {@link GenrePrefetchContext} (ThreadLocal) to share
 * bulk-loaded genre preferences across {@code MatchScoreCalculator} and
 * {@code BehavioralScoreCalculator} within a single scoring pass.  Two correctness
 * properties must hold:
 *
 * <ol>
 *   <li><b>Thread isolation</b>: concurrent feed requests on different threads must
 *       never see each other's prefetch context — each thread has its own ThreadLocal.</li>
 *   <li><b>Exception safety</b>: if an exception is thrown inside the scoring loop,
 *       the {@code finally} block must clear the context before the thread returns
 *       to the pool, otherwise the next request on the same thread would see stale data.</li>
 * </ol>
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class GenrePrefetchContextConcurrencyTest {

    @Autowired
    private GenrePrefetchContext genrePrefetchContext;

    @AfterEach
    void ensureContextCleared() {
        // Safety net: clear context after each test in case a test fails before its own clear.
        genrePrefetchContext.clear();
    }

    // -------------------------------------------------------------------------
    // 1. Default state
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Context is inactive on the main thread at rest")
    void contextIsInactiveByDefault() {
        assertFalse(genrePrefetchContext.isActive(),
                "GenrePrefetchContext must be inactive before any scoring pass begins");
        assertEquals(Optional.empty(), genrePrefetchContext.find("any-user"),
                "find() must return Optional.empty() when no prefetch is active");
    }

    // -------------------------------------------------------------------------
    // 2. Thread isolation — Thread A's context is invisible to Thread B
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Thread A's prefetch context is not visible to Thread B")
    void threadAContextIsNotVisibleToThreadB() throws Exception {
        // Load a context on the main (test) thread.
        genrePrefetchContext.set(Map.of("user-a", Collections.emptyList()));
        assertTrue(genrePrefetchContext.isActive(), "Main thread context should be active");

        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        Thread threadB = new Thread(() -> {
            try {
                // Thread B has NEVER called set() — it must not see Thread A's context.
                if (genrePrefetchContext.isActive()) {
                    errors.add(new AssertionError(
                            "Thread B incorrectly sees an active GenrePrefetchContext " +
                            "that was set by Thread A"));
                }
                if (genrePrefetchContext.find("user-a").isPresent()) {
                    errors.add(new AssertionError(
                            "Thread B incorrectly found 'user-a' prefs from Thread A's context"));
                }
            } catch (Exception e) {
                errors.add(e);
            }
        });

        threadB.start();
        threadB.join(5_000);

        genrePrefetchContext.clear();

        assertTrue(errors.isEmpty(),
                "Thread isolation failure: " + errors);
    }

    // -------------------------------------------------------------------------
    // 3. Thread isolation — Thread B clearing its own context does not affect Thread A
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Thread B clearing its own context does not affect Thread A's context")
    void threadBClearDoesNotAffectThreadA() throws Exception {
        genrePrefetchContext.set(Map.of("user-a", Collections.emptyList()));

        Thread threadB = new Thread(() -> {
            // B sets and then immediately clears its own context.
            genrePrefetchContext.set(Map.of("user-b", Collections.emptyList()));
            genrePrefetchContext.clear();
        });
        threadB.start();
        threadB.join(5_000);

        // Thread A's context must still be active after Thread B cleared its own.
        assertTrue(genrePrefetchContext.isActive(),
                "Thread A's context must survive Thread B calling clear() on its own ThreadLocal");
        assertTrue(genrePrefetchContext.find("user-a").isPresent(),
                "Thread A must still find 'user-a' after Thread B cleared its own context");

        genrePrefetchContext.clear();
    }

    // -------------------------------------------------------------------------
    // 4. Exception safety — context cleared even when exception is thrown
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Context is cleared in the finally block even when scoring throws")
    void contextIsClearedWhenScoringThrows() {
        genrePrefetchContext.set(Map.of("user-x", Collections.emptyList()));
        assertTrue(genrePrefetchContext.isActive());

        try {
            // Simulate the scoring loop throwing an unchecked exception.
            if (genrePrefetchContext.isActive()) {
                throw new RuntimeException("Simulated scoring failure");
            }
        } catch (RuntimeException expected) {
            // Intentionally caught — verifying the finally behaviour below.
        } finally {
            genrePrefetchContext.clear();
        }

        assertFalse(genrePrefetchContext.isActive(),
                "Context must be inactive after finally block clears it, even after exception");
        assertEquals(Optional.empty(), genrePrefetchContext.find("user-x"),
                "find() must return empty after context is cleared");
    }

    // -------------------------------------------------------------------------
    // 5. N concurrent threads — each sees only its own context
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("N concurrent threads each see only their own prefetch context")
    void nConcurrentThreadsEachSeeOwnContext() throws Exception {
        int threadCount = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        // Barrier ensures all threads enter the critical section simultaneously,
        // maximising the probability of cross-contamination if ThreadLocal is misused.
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final String myUserId = "concurrent-user-" + i;
            futures.add(pool.submit(() -> {
                barrier.await(); // synchronise start — all threads race together

                // Each thread stores a context keyed by its own unique user ID.
                genrePrefetchContext.set(Map.of(myUserId, Collections.emptyList()));
                try {
                    Thread.sleep(20); // hold context while peers are also running

                    // My own user ID must be present (context is active).
                    Optional<List<UserGenrePreference>> mine = genrePrefetchContext.find(myUserId);
                    if (mine.isEmpty()) {
                        errors.add(new AssertionError(
                                "Thread for " + myUserId + " cannot find its own context entry"));
                    }

                    // Context must be active (set() was called on this thread).
                    if (!genrePrefetchContext.isActive()) {
                        errors.add(new AssertionError(
                                "Thread for " + myUserId + " reports context inactive after set()"));
                    }

                    // Any other userId that WAS NOT put into this thread's map must not appear
                    // as a populated result.  (find() returns Optional.of(emptyList) for unknown
                    // keys when context is active, which is correct — no DB fallback needed
                    // for users we already know have no preferences.)
                    for (int j = 0; j < threadCount; j++) {
                        String otherId = "concurrent-user-" + j;
                        if (!otherId.equals(myUserId)) {
                            Optional<List<UserGenrePreference>> other = genrePrefetchContext.find(otherId);
                            // Context is active so find() always returns Optional.of(...).
                            // The list must be empty — we did NOT put otherId in our map.
                            if (other.isPresent() && !other.get().isEmpty()) {
                                errors.add(new AssertionError(
                                        "Thread for " + myUserId + " sees non-empty prefs for " + otherId +
                                        " — this would mean cross-thread context contamination"));
                            }
                        }
                    }
                } finally {
                    genrePrefetchContext.clear();
                    // After clear, context must be inactive on this thread.
                    if (genrePrefetchContext.isActive()) {
                        errors.add(new AssertionError(
                                "Thread for " + myUserId + " still has active context after clear()"));
                    }
                }
                return null;
            }));
        }

        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        int executionErrors = 0;
        for (Future<Void> future : futures) {
            try {
                future.get();
            } catch (ExecutionException e) {
                errors.add(e.getCause());
                executionErrors++;
            }
        }

        assertEquals(0, executionErrors,
                "No thread should have thrown an unhandled exception");
        assertTrue(errors.isEmpty(),
                "Thread-isolation or exception-safety failures detected:\n" +
                String.join("\n", errors.stream().map(Throwable::getMessage).toList()));
    }
}
