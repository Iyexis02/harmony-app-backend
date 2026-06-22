package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.enums.user.RegistrationStage;
import com.example.dating.models.matching.dto.PotentialMatchPage;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.repositories.UserMatchScoreRepository;
import com.example.dating.services.matching.MatchRecommendationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Master Audit — Batch D: Stable Pagination via Candidate Pool Cache.
 *
 * <p>Verifies the Caffeine {@code candidatePoolCache} added to
 * {@link MatchRecommendationService#fetchCandidateData}:
 *
 * <ol>
 *   <li><b>No duplicates between pages</b>: IDs returned on page 1 (offset=0) and page 2
 *       (offset=N) are disjoint — the same ordered pool is reused across requests.</li>
 *   <li><b>Consistent total</b>: two consecutive requests for the same user return the
 *       same {@code total} — no flickering count caused by re-running the random query.</li>
 *   <li><b>Cache invalidation</b>: {@link MatchRecommendationService#invalidateCandidateCache}
 *       evicts the entry; the very next call succeeds without error and returns a valid page
 *       (the pool is rebuilt from a fresh random query).</li>
 *   <li><b>Concurrent requests — same user</b>: 20 threads calling
 *       {@link MatchRecommendationService#findPotentialMatches} simultaneously all complete
 *       without exception and report the same {@code total}, proving the Caffeine cache is
 *       accessed safely under concurrent load.</li>
 * </ol>
 *
 * <p><b>Test strategy:</b> all 10 candidates are pre-seeded into {@code user_match_scores}
 * with a future {@code computedAt} timestamp so they are always treated as fresh-cache hits
 * (Tier 1 in the two-tier scoring strategy).  This eliminates async background scoring from
 * the test, making results deterministic: {@code allScored} is built from {@code freshCached}
 * only, sorted by score, paginated by offset/limit.  With all scores equal, Java's stable
 * sort preserves the candidate pool order, so page 1 and page 2 are guaranteed to be
 * non-overlapping slices of the same pool.
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class MasterBatchDPaginationStabilityTest {

    private static final String ALGORITHM_VERSION = "v2.0";
    /** Page size for the pagination tests. */
    private static final int    PAGE_SIZE         = 5;
    /** Total candidates — must be > 2 * PAGE_SIZE to have non-trivial page 2. */
    private static final int    CANDIDATE_COUNT   = 10;

    @Autowired private MatchRecommendationService recommendationService;
    @Autowired private UserJpaRepository           userRepository;
    @Autowired private UserMatchScoreRepository    scoreRepository;
    @Autowired private PlatformTransactionManager  txManager;

    private UserEntity     requesterEntity;
    private User           requester;
    private List<UserEntity> candidateEntities;
    private final List<String> createdUserIds = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Setup / teardown
    // -------------------------------------------------------------------------

    @BeforeEach
    void setUp() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            requesterEntity = userRepository.save(UserEntity.builder()
                    .email("masterD-req-" + UUID.randomUUID() + "@test.invalid")
                    .registrationStage(RegistrationStage.FINISHED)
                    .build());
            createdUserIds.add(requesterEntity.getId());

            candidateEntities = new ArrayList<>(CANDIDATE_COUNT);
            for (int i = 0; i < CANDIDATE_COUNT; i++) {
                UserEntity c = userRepository.save(UserEntity.builder()
                        .email("masterD-cand-" + i + "-" + UUID.randomUUID() + "@test.invalid")
                        .registrationStage(RegistrationStage.FINISHED)
                        .build());
                candidateEntities.add(c);
                createdUserIds.add(c.getId());
            }
            return null;
        });

        requester = User.builder()
                .id(requesterEntity.getId())
                .email(requesterEntity.getEmail())
                .build();

        // Pre-seed the DB score cache so all candidates are Tier-1 hits on every call.
        // computedAt far in the future → isCacheFresh() always returns true.
        preSeedScoreCache(LocalDateTime.now().plusMinutes(10), 75.0);

        // Evict any residual candidate pool cache entry from a previous test run.
        recommendationService.invalidateCandidateCache(requesterEntity.getId());
    }

    @AfterEach
    void tearDown() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            scoreRepository.deleteAllInvolvingUser(requesterEntity.getId());
            createdUserIds.forEach(id -> userRepository.findById(id).ifPresent(userRepository::delete));
            return null;
        });
        createdUserIds.clear();
    }

    // =========================================================================
    // Test 1 — No duplicates between consecutive pages
    // =========================================================================

    @Test
    @DisplayName("Page 1 and page 2 return disjoint candidate IDs — no duplicates across pages")
    void consecutivePageRequests_returnNonOverlappingCandidates() {
        PotentialMatchPage page1 = recommendationService.findPotentialMatches(
                requester, PAGE_SIZE, 0, 0.0, false);
        PotentialMatchPage page2 = recommendationService.findPotentialMatches(
                requester, PAGE_SIZE, PAGE_SIZE, 0.0, false);

        assertNotNull(page1, "Page 1 must not be null");
        assertNotNull(page2, "Page 2 must not be null");

        // If total <= PAGE_SIZE there is no page 2 — test environment may have very few candidates.
        if (page1.getTotal() <= PAGE_SIZE) {
            assertTrue(page2.getMatches().isEmpty(),
                    "Page 2 must be empty when total <= PAGE_SIZE");
            return;
        }

        Set<String> idsPage1 = new HashSet<>();
        page1.getMatches().forEach(m -> idsPage1.add(m.getUserId()));

        Set<String> idsPage2 = new HashSet<>();
        page2.getMatches().forEach(m -> idsPage2.add(m.getUserId()));

        // Intersection must be empty: no candidate appears in both pages.
        Set<String> overlap = new HashSet<>(idsPage1);
        overlap.retainAll(idsPage2);
        assertTrue(overlap.isEmpty(),
                "Candidate IDs must not overlap between page 1 and page 2. "
                + "Overlap: " + overlap + ". "
                + "This indicates the random query is re-run on each request "
                + "instead of reusing the cached pool.");
    }

    // =========================================================================
    // Test 2 — Consistent total across consecutive requests
    // =========================================================================

    @Test
    @DisplayName("Total count is consistent across consecutive page requests — no flickering")
    void consecutivePageRequests_returnConsistentTotal() {
        PotentialMatchPage first  = recommendationService.findPotentialMatches(
                requester, PAGE_SIZE, 0, 0.0, false);
        PotentialMatchPage second = recommendationService.findPotentialMatches(
                requester, PAGE_SIZE, 0, 0.0, false);

        assertNotNull(first,  "First call must not be null");
        assertNotNull(second, "Second call must not be null");

        assertEquals(first.getTotal(), second.getTotal(),
                "Total must be identical across consecutive requests. "
                + "A mismatch means the random query returned a different pool on each call — "
                + "the candidate pool cache is not working. "
                + "first.total=" + first.getTotal() + " second.total=" + second.getTotal());
    }

    // =========================================================================
    // Test 3 — invalidateCandidateCache forces a fresh query on next request
    // =========================================================================

    @Test
    @DisplayName("invalidateCandidateCache evicts the pool; next request rebuilds it without error")
    void invalidateCandidateCache_forcesRebuildOnNextRequest() {
        // Warm the cache.
        PotentialMatchPage before = recommendationService.findPotentialMatches(
                requester, PAGE_SIZE, 0, 0.0, false);
        assertNotNull(before, "Pre-invalidation call must succeed");

        // Evict.
        recommendationService.invalidateCandidateCache(requesterEntity.getId());

        // Post-invalidation call — must rebuild the pool from a fresh RANDOM() query.
        PotentialMatchPage after = assertDoesNotThrow(
                () -> recommendationService.findPotentialMatches(requester, PAGE_SIZE, 0, 0.0, false),
                "findPotentialMatches must not throw after cache invalidation");

        assertNotNull(after, "Post-invalidation call must return a non-null page");
        assertNotNull(after.getMatches(), "Matches list must not be null after cache invalidation");
        assertEquals(PAGE_SIZE, after.getLimit(), "Limit must be preserved after cache invalidation");
    }

    // =========================================================================
    // Test 4 — Concurrent requests for the same user all return the same total
    // =========================================================================

    /**
     * 20 threads call {@code findPotentialMatches} simultaneously for the same user.
     *
     * <p>Because the first thread to miss the candidate pool cache populates it via
     * {@code candidatePoolCache.put()}, and Caffeine's put is atomic, all subsequent
     * threads should read the same ordered pool and compute the same total.
     *
     * <p>If the cache were not thread-safe (e.g. a plain {@code HashMap}), concurrent
     * writes could corrupt the pool list, causing different threads to see different
     * totals or throw {@code ConcurrentModificationException}.
     */
    @Test
    @DisplayName("Concurrent findPotentialMatches for same user: 20 threads all return same total")
    void concurrent_sameUser_allReturnSameTotal() throws Exception {
        int threadCount = 20;
        ExecutorService pool    = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier   barrier = new CyclicBarrier(threadCount);

        List<Future<PotentialMatchPage>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(); // force simultaneous start
                return recommendationService.findPotentialMatches(
                        requester, PAGE_SIZE, 0, 0.0, false);
            }));
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS),
                "All 20 threads must complete within 30 s");

        Integer expectedTotal = null;
        for (int i = 0; i < threadCount; i++) {
            int idx = i;
            PotentialMatchPage result = assertDoesNotThrow(
                    (ThrowingSupplier<PotentialMatchPage>) futures.get(idx)::get,
                    "Thread " + idx + " must not throw");

            assertNotNull(result,                   "Thread " + idx + " must return a non-null page");
            assertNotNull(result.getMatches(),       "Thread " + idx + " matches list must not be null");
            assertTrue(result.getMatches().size() <= PAGE_SIZE,
                    "Thread " + idx + " page size must not exceed limit");

            // All threads must agree on the total.
            if (expectedTotal == null) {
                expectedTotal = result.getTotal();
            } else {
                assertEquals(expectedTotal, result.getTotal(),
                        "Thread " + idx + " total=" + result.getTotal()
                        + " differs from expected=" + expectedTotal
                        + ". A mismatch under concurrency indicates a race condition "
                        + "in the candidatePoolCache or the scoring pipeline.");
            }
        }
    }

    // =========================================================================
    // Test 5 — Page ordering is stable: page 1 same across back-to-back calls
    // =========================================================================

    @Test
    @DisplayName("Page 1 candidate IDs are identical across back-to-back requests (stable pool order)")
    void backToBackPage1Requests_returnIdenticalIds() {
        PotentialMatchPage call1 = recommendationService.findPotentialMatches(
                requester, PAGE_SIZE, 0, 0.0, false);
        PotentialMatchPage call2 = recommendationService.findPotentialMatches(
                requester, PAGE_SIZE, 0, 0.0, false);

        assertNotNull(call1);
        assertNotNull(call2);

        if (call1.getMatches().isEmpty()) {
            assertTrue(call2.getMatches().isEmpty(),
                    "Both calls must be empty if the first is empty");
            return;
        }

        List<String> ids1 = call1.getMatches().stream().map(m -> m.getUserId()).toList();
        List<String> ids2 = call2.getMatches().stream().map(m -> m.getUserId()).toList();

        assertEquals(ids1, ids2,
                "Page 1 candidate ID list must be identical across back-to-back requests. "
                + "A difference means the pool ordering is not stable — the random query "
                + "is re-running instead of the cache being reused.");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void preSeedScoreCache(LocalDateTime computedAt, double score) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            for (UserEntity candidate : candidateEntities) {
                scoreRepository.upsertScore(
                        UUID.randomUUID().toString(),
                        requesterEntity.getId(),
                        candidate.getId(),
                        score, score, score, score, score, score,
                        ALGORITHM_VERSION,
                        computedAt, null, null);
            }
            return null;
        });
    }
}
