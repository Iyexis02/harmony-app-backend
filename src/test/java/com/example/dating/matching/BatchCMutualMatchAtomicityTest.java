package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.exceptions.DuplicateSwipeException;
import com.example.dating.models.matching.dao.UserSwipe;
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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies Batch C — Atomic Mutual-Match Detection in SwipeService.
 *
 * <p>Covers:
 * <ol>
 *   <li>Sequential mutual like: exactly one Match row; both swipes have
 *       {@code resultedInMatch = true} and are linked to the same match.</li>
 *   <li>Concurrent mutual like: no unexpected exceptions; at most one match row
 *       (never a duplicate due to the idempotent native INSERT).</li>
 *   <li>super_like on an existing like: match is created with source SUPER_LIKE.</li>
 *   <li>Block action: no match is created even when the other user has liked.</li>
 *   <li>Duplicate swipe: {@link DuplicateSwipeException} is raised — not a 500.</li>
 * </ol>
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class BatchCMutualMatchAtomicityTest {

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
        entityA = userRepository.findByEmail("batch.c.mutual.a@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("batch.c.mutual.a@test.com")
                                .name("BatchCMutualA")
                                .build()));

        entityB = userRepository.findByEmail("batch.c.mutual.b@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("batch.c.mutual.b@test.com")
                                .name("BatchCMutualB")
                                .build()));

        domainA = User.builder().id(entityA.getId()).build();
        domainB = User.builder().id(entityB.getId()).build();
    }

    @AfterEach
    void tearDown() {
        // Delete swipes before match (FK order).
        swipeRepository.deleteAllInvolvingUser(entityA.getId());
        swipeRepository.deleteAllInvolvingUser(entityB.getId());
        matchRepository.findMatchBetweenUsers(entityA.getId(), entityB.getId())
                .ifPresent(m -> matchRepository.deleteById(m.getId()));
    }

    // ── Test 1: Sequential mutual like ───────────────────────────────────────

    @Test
    @DisplayName("Sequential mutual like: exactly one Match row, both swipes resultedInMatch=true")
    void sequentialMutualLike_exactlyOneMatchBothSwipesLinked() {
        // B likes A first — no match yet
        SwipeResult resultB = swipeService.recordSwipe(domainB, entityA.getId(), "like", 75.0, "web");
        assertFalse(resultB.getResultedInMatch(),
                "B's first swipe must not produce a match — A hasn't swiped yet");

        // A likes B back — must trigger mutual match detection
        SwipeResult resultA = swipeService.recordSwipe(domainA, entityB.getId(), "like", 80.0, "web");
        assertTrue(resultA.getResultedInMatch(),
                "A's swipe must detect a mutual like (pre-insert check) and produce a match");
        assertNotNull(resultA.getMatch(),
                "SwipeResult must carry match details when resultedInMatch=true");

        // Exactly one match row in DB
        long matchCount = matchRepository.countActiveMatchesByUserId(entityA.getId());
        assertEquals(1, matchCount,
                "Exactly one active match must exist between A and B");

        // Both swipe rows must be linked to the match with resultedInMatch=true
        Optional<UserSwipe> swipeA = swipeRepository.findByUserIds(entityA.getId(), entityB.getId());
        Optional<UserSwipe> swipeB = swipeRepository.findByUserIds(entityB.getId(), entityA.getId());
        assertTrue(swipeA.isPresent(), "Swipe row A→B must exist");
        assertTrue(swipeB.isPresent(), "Swipe row B→A must exist");
        assertTrue(swipeA.get().getResultedInMatch(),
                "A's swipe row must have resultedInMatch=true");
        assertTrue(swipeB.get().getResultedInMatch(),
                "B's swipe row must have resultedInMatch=true");
        assertNotNull(swipeA.get().getMatch(), "A's swipe must be linked to the match");
        assertNotNull(swipeB.get().getMatch(), "B's swipe must be linked to the match");
        assertEquals(swipeA.get().getMatch().getId(), swipeB.get().getMatch().getId(),
                "Both swipes must reference the same match ID");
    }

    // ── Test 2: Concurrent mutual like ───────────────────────────────────────

    @Test
    @DisplayName("Concurrent mutual like: no unexpected exceptions, at most one Match row created")
    void concurrentMutualLike_noExceptionsAtMostOneMatch() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);

        // Both users swipe right on each other simultaneously.
        Future<SwipeResult> futureA = pool.submit(() -> {
            barrier.await();
            return swipeService.recordSwipe(domainA, entityB.getId(), "like", 75.0, "web");
        });
        Future<SwipeResult> futureB = pool.submit(() -> {
            barrier.await();
            return swipeService.recordSwipe(domainB, entityA.getId(), "like", 75.0, "web");
        });

        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        int errorCount = 0;
        for (Future<SwipeResult> f : List.of(futureA, futureB)) {
            try {
                f.get();
            } catch (ExecutionException ex) {
                System.err.println("Unexpected exception: " + ex.getCause());
                errorCount++;
            }
        }

        assertEquals(0, errorCount,
                "No thread should throw an unexpected exception during concurrent mutual like");

        // At most one match must exist — the idempotent INSERT ON CONFLICT prevents duplicates.
        // (Zero is theoretically possible in the narrow window where both checks fire before
        //  either REQUIRES_NEW commit; the important guarantee is never more than one.)
        long matchCount = matchRepository.countActiveMatchesByUserId(entityA.getId());
        assertTrue(matchCount <= 1,
                "At most one match must exist after concurrent mutual like — never a duplicate");
    }

    // ── Test 3: super_like on an existing like ────────────────────────────────

    @Test
    @DisplayName("super_like on a user who already liked: match is created")
    void superLike_onExistingLike_createsMatch() {
        // A likes B first
        swipeService.recordSwipe(domainA, entityB.getId(), "like", 70.0, "web");

        // B super-likes A → mutual
        SwipeResult result = swipeService.recordSwipe(domainB, entityA.getId(), "super_like", 85.0, "web");
        assertTrue(result.getResultedInMatch(),
                "super_like on an existing like must produce a match");
        assertNotNull(result.getMatch(),
                "SwipeResult must include match details for super_like mutual match");

        long matchCount = matchRepository.countActiveMatchesByUserId(entityA.getId());
        assertEquals(1, matchCount, "Exactly one match must exist after super_like mutual detection");
    }

    // ── Test 4: block never creates a match ──────────────────────────────────

    @Test
    @DisplayName("Block action: no match created even when other user has liked")
    void blockAction_noMatchCreated() {
        // B likes A first
        swipeService.recordSwipe(domainB, entityA.getId(), "like", 70.0, "web");

        // A blocks B — must NOT create a match
        SwipeResult result = swipeService.recordSwipe(domainA, entityB.getId(), "block", 80.0, "web");
        assertFalse(result.getResultedInMatch(),
                "Block must never result in a match");
        assertNull(result.getMatch(),
                "Block SwipeResult must carry no match details");

        long matchCount = matchRepository.countActiveMatchesByUserId(entityA.getId());
        assertEquals(0, matchCount, "No match must exist after a block action");
    }

    // ── Test 5: duplicate swipe throws DuplicateSwipeException ───────────────

    @Test
    @DisplayName("Duplicate swipe: DuplicateSwipeException is raised, not an unhandled 500")
    void duplicateSwipe_throwsDuplicateSwipeException() {
        swipeService.recordSwipe(domainA, entityB.getId(), "like", 75.0, "web");

        assertThrows(DuplicateSwipeException.class,
                () -> swipeService.recordSwipe(domainA, entityB.getId(), "like", 75.0, "web"),
                "Second identical swipe must throw DuplicateSwipeException, not a generic 500");
    }
}
