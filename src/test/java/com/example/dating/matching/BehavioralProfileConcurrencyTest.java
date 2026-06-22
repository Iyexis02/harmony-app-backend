package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.models.matching.dao.UserBehavioralProfile;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.repositories.UserBehavioralProfileRepository;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.matching.BehavioralProfileService;
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

/**
 * Verifies Batch F — Behavioral Profile Optimistic Locking.
 *
 * <p>Without the {@code @Version} field and retry logic, two concurrent
 * {@code updateAfterSwipe()} calls for the same user both read
 * {@code totalLikes = N}, both compute {@code N+1}, and the second write
 * silently overwrites the first.  The final count is {@code N+1} instead of
 * {@code N+2} — a silent lost-update.
 *
 * <p>With the fix:
 * <ul>
 *   <li>The {@code @Version} column causes Hibernate to throw
 *       {@link org.springframework.dao.OptimisticLockingFailureException} when
 *       the second writer tries to commit with a stale version.</li>
 *   <li>{@code updateAfterSwipe()} catches that exception and retries, re-reading
 *       the now-committed row (version N+1, totalLikes = N+1) and writing
 *       version N+2 with totalLikes = N+2.</li>
 * </ul>
 *
 * <p>The test launches two threads with a {@link CyclicBarrier} so both enter
 * the database read at the same instant, maximising the chance of a version
 * conflict.  After both threads complete we assert {@code totalLikes = 2}.
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class BehavioralProfileConcurrencyTest {

    @Autowired
    private BehavioralProfileService behavioralProfileService;

    @Autowired
    private UserBehavioralProfileRepository behavioralProfileRepository;

    @Autowired
    private UserJpaRepository userRepository;

    private UserEntity swiper;
    private UserEntity swiped;

    @BeforeEach
    void setUp() {
        swiper = userRepository.findByEmail("bp.concurrency.swiper@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("bp.concurrency.swiper@test.com")
                                .name("BpConcurrencySwiper")
                                .build()));

        swiped = userRepository.findByEmail("bp.concurrency.swiped@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("bp.concurrency.swiped@test.com")
                                .name("BpConcurrencySwiped")
                                .build()));

        // Start each test with a clean slate for the swiper's behavioral profile.
        behavioralProfileRepository.findByUserId(swiper.getId())
                .ifPresent(p -> behavioralProfileRepository.deleteById(p.getId()));
    }

    @AfterEach
    void tearDown() {
        behavioralProfileRepository.findByUserId(swiper.getId())
                .ifPresent(p -> behavioralProfileRepository.deleteById(p.getId()));
    }

    @Test
    @DisplayName("Concurrent updateAfterSwipe for same user: totalLikes increments by exactly 2")
    void concurrentBehavioralUpdate_totalLikesIncrementsByTwo() throws Exception {
        int threadCount = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        // Barrier forces both threads to begin the DB read at the same instant,
        // maximising the probability of an optimistic-lock conflict.
        CyclicBarrier barrier = new CyclicBarrier(threadCount);

        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(); // synchronise start
                behavioralProfileService.updateAfterSwipe(
                        swiper.getId(), swiped.getId(), "like", 75.0);
                return null;
            }));
        }

        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        int errorCount = 0;
        for (Future<Void> future : futures) {
            try {
                future.get();
            } catch (ExecutionException e) {
                System.err.println("Thread threw an unexpected exception: " + e.getCause());
                errorCount++;
            }
        }

        assertEquals(0, errorCount,
                "Neither thread should have thrown an unhandled exception");

        UserBehavioralProfile profile = behavioralProfileRepository
                .findByUserId(swiper.getId())
                .orElseThrow(() -> new AssertionError("Behavioral profile must exist after two likes"));

        assertEquals(2, profile.getTotalLikes(),
                "totalLikes must be 2 after two concurrent like updates — " +
                "if it is 1, the optimistic-lock retry is not working correctly");
    }
}
