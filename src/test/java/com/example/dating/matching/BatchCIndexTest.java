package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.enums.user.AuthProvider;
import com.example.dating.enums.user.RegistrationStage;
import com.example.dating.models.matching.dao.UserMatchScore;
import com.example.dating.models.matching.dao.UserSwipe;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.repositories.MatchRepository;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.repositories.UserMatchScoreRepository;
import com.example.dating.repositories.UserSwipeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Batch C — Missing Database Indexes
 *
 * <p>Verifies that all five indexes introduced in Batch C:
 * <ol>
 *   <li>{@code idx_dob}          — users.dob</li>
 *   <li>{@code idx_gender}       — users.gender</li>
 *   <li>{@code idx_swiped_action}— user_swipes(swiped_user_id, action)</li>
 *   <li>{@code idx_user_version} — user_match_scores(user_id, algorithm_version)</li>
 *   <li>{@code idx_status_matched_at} — matches(status, matched_at)</li>
 * </ol>
 * are present in PostgreSQL after application start-up, and that the two hot-path
 * queries ({@code hasUserLiked} and {@code findAllByUserIdAndVersion}) return
 * correct results under concurrent load.
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class BatchCIndexTest {

    // -----------------------------------------------------------------------
    // Infrastructure
    // -----------------------------------------------------------------------

    @Autowired private JdbcTemplate jdbc;

    @Autowired private UserJpaRepository      userRepository;
    @Autowired private UserSwipeRepository    swipeRepository;
    @Autowired private UserMatchScoreRepository matchScoreRepository;
    @Autowired private MatchRepository        matchRepository;

    // IDs written during @BeforeEach; cleaned up in @AfterEach
    private String swiperUserId;
    private String swipedUserId;
    private final List<String> swipeIds      = new ArrayList<>();
    private final List<String> matchScoreIds = new ArrayList<>();

    // -----------------------------------------------------------------------
    // Setup / Teardown
    // -----------------------------------------------------------------------

    @BeforeEach
    void createTestUsers() {
        UserEntity swiper = userRepository.save(UserEntity.builder()
                .email("batchc-swiper-" + UUID.randomUUID() + "@test.invalid")
                .authProvider(AuthProvider.EMAIL)
                .emailVerified(false)
                .tokenVersion(0)
                .registrationStage(RegistrationStage.FINISHED)
                .premiumStatus(false)
                .build());
        swiperUserId = swiper.getId();

        UserEntity swiped = userRepository.save(UserEntity.builder()
                .email("batchc-swiped-" + UUID.randomUUID() + "@test.invalid")
                .authProvider(AuthProvider.EMAIL)
                .emailVerified(false)
                .tokenVersion(0)
                .registrationStage(RegistrationStage.FINISHED)
                .premiumStatus(false)
                .build());
        swipedUserId = swiped.getId();
    }

    @AfterEach
    void cleanup() {
        // Remove match scores first (FK-safe order)
        matchScoreIds.forEach(id -> matchScoreRepository.deleteById(id));
        matchScoreIds.clear();

        swipeIds.forEach(id -> swipeRepository.deleteById(id));
        swipeIds.clear();

        // Remove test users (cascade will clean sub-entities)
        if (swiperUserId != null) userRepository.deleteById(swiperUserId);
        if (swipedUserId != null) userRepository.deleteById(swipedUserId);

        swiperUserId = null;
        swipedUserId = null;
    }

    // -----------------------------------------------------------------------
    // Helper: reload UserEntity reference within same session
    // -----------------------------------------------------------------------

    private UserEntity findUser(String id) {
        return userRepository.findById(id).orElseThrow();
    }

    private UserSwipe saveSwipe(String swiperId, String swipedId, String action) {
        UserSwipe s = swipeRepository.save(UserSwipe.builder()
                .swiperUser(findUser(swiperId))
                .swipedUser(findUser(swipedId))
                .action(action)
                .resultedInMatch(false)
                .build());
        swipeIds.add(s.getId());
        return s;
    }

    private void saveMatchScore(String userId, String matchedUserId, String version) {
        String id = UUID.randomUUID().toString();
        matchScoreRepository.upsertScore(
                id, userId, matchedUserId,
                70.0, 65.0, 60.0, 80.0, 55.0, 66.0,
                version, LocalDateTime.now(), null, null);
        // Resolve actual ID for cleanup (upsert may have reused an existing row)
        matchScoreRepository.findByUserIdAndMatchedUserId(userId, matchedUserId)
                .map(UserMatchScore::getId)
                .ifPresent(matchScoreIds::add);
    }

    // -----------------------------------------------------------------------
    // Index existence tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("idx_dob exists on the users table")
    void idx_dob_exists() {
        assertIndexExists("idx_dob", "users");
    }

    @Test
    @DisplayName("idx_gender exists on the users table")
    void idx_gender_exists() {
        assertIndexExists("idx_gender", "users");
    }

    @Test
    @DisplayName("idx_swiped_action composite index exists on user_swipes")
    void idx_swiped_action_exists() {
        assertIndexExists("idx_swiped_action", "user_swipes");
    }

    @Test
    @DisplayName("idx_user_version composite index exists on user_match_scores")
    void idx_user_version_exists() {
        assertIndexExists("idx_user_version", "user_match_scores");
    }

    @Test
    @DisplayName("idx_status_matched_at composite index exists on matches")
    void idx_status_matched_at_exists() {
        assertIndexExists("idx_status_matched_at", "matches");
    }

    /**
     * Verifies that ALL five Batch C indexes are present in a single query so a
     * single failure message names every missing index at once.
     */
    @Test
    @DisplayName("All five Batch C indexes are present in PostgreSQL")
    void all_batch_c_indexes_present() {
        List<String> required = List.of(
                "idx_dob",
                "idx_gender",
                "idx_swiped_action",
                "idx_user_version",
                "idx_status_matched_at");

        List<String> found = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE indexname = ANY(?)",
                String.class,
                new Object[]{required.toArray(new String[0])});

        List<String> missing = required.stream()
                .filter(name -> !found.contains(name))
                .toList();

        assertTrue(missing.isEmpty(),
                "Missing Batch C indexes: " + missing);
    }

    // -----------------------------------------------------------------------
    // Correctness test: hasUserLiked — served by idx_swiped_action
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("hasUserLiked returns false when no swipe exists")
    void hasUserLiked_noSwipe_returnsFalse() {
        assertFalse(swipeRepository.hasUserLiked(swiperUserId, swipedUserId));
    }

    @Test
    @DisplayName("hasUserLiked returns true after a 'like' swipe")
    void hasUserLiked_afterLike_returnsTrue() {
        saveSwipe(swiperUserId, swipedUserId, "like");
        assertTrue(swipeRepository.hasUserLiked(swiperUserId, swipedUserId));
    }

    @Test
    @DisplayName("hasUserLiked returns true after a 'super_like' swipe")
    void hasUserLiked_afterSuperLike_returnsTrue() {
        saveSwipe(swiperUserId, swipedUserId, "super_like");
        assertTrue(swipeRepository.hasUserLiked(swiperUserId, swipedUserId));
    }

    @Test
    @DisplayName("hasUserLiked returns false after a 'pass' swipe")
    void hasUserLiked_afterPass_returnsFalse() {
        saveSwipe(swiperUserId, swipedUserId, "pass");
        assertFalse(swipeRepository.hasUserLiked(swiperUserId, swipedUserId));
    }

    @Test
    @DisplayName("hasUserLiked returns false after a 'block' swipe")
    void hasUserLiked_afterBlock_returnsFalse() {
        saveSwipe(swiperUserId, swipedUserId, "block");
        assertFalse(swipeRepository.hasUserLiked(swiperUserId, swipedUserId));
    }

    // -----------------------------------------------------------------------
    // Correctness test: findAllByUserIdAndVersion — served by idx_user_version
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("findAllByUserIdAndVersion returns only scores for the requested version")
    void findAllByUserIdAndVersion_filtersOnVersion() {
        saveMatchScore(swiperUserId, swipedUserId, "v2.0");

        // Scores for the correct version
        List<UserMatchScore> v2Scores = matchScoreRepository
                .findAllByUserIdAndVersion(swiperUserId, "v2.0");
        assertFalse(v2Scores.isEmpty(), "Should find at least one v2.0 score");
        v2Scores.forEach(s -> assertEquals("v2.0", s.getAlgorithmVersion()));

        // No scores for a version that was never written
        List<UserMatchScore> v99Scores = matchScoreRepository
                .findAllByUserIdAndVersion(swiperUserId, "v99.0");
        assertTrue(v99Scores.isEmpty(), "Should return empty for unknown version");
    }

    // -----------------------------------------------------------------------
    // Concurrent correctness: hasUserLiked under parallel reads
    //
    // Demonstrates the hot path (idx_swiped_action) is safe under concurrent
    // read pressure.  All 20 threads must observe the same result; any race
    // condition or connection-pool starvation would surface here.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("hasUserLiked is correct under 20 concurrent reads (idx_swiped_action hot path)")
    void hasUserLiked_concurrentReads_allCorrect() throws Exception {
        // Persist a single 'like' so the expected answer is `true`
        saveSwipe(swiperUserId, swipedUserId, "like");

        int threads = 20;
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger trueCount  = new AtomicInteger(0);
        AtomicInteger falseCount = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final String swiper = swiperUserId;
            final String swiped = swipedUserId;
            futures.add(pool.submit(() -> {
                try {
                    startGate.await();         // all threads start simultaneously
                    if (swipeRepository.hasUserLiked(swiper, swiped)) {
                        trueCount.incrementAndGet();
                    } else {
                        falseCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        startGate.countDown();                 // release all threads at once
        for (Future<?> f : futures) f.get();   // wait for completion / surface exceptions
        pool.shutdown();

        assertEquals(threads, trueCount.get(),
                "All " + threads + " concurrent reads should return true. "
                + "false-count=" + falseCount.get());
    }

    // -----------------------------------------------------------------------
    // Concurrent correctness: findAllByUserIdAndVersion under parallel reads
    //
    // 20 threads each read the score cache for the same user+version pair.
    // Validates that idx_user_version is safe under concurrent access.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("findAllByUserIdAndVersion is correct under 20 concurrent reads (idx_user_version hot path)")
    void findAllByUserIdAndVersion_concurrentReads_allReturnSameCount() throws Exception {
        saveMatchScore(swiperUserId, swipedUserId, "v2.0");

        int expectedSize = matchScoreRepository
                .findAllByUserIdAndVersion(swiperUserId, "v2.0").size();
        assertTrue(expectedSize >= 1, "Pre-condition: at least one score must be present");

        int threads = 20;
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger wrongCount = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final String uid = swiperUserId;
            futures.add(pool.submit(() -> {
                try {
                    startGate.await();
                    int size = matchScoreRepository
                            .findAllByUserIdAndVersion(uid, "v2.0").size();
                    if (size != expectedSize) {
                        wrongCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        startGate.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        assertEquals(0, wrongCount.get(),
                "All concurrent reads must return the same result set size (" + expectedSize + ")");
    }

    // -----------------------------------------------------------------------
    // Internal helper
    // -----------------------------------------------------------------------

    /**
     * Asserts that {@code indexName} exists for {@code tableName} in pg_indexes.
     * Uses the current schema (public) to avoid matching indexes on other schemas.
     */
    private void assertIndexExists(String indexName, String tableName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes "
                + "WHERE schemaname = 'public' AND tablename = ? AND indexname = ?",
                Integer.class,
                tableName, indexName);
        assertEquals(1, count,
                "Expected index '" + indexName + "' on table '" + tableName + "' to exist. "
                + "Did the application start with ddl-auto=update?");
    }
}
