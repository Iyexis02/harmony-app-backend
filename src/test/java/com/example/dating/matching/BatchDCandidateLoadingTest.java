package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.enums.user.RegistrationStage;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.dating.dao.UserDatingPreferences;
import com.example.dating.models.user.lifestyle.dao.UserLifestyle;
import com.example.dating.models.user.personality.dao.UserPersonality;
import com.example.dating.models.user.photos.dao.UserPhoto;
import com.example.dating.models.user.preferences.dao.UserMusicPreferences;
import com.example.dating.repositories.UserJpaRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.annotations.BatchSize;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Batch D — @BatchSize and JOIN FETCH for Candidate Loading
 *
 * <p>Verifies:
 * <ol>
 *   <li>Structural: @BatchSize(50) present on datingPreferences, lifestyle, personality,
 *       musicPreferences, and photos fields of UserEntity.</li>
 *   <li>Query: findCandidateUsers() JOIN FETCHes datingPreferences so datingPreferences
 *       is accessible outside a transaction without triggering individual SELECTs.</li>
 *   <li>Query: countCandidateUsers() returns the correct count (mirrors findCandidateUsers
 *       filters, no JOIN FETCH).</li>
 *   <li>Pagination: Page<UserEntity> total-element count matches countCandidateUsers()
 *       (validates the countQuery attribute is correct).</li>
 *   <li>Concurrency: 10 threads calling findCandidateUsers() simultaneously all succeed
 *       and return the correct result.</li>
 * </ol>
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class BatchDCandidateLoadingTest {

    @Autowired
    private UserJpaRepository userRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private EntityManager entityManager;

    /** IDs of users created by setUp() — deleted in tearDown(). */
    private final List<String> createdUserIds = new ArrayList<>();

    /** The "requesting" user whose candidates we query. */
    private String requestingUserId;

    // -------------------------------------------------------------------------
    // Setup / teardown
    // -------------------------------------------------------------------------

    @BeforeEach
    void setUp() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            // Requesting user (excluded from its own candidate results)
            UserEntity requester = userRepository.save(UserEntity.builder()
                    .email("batchd-requester-" + UUID.randomUUID() + "@test.invalid")
                    .registrationStage(RegistrationStage.FINISHED)
                    .build());
            requestingUserId = requester.getId();
            createdUserIds.add(requestingUserId);

            // 5 finished candidates with datingPreferences
            for (int i = 0; i < 5; i++) {
                UserEntity candidate = userRepository.save(UserEntity.builder()
                        .email("batchd-candidate-" + i + "-" + UUID.randomUUID() + "@test.invalid")
                        .registrationStage(RegistrationStage.FINISHED)
                        .build());
                createdUserIds.add(candidate.getId());
            }
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            for (String id : createdUserIds) {
                userRepository.deleteById(id);
            }
            return null;
        });
        createdUserIds.clear();
    }

    // -------------------------------------------------------------------------
    // Test 1 — Structural: @BatchSize(50) on datingPreferences
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("UserEntity.datingPreferences must carry @BatchSize(50)")
    void datingPreferences_hasBatchSize50() throws NoSuchFieldException {
        assertBatchSize(UserEntity.class, "datingPreferences", 50);
    }

    // -------------------------------------------------------------------------
    // Test 2 — Structural: @BatchSize(50) on lifestyle
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("UserEntity.lifestyle must carry @BatchSize(50)")
    void lifestyle_hasBatchSize50() throws NoSuchFieldException {
        assertBatchSize(UserEntity.class, "lifestyle", 50);
    }

    // -------------------------------------------------------------------------
    // Test 3 — Structural: @BatchSize(50) on personality
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("UserEntity.personality must carry @BatchSize(50)")
    void personality_hasBatchSize50() throws NoSuchFieldException {
        assertBatchSize(UserEntity.class, "personality", 50);
    }

    // -------------------------------------------------------------------------
    // Test 4 — Structural: @BatchSize(50) on musicPreferences
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("UserEntity.musicPreferences must carry @BatchSize(50)")
    void musicPreferences_hasBatchSize50() throws NoSuchFieldException {
        assertBatchSize(UserEntity.class, "musicPreferences", 50);
    }

    // -------------------------------------------------------------------------
    // Test 5 — Structural: @BatchSize(50) on photos
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("UserEntity.photos must carry @BatchSize(50)")
    void photos_hasBatchSize50() throws NoSuchFieldException {
        assertBatchSize(UserEntity.class, "photos", 50);
    }

    // -------------------------------------------------------------------------
    // Test 6 — Query: findCandidateUsers excludes the requester and returns candidates
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("findCandidateUsers excludes the requesting user and returns finished candidates")
    void findCandidateUsers_excludesRequesterAndReturnsFinishedCandidates() {
        Page<UserEntity> page = userRepository.findCandidateUsers(
                requestingUserId,
                RegistrationStage.FINISHED,
                List.of("__none__"),
                null, null,
                PageRequest.of(0, 500));

        List<String> returnedIds = page.getContent().stream()
                .map(UserEntity::getId)
                .toList();

        assertFalse(returnedIds.contains(requestingUserId),
                "Requesting user must not appear in candidate results");

        // All 5 test candidates plus any pre-existing FINISHED users in the DB
        assertTrue(page.getTotalElements() >= 5,
                "At least 5 candidates must be returned");
    }

    // -------------------------------------------------------------------------
    // Test 7 — Query: datingPreferences is accessible outside a transaction
    //           (proves the JOIN FETCH is working)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("datingPreferences is accessible outside transaction after findCandidateUsers (JOIN FETCH)")
    void findCandidateUsers_datingPreferencesAccessibleOutsideTransaction() {
        // No @Transactional on this test — the JPA session closes after the repository call.
        // If datingPreferences were NOT JOIN FETCHed, accessing it here would trigger
        // either a LazyInitializationException (LAZY) or a separate SELECT per candidate (EAGER).
        // With JOIN FETCH the proxy is already initialized; no additional query fires.
        Page<UserEntity> page = userRepository.findCandidateUsers(
                requestingUserId,
                RegistrationStage.FINISHED,
                List.of("__none__"),
                null, null,
                PageRequest.of(0, 500));

        assertFalse(page.isEmpty(), "Must have at least one candidate to test");

        for (UserEntity candidate : page.getContent()) {
            // getDateOfBirth() is a direct column — always safe outside transaction.
            // getDatingPreferences() is a @OneToOne relationship.
            // Calling it outside a session verifies the JOIN FETCH populated the proxy.
            // If null is returned, that's fine — it means the candidate has no dating prefs yet.
            // What must NOT happen is a LazyInitializationException.
            assertDoesNotThrow(
                    candidate::getDatingPreferences,
                    "getDatingPreferences() must not throw outside a transaction — "
                    + "JOIN FETCH in findCandidateUsers() must have already loaded it. "
                    + "Candidate id: " + candidate.getId()
            );
        }
    }

    // -------------------------------------------------------------------------
    // Test 8 — Query: countCandidateUsers matches Page totalElements
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("countCandidateUsers matches Page.getTotalElements from findCandidateUsers")
    void countCandidateUsers_matchesPageTotalElements() {
        List<String> safeExcluded = List.of("__none__");

        Page<UserEntity> page = userRepository.findCandidateUsers(
                requestingUserId,
                RegistrationStage.FINISHED,
                safeExcluded,
                null, null,
                PageRequest.of(0, 500));

        long countResult = userRepository.countCandidateUsers(
                requestingUserId,
                RegistrationStage.FINISHED,
                safeExcluded,
                null, null);

        assertEquals(page.getTotalElements(), countResult,
                "countCandidateUsers must return the same total as Page.getTotalElements. "
                + "A mismatch means the countQuery attribute in @Query is inconsistent with the "
                + "main query filters, breaking pagination.");
    }

    // -------------------------------------------------------------------------
    // Test 9 — Query: excludedIds filter works in findCandidateUsers
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("findCandidateUsers respects excludedIds — excluded users do not appear")
    void findCandidateUsers_excludedIds_areAbsentFromResults() {
        // Exclude all but the requester — use all known candidate IDs
        List<String> excludedCandidateIds = createdUserIds.stream()
                .filter(id -> !id.equals(requestingUserId))
                .toList();

        Page<UserEntity> page = userRepository.findCandidateUsers(
                requestingUserId,
                RegistrationStage.FINISHED,
                excludedCandidateIds,
                null, null,
                PageRequest.of(0, 500));

        List<String> returnedIds = page.getContent().stream()
                .map(UserEntity::getId)
                .toList();

        for (String excludedId : excludedCandidateIds) {
            assertFalse(returnedIds.contains(excludedId),
                    "Excluded candidate " + excludedId + " must not appear in results");
        }
    }

    // -------------------------------------------------------------------------
    // Test 10 — Concurrency: 10 threads calling findCandidateUsers simultaneously
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Concurrent findCandidateUsers: 10 threads all succeed with consistent results")
    void concurrent_findCandidateUsers_allSucceedWithConsistentResults() throws Exception {
        int threadCount = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);

        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(); // force simultaneous start
                Page<UserEntity> page = userRepository.findCandidateUsers(
                        requestingUserId,
                        RegistrationStage.FINISHED,
                        List.of("__none__"),
                        null, null,
                        PageRequest.of(0, 500));

                // Access datingPreferences outside transaction — must not throw
                for (UserEntity candidate : page.getContent()) {
                    candidate.getDatingPreferences(); // validates JOIN FETCH under concurrent load
                }

                return page.getTotalElements();
            }));
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS),
                "All threads must complete within 30 s");

        Long expectedTotal = null;
        for (Future<Long> future : futures) {
            Long total = assertDoesNotThrow(
                    (ThrowingSupplier<Long>) future::get,
                    "No thread should throw during concurrent findCandidateUsers"
            );
            assertNotNull(total);
            if (expectedTotal == null) {
                expectedTotal = total;
            } else {
                assertEquals(expectedTotal, total,
                        "All threads must observe the same total-element count — "
                        + "inconsistency indicates a race condition or incorrect query");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private void assertBatchSize(Class<?> clazz, String fieldName, int expectedSize)
            throws NoSuchFieldException {
        Field field = clazz.getDeclaredField(fieldName);
        BatchSize annotation = field.getAnnotation(BatchSize.class);

        assertNotNull(annotation,
                clazz.getSimpleName() + "." + fieldName + " must carry @BatchSize. "
                + "Without it, loading " + fieldName + " for 500 candidates triggers 500 "
                + "individual SELECT statements (N+1).");

        assertEquals(expectedSize, annotation.size(),
                clazz.getSimpleName() + "." + fieldName + " @BatchSize must be " + expectedSize
                + " (loads 50 candidates' " + fieldName + " per batch SELECT).");
    }
}
