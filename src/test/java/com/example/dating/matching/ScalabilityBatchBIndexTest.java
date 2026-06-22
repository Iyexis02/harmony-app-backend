package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.enums.matching.MatchSource;
import com.example.dating.enums.matching.MatchStatus;
import org.springframework.data.domain.PageRequest;
import com.example.dating.enums.user.AuthProvider;
import com.example.dating.enums.user.RegistrationStage;
import com.example.dating.models.matching.dao.Match;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.repositories.MatchRepository;
import com.example.dating.repositories.UserBehavioralProfileRepository;
import com.example.dating.repositories.UserJpaRepository;
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
 * Scalability Batch B — Missing Database Indexes
 *
 * <p>Verifies that all indexes introduced in Scalability Batch B exist in
 * PostgreSQL after application start-up and that the hot-path queries they
 * serve return correct results under concurrent load.
 *
 * <h3>Indexes under test</h3>
 * <ul>
 *   <li>{@code idx_privacy_user}         — user_privacy_settings(user_id)</li>
 *   <li>{@code idx_lifestyle_user}        — user_lifestyle(user_id)</li>
 *   <li>{@code idx_personality_user}      — user_personality(user_id)</li>
 *   <li>{@code idx_music_prefs_user}      — user_music_preferences(user_id)</li>
 *   <li>{@code idx_dating_prefs_user}     — user_dating_preferences(user_id)</li>
 *   <li>{@code idx_behavioral_user}       — user_behavioral_profile(user_id)</li>
 *   <li>{@code idx_match_a_status}        — matches(user_a_id, status)</li>
 *   <li>{@code idx_match_b_status}        — matches(user_b_id, status)</li>
 *   <li>{@code idx_match_a_status_conv}   — matches(user_a_id, status, conversation_started)</li>
 *   <li>{@code idx_match_b_status_conv}   — matches(user_b_id, status, conversation_started)</li>
 * </ul>
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class ScalabilityBatchBIndexTest {

    // -----------------------------------------------------------------------
    // Infrastructure
    // -----------------------------------------------------------------------

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserJpaRepository userRepository;
    @Autowired private MatchRepository matchRepository;
    @Autowired private UserBehavioralProfileRepository behavioralProfileRepository;

    private String userAId;
    private String userBId;
    private final List<String> matchIds = new ArrayList<>();

    // -----------------------------------------------------------------------
    // Setup / Teardown
    // -----------------------------------------------------------------------

    @BeforeEach
    void createTestUsers() {
        UserEntity a = userRepository.save(UserEntity.builder()
                .email("scalb2-a-" + UUID.randomUUID() + "@test.invalid")
                .authProvider(AuthProvider.EMAIL)
                .emailVerified(false)
                .tokenVersion(0)
                .registrationStage(RegistrationStage.FINISHED)
                .premiumStatus(false)
                .build());
        userAId = a.getId();

        UserEntity b = userRepository.save(UserEntity.builder()
                .email("scalb2-b-" + UUID.randomUUID() + "@test.invalid")
                .authProvider(AuthProvider.EMAIL)
                .emailVerified(false)
                .tokenVersion(0)
                .registrationStage(RegistrationStage.FINISHED)
                .premiumStatus(false)
                .build());
        userBId = b.getId();
    }

    @AfterEach
    void cleanup() {
        matchIds.forEach(id -> matchRepository.deleteById(id));
        matchIds.clear();
        if (userAId != null) userRepository.deleteById(userAId);
        if (userBId != null) userRepository.deleteById(userBId);
        userAId = null;
        userBId = null;
    }

    // -----------------------------------------------------------------------
    // Index existence — user sub-entity tables
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("idx_privacy_user exists on user_privacy_settings")
    void idx_privacy_user_exists() {
        assertIndexExists("idx_privacy_user", "user_privacy_settings");
    }

    @Test
    @DisplayName("idx_lifestyle_user exists on user_lifestyle")
    void idx_lifestyle_user_exists() {
        assertIndexExists("idx_lifestyle_user", "user_lifestyle");
    }

    @Test
    @DisplayName("idx_personality_user exists on user_personality")
    void idx_personality_user_exists() {
        assertIndexExists("idx_personality_user", "user_personality");
    }

    @Test
    @DisplayName("idx_music_prefs_user exists on user_music_preferences")
    void idx_music_prefs_user_exists() {
        assertIndexExists("idx_music_prefs_user", "user_music_preferences");
    }

    @Test
    @DisplayName("idx_dating_prefs_user exists on user_dating_preferences")
    void idx_dating_prefs_user_exists() {
        assertIndexExists("idx_dating_prefs_user", "user_dating_preferences");
    }

    @Test
    @DisplayName("idx_behavioral_user exists on user_behavioral_profile")
    void idx_behavioral_user_exists() {
        assertIndexExists("idx_behavioral_user", "user_behavioral_profile");
    }

    // -----------------------------------------------------------------------
    // Index existence — Match composite indexes
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("idx_match_a_status composite index exists on matches")
    void idx_match_a_status_exists() {
        assertIndexExists("idx_match_a_status", "matches");
    }

    @Test
    @DisplayName("idx_match_b_status composite index exists on matches")
    void idx_match_b_status_exists() {
        assertIndexExists("idx_match_b_status", "matches");
    }

    @Test
    @DisplayName("idx_match_a_status_conv composite index exists on matches")
    void idx_match_a_status_conv_exists() {
        assertIndexExists("idx_match_a_status_conv", "matches");
    }

    @Test
    @DisplayName("idx_match_b_status_conv composite index exists on matches")
    void idx_match_b_status_conv_exists() {
        assertIndexExists("idx_match_b_status_conv", "matches");
    }

    /**
     * Single assertion that names every missing index if more than one is absent.
     */
    @Test
    @DisplayName("All Scalability Batch B indexes are present in PostgreSQL")
    void all_scalability_batch_b_indexes_present() {
        List<String> required = List.of(
                "idx_privacy_user",
                "idx_lifestyle_user",
                "idx_personality_user",
                "idx_music_prefs_user",
                "idx_dating_prefs_user",
                "idx_behavioral_user",
                "idx_match_a_status",
                "idx_match_b_status",
                "idx_match_a_status_conv",
                "idx_match_b_status_conv");

        List<String> found = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE indexname = ANY(?)",
                String.class,
                new Object[]{required.toArray(new String[0])});

        List<String> missing = required.stream()
                .filter(name -> !found.contains(name))
                .toList();

        assertTrue(missing.isEmpty(),
                "Missing Scalability Batch B indexes: " + missing);
    }

    // -----------------------------------------------------------------------
    // Correctness test: findMatchesByUserIdAndStatus
    //
    // This is the primary hot-path query served by idx_match_a_status /
    // idx_match_b_status.  Verifies that the query returns correct rows
    // regardless of whether the requesting user is stored as user_a or user_b.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("findMatchesByUserIdAndStatus finds match when user is user_a")
    void findMatchesByStatus_userIsA_found() {
        saveMatch(userAId, userBId, MatchStatus.ACTIVE);

        List<Match> results = matchRepository.findMatchesByUserIdAndStatus(
                userAId, MatchStatus.ACTIVE, PageRequest.of(0, 100));

        assertFalse(results.isEmpty(), "Should find at least one ACTIVE match for user_a");
        assertTrue(results.stream().anyMatch(m ->
                m.getUserA().getId().equals(userAId) || m.getUserB().getId().equals(userAId)),
                "Returned match must involve userA");
    }

    @Test
    @DisplayName("findMatchesByUserIdAndStatus finds match when user is user_b")
    void findMatchesByStatus_userIsB_found() {
        saveMatch(userAId, userBId, MatchStatus.ACTIVE);

        List<Match> results = matchRepository.findMatchesByUserIdAndStatus(
                userBId, MatchStatus.ACTIVE, PageRequest.of(0, 100));

        assertFalse(results.isEmpty(), "Should find at least one ACTIVE match for user_b");
        assertTrue(results.stream().anyMatch(m ->
                m.getUserA().getId().equals(userBId) || m.getUserB().getId().equals(userBId)),
                "Returned match must involve userB");
    }

    @Test
    @DisplayName("findMatchesByUserIdAndStatus filters by status — UNMATCHED not returned for ACTIVE query")
    void findMatchesByStatus_wrongStatus_notReturned() {
        saveMatch(userAId, userBId, MatchStatus.UNMATCHED);

        List<Match> results = matchRepository.findMatchesByUserIdAndStatus(
                userAId, MatchStatus.ACTIVE, PageRequest.of(0, 100));

        assertTrue(results.stream().noneMatch(m ->
                (m.getUserA().getId().equals(userAId) || m.getUserB().getId().equals(userAId))
                && m.getStatus() == MatchStatus.UNMATCHED),
                "An UNMATCHED row must not appear in an ACTIVE query");
    }

    // -----------------------------------------------------------------------
    // Concurrent correctness: findMatchesByUserIdAndStatus under parallel reads
    //
    // 20 threads each read (userAId, ACTIVE) simultaneously.
    // Validates that idx_match_a_status is safe under concurrent access and
    // that all threads observe the same consistent result count.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("findMatchesByUserIdAndStatus is consistent under 20 concurrent reads (idx_match_a_status hot path)")
    void findMatchesByStatus_concurrentReads_allReturnSameCount() throws Exception {
        saveMatch(userAId, userBId, MatchStatus.ACTIVE);

        int expectedSize = matchRepository
                .findMatchesByUserIdAndStatus(userAId, MatchStatus.ACTIVE, PageRequest.of(0, 100)).size();
        assertTrue(expectedSize >= 1, "Pre-condition: at least one ACTIVE match must be present");

        int threads = 20;
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger wrongCount = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final String uid = userAId;
            futures.add(pool.submit(() -> {
                try {
                    startGate.await();
                    int size = matchRepository
                            .findMatchesByUserIdAndStatus(uid, MatchStatus.ACTIVE, PageRequest.of(0, 100)).size();
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
    // Concurrent correctness: findActiveMatchesByUserId under parallel reads
    //
    // Uses the other side of the OR clause (user_b) to exercise idx_match_b_status.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("findActiveMatchesByUserId is consistent under 20 concurrent reads (idx_match_b_status hot path)")
    void findActiveMatchesByUserId_concurrentReads_allReturnSameCount() throws Exception {
        saveMatch(userAId, userBId, MatchStatus.ACTIVE);

        // userBId is stored as user_b in this pair — exercises the _b_ index
        int expectedSize = matchRepository.findActiveMatchesByUserId(userBId, PageRequest.of(0, 1000)).getContent().size();
        assertTrue(expectedSize >= 1, "Pre-condition: at least one ACTIVE match must exist for userB");

        int threads = 20;
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger wrongCount = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final String uid = userBId;
            futures.add(pool.submit(() -> {
                try {
                    startGate.await();
                    int size = matchRepository.findActiveMatchesByUserId(uid, PageRequest.of(0, 1000)).getContent().size();
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
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Saves a match between two users, respecting the (user_a_id < user_b_id)
     * ordering invariant enforced by {@code Match.@PrePersist}.
     */
    private void saveMatch(String id1, String id2, MatchStatus status) {
        // Enforce lexicographic ordering so @PrePersist doesn't throw
        String aId = id1.compareTo(id2) <= 0 ? id1 : id2;
        String bId = id1.compareTo(id2) <= 0 ? id2 : id1;

        UserEntity userA = userRepository.findById(aId).orElseThrow();
        UserEntity userB = userRepository.findById(bId).orElseThrow();

        Match m = matchRepository.save(Match.builder()
                .userA(userA)
                .userB(userB)
                .matchedAt(LocalDateTime.now())
                .matchScore(75.0)
                .status(status)
                .conversationStarted(false)
                .matchSource(MatchSource.MUTUAL_SWIPE)
                .build());
        matchIds.add(m.getId());
    }

    /**
     * Asserts that {@code indexName} exists for {@code tableName} in pg_indexes
     * (public schema).
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
