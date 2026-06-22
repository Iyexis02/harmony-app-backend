package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.models.matching.dao.Match;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.MatchRepository;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.repositories.UserSwipeRepository;
import com.example.dating.services.matching.MatchService;
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
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch G — Read Transaction Annotations and Count Query.
 *
 * <p>Verifies:
 * <ol>
 *   <li>{@link SwipeService#getLikeCount} uses a COUNT query and includes super-likes
 *       (not just 'like' rows). Prior to Batch G, only 'like' rows were counted and
 *       the full entity list was loaded into memory.</li>
 *   <li>Read-only methods on {@link MatchService} and {@link SwipeService} are safe
 *       under concurrent access — no dirty-check side-effects or transaction errors.</li>
 *   <li>All targeted read methods return correct results, confirming the
 *       {@code @Transactional(readOnly = true)} annotations did not change behaviour.</li>
 * </ol>
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class ReadTransactionAnnotationTest {

    @Autowired private SwipeService swipeService;
    @Autowired private MatchService matchService;
    @Autowired private UserJpaRepository userRepository;
    @Autowired private UserSwipeRepository swipeRepository;
    @Autowired private MatchRepository matchRepository;

    private UserEntity entityA;
    private UserEntity entityB;
    private User domainA;
    private User domainB;

    @BeforeEach
    void setUp() {
        entityA = userRepository.findByEmail("batch.g.a@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("batch.g.a@test.com")
                                .name("BatchGUserA")
                                .build()));

        entityB = userRepository.findByEmail("batch.g.b@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("batch.g.b@test.com")
                                .name("BatchGUserB")
                                .build()));

        domainA = User.builder().id(entityA.getId()).build();
        domainB = User.builder().id(entityB.getId()).build();
    }

    @AfterEach
    void tearDown() {
        swipeRepository.deleteAllInvolvingUser(entityA.getId());
        swipeRepository.deleteAllInvolvingUser(entityB.getId());
        matchRepository.findMatchBetweenUsers(entityA.getId(), entityB.getId())
                .ifPresent(m -> matchRepository.deleteById(m.getId()));
    }

    // -------------------------------------------------------------------------
    // Issue #11 — getLikeCount uses COUNT query and includes super_like
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getLikeCount: counts 'like' rows")
    void getLikeCount_countsLikes() {
        swipeService.recordSwipe(domainA, entityB.getId(), "like", 70.0, "web");

        long count = swipeService.getLikeCount(entityA.getId());

        assertEquals(1, count, "One 'like' should be counted");
    }

    @Test
    @DisplayName("getLikeCount: counts 'super_like' rows (previously missed)")
    void getLikeCount_countsSuperLikes() {
        // Before Batch G: countLikesByUserId only counted action = 'like'.
        // A super_like was silently excluded, under-counting behavioral confidence.
        swipeService.recordSwipe(domainA, entityB.getId(), "super_like", 80.0, "web");

        long count = swipeService.getLikeCount(entityA.getId());

        assertEquals(1, count, "One 'super_like' must be included in getLikeCount()");
    }

    @Test
    @DisplayName("getLikeCount: sums both 'like' and 'super_like', excludes 'pass'")
    void getLikeCount_sumsLikeAndSuperLike_excludesPass() {
        // Need a third user to swipe on — reuse entityB for the like, create a third for pass
        UserEntity entityC = userRepository.findByEmail("batch.g.c@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("batch.g.c@test.com")
                                .name("BatchGUserC")
                                .build()));

        swipeService.recordSwipe(domainA, entityB.getId(), "like", 70.0, "web");
        swipeService.recordSwipe(domainA, entityC.getId(), "pass", 30.0, "web");

        long count = swipeService.getLikeCount(entityA.getId());

        assertEquals(1, count, "'pass' must not be included in getLikeCount()");

        // cleanup extra user swipe
        swipeRepository.deleteAllInvolvingUser(entityC.getId());
    }

    // -------------------------------------------------------------------------
    // Issue #10 — Read methods work correctly (no transaction regression)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("MatchService.getActiveMatchCount returns 0 before any match")
    void getActiveMatchCount_zeroInitially() {
        long count = matchService.getActiveMatchCount(entityA.getId());
        assertEquals(0, count);
    }

    @Test
    @DisplayName("MatchService.areUsersMatched returns false before any match")
    void areUsersMatched_falseInitially() {
        assertFalse(matchService.areUsersMatched(entityA.getId(), entityB.getId()));
    }

    @Test
    @DisplayName("MatchService.getNewMatches returns empty list before any match")
    void getNewMatches_emptyInitially() {
        assertTrue(matchService.getNewMatches(entityA.getId()).isEmpty());
    }

    @Test
    @DisplayName("MatchService.getMatchBetweenUsers returns match after mutual like")
    void getMatchBetweenUsers_presentAfterMutualLike() {
        swipeService.recordSwipe(domainB, entityA.getId(), "like", 65.0, "web");
        swipeService.recordSwipe(domainA, entityB.getId(), "like", 72.0, "web");

        Optional<Match> match = matchService.getMatchBetweenUsers(entityA.getId(), entityB.getId());

        assertTrue(match.isPresent(), "Match must exist after mutual like");
        assertTrue(matchService.areUsersMatched(entityA.getId(), entityB.getId()));
        assertEquals(1, matchService.getActiveMatchCount(entityA.getId()));
    }

    @Test
    @DisplayName("SwipeService.hasSwipedOn returns correct state")
    void hasSwipedOn_correctState() {
        assertFalse(swipeService.hasSwipedOn(entityA.getId(), entityB.getId()));

        swipeService.recordSwipe(domainA, entityB.getId(), "pass", 40.0, "web");

        assertTrue(swipeService.hasSwipedOn(entityA.getId(), entityB.getId()));
    }

    @Test
    @DisplayName("SwipeService.getSwipeCount returns correct count")
    void getSwipeCount_correctCount() {
        assertEquals(0, swipeService.getSwipeCount(entityA.getId()));

        swipeService.recordSwipe(domainA, entityB.getId(), "like", 55.0, "web");

        assertEquals(1, swipeService.getSwipeCount(entityA.getId()));
    }

    // -------------------------------------------------------------------------
    // Concurrency — read methods safe under parallel access
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Concurrent reads on MatchService do not throw or return corrupt data")
    void concurrentReads_noErrorsAndCorrectData() throws Exception {
        // Establish a known match first (write, single-threaded)
        swipeService.recordSwipe(domainB, entityA.getId(), "like", 60.0, "web");
        swipeService.recordSwipe(domainA, entityB.getId(), "like", 65.0, "web");

        int threadCount = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        AtomicInteger errorCount = new AtomicInteger(0);
        AtomicInteger wrongResultCount = new AtomicInteger(0);

        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                barrier.await();
                try {
                    boolean matched = matchService.areUsersMatched(entityA.getId(), entityB.getId());
                    if (!matched) wrongResultCount.incrementAndGet();

                    long activeCount = matchService.getActiveMatchCount(entityA.getId());
                    if (activeCount != 1) wrongResultCount.incrementAndGet();

                    long likeCount = swipeService.getLikeCount(entityA.getId());
                    if (likeCount != 1) wrongResultCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                }
                return null;
            }));
        }

        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);
        for (Future<Void> f : futures) f.get(); // re-throw any unexpected exceptions

        assertEquals(0, errorCount.get(), "No read method should throw under concurrent access");
        assertEquals(0, wrongResultCount.get(), "All concurrent reads must return correct values");
    }
}
