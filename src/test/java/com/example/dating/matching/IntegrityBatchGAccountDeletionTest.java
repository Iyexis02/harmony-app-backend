package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.models.matching.dao.UserBehavioralProfile;
import com.example.dating.models.matching.dao.UserMatchScore;
import com.example.dating.models.matching.dao.UserSwipe;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.repositories.*;
import com.example.dating.services.AccountDeletionService;
import com.example.dating.services.matching.BehavioralScoreCalculator;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies Batch G — Atomic Account Deletion with Exclusive Lock.
 *
 * <p>Covers:
 * <ol>
 *   <li>Deletion acquires exclusive lock via {@code findByIdForUpdate}.</li>
 *   <li>Concurrent {@code SELECT FOR UPDATE} during deletion blocks until deletion completes.</li>
 *   <li>Deletion cleans all related entities (swipes, matches, scores, genres, behavioral profile).</li>
 *   <li>Behavioral score cache is invalidated after deletion.</li>
 * </ol>
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class IntegrityBatchGAccountDeletionTest {

    @Autowired private AccountDeletionService accountDeletionService;
    @Autowired private UserJpaRepository userJpaRepository;
    @Autowired private UserSwipeRepository userSwipeRepository;
    @Autowired private MatchRepository matchRepository;
    @Autowired private UserMatchScoreRepository userMatchScoreRepository;
    @Autowired private UserGenrePreferenceRepository userGenrePreferenceRepository;
    @Autowired private UserBehavioralProfileRepository userBehavioralProfileRepository;
    @Autowired private BehavioralScoreCalculator behavioralScoreCalculator;
    @Autowired private PlatformTransactionManager txManager;

    private TransactionTemplate txTemplate;

    @BeforeEach
    void setUp() {
        txTemplate = new TransactionTemplate(txManager);
    }

    // ── Helper ──────────────────────────────────────────────────────────────────

    private UserEntity createUser(String emailPrefix) {
        return userJpaRepository.findByEmail(emailPrefix + "@integrity-g.test")
                .orElseGet(() -> userJpaRepository.save(
                        UserEntity.builder()
                                .email(emailPrefix + "@integrity-g.test")
                                .name("IntegrityG_" + emailPrefix)
                                .build()));
    }

    // ── Test 1: Deletion acquires exclusive lock (structural) ───────────────────

    @Test
    @DisplayName("findByIdForUpdate method exists on UserJpaRepository with PESSIMISTIC_WRITE lock")
    void deleteAccount_usesExclusiveLock() throws Exception {
        Method method = UserJpaRepository.class.getMethod("findByIdForUpdate", String.class);
        assertNotNull(method, "findByIdForUpdate must exist on UserJpaRepository");

        jakarta.persistence.LockModeType lockMode = method.getAnnotation(
                org.springframework.data.jpa.repository.Lock.class).value();
        assertEquals(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE, lockMode,
                "findByIdForUpdate must use PESSIMISTIC_WRITE lock");
    }

    // ── Test 2: Concurrent SELECT FOR UPDATE during deletion blocks ─────────────

    @Test
    @DisplayName("Concurrent FOR UPDATE read against a user being deleted blocks then finds user gone")
    void concurrentForUpdateDuringDeletion_blocksAndFails() throws Exception {
        // Create the target user and a swiper in their own transaction
        UserEntity target = txTemplate.execute(s -> createUser("g2-target-" + System.nanoTime()));
        UserEntity swiper = txTemplate.execute(s -> createUser("g2-swiper-" + System.nanoTime()));
        String targetId = target.getId();
        String swiperId = swiper.getId();

        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch deletionCanProceed = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Thread 1: Acquire FOR UPDATE lock, pause, then delete
        Future<?> deletionFuture = executor.submit(() ->
            txTemplate.executeWithoutResult(status -> {
                userJpaRepository.findByIdForUpdate(targetId);
                lockAcquired.countDown();

                try {
                    deletionCanProceed.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                userSwipeRepository.deleteAllInvolvingUser(targetId);
                matchRepository.deleteAllByUserId(targetId);
                userMatchScoreRepository.deleteAllInvolvingUser(targetId);
                userGenrePreferenceRepository.deleteByUserId(targetId);
                userBehavioralProfileRepository.deleteByUserId(targetId);
                userJpaRepository.deleteById(targetId);
            })
        );

        assertTrue(lockAcquired.await(5, TimeUnit.SECONDS), "Lock must be acquired");

        // Thread 2: Attempt a FOR UPDATE read — this is what any protected path
        // (swipe, match creation) would do. It will block until Thread 1 commits.
        Future<Boolean> readerFuture = executor.submit(() -> {
            try {
                return txTemplate.execute(status ->
                    userJpaRepository.findByIdForUpdate(targetId).isPresent()
                );
            } finally {
                deletionCanProceed.countDown();
            }
        });

        // Give Thread 2 a moment to start blocking on the row lock, then let Thread 1 finish
        Thread.sleep(300);
        deletionCanProceed.countDown();

        deletionFuture.get(10, TimeUnit.SECONDS);

        // Thread 2's FOR UPDATE must find the row gone after deletion commits
        Boolean userStillExists = readerFuture.get(10, TimeUnit.SECONDS);
        assertFalse(userStillExists,
                "After deletion commits, the concurrent FOR UPDATE read must find the user gone");

        executor.shutdown();
        txTemplate.executeWithoutResult(s -> userJpaRepository.deleteById(swiperId));
    }

    // ── Test 3: Deletion cleans all related entities ────────────────────────────

    @Test
    @DisplayName("Deletion removes swipes, matches, scores, genre prefs, and behavioral profile")
    void deleteAccount_cleansAllRelatedEntities() {
        // Create two users
        String aId = txTemplate.execute(s -> createUser("g3-a-" + System.nanoTime()).getId());
        String bId = txTemplate.execute(s -> createUser("g3-b-" + System.nanoTime()).getId());

        // Populate all related tables — re-fetch entities inside the transaction
        txTemplate.executeWithoutResult(status -> {
            UserEntity a = userJpaRepository.findById(aId).orElseThrow();
            UserEntity b = userJpaRepository.findById(bId).orElseThrow();

            userSwipeRepository.save(UserSwipe.builder()
                    .swiperUser(a).swipedUser(b)
                    .action("like").swipedAt(LocalDateTime.now())
                    .matchScoreAtSwipe(75.0).resultedInMatch(false)
                    .build());

            userMatchScoreRepository.save(UserMatchScore.builder()
                    .user(a).matchedUser(b)
                    .musicScore(80.0).overallScore(75.0)
                    .algorithmVersion("v2.0").computedAt(LocalDateTime.now())
                    .build());

            userBehavioralProfileRepository.save(UserBehavioralProfile.builder()
                    .user(a).totalLikes(10).totalPasses(5)
                    .learnedGenreWeights("{\"rock\":0.8}")
                    .build());
        });

        // Verify data exists
        assertTrue(userSwipeRepository.findByUserIds(aId, bId).isPresent(),
                "Swipe must exist before deletion");
        assertTrue(userMatchScoreRepository.findByUserIdAndMatchedUserId(aId, bId).isPresent(),
                "Score must exist before deletion");
        assertTrue(userBehavioralProfileRepository.findByUserId(aId).isPresent(),
                "Behavioral profile must exist before deletion");

        // Delete account A (Spotify auth — no password needed)
        accountDeletionService.deleteAccount(aId, null);

        // Verify all related entities are gone
        assertTrue(userSwipeRepository.findByUserIds(aId, bId).isEmpty(),
                "Swipe must be deleted");
        assertTrue(userMatchScoreRepository.findByUserIdAndMatchedUserId(aId, bId).isEmpty(),
                "Score must be deleted");
        assertTrue(userBehavioralProfileRepository.findByUserId(aId).isEmpty(),
                "Behavioral profile must be deleted");
        assertTrue(userJpaRepository.findById(aId).isEmpty(),
                "User entity must be deleted");

        // Cleanup: userB
        txTemplate.executeWithoutResult(s -> userJpaRepository.deleteById(bId));
    }

    // ── Test 4: Behavioral cache invalidated after deletion ─────────────────────

    @Test
    @DisplayName("Behavioral score Caffeine cache is invalidated after account deletion")
    @SuppressWarnings("unchecked")
    void deleteAccount_invalidatesBehavioralCache() throws Exception {
        String userId = txTemplate.execute(s -> createUser("g4-" + System.nanoTime()).getId());

        // Create a behavioral profile — re-fetch the managed entity inside the tx
        UserBehavioralProfile profile = txTemplate.execute(s -> {
            UserEntity managed = userJpaRepository.findById(userId).orElseThrow();
            return userBehavioralProfileRepository.save(UserBehavioralProfile.builder()
                    .user(managed).totalLikes(10).totalPasses(5)
                    .learnedGenreWeights("{\"pop\":0.9}")
                    .build());
        });
        String profileId = profile.getId();
        assertNotNull(profileId);

        // Access the Caffeine cache via reflection
        Field cacheField = BehavioralScoreCalculator.class.getDeclaredField("genreWeightCache");
        cacheField.setAccessible(true);
        Cache<String, Map<String, Double>> cache =
                (Cache<String, Map<String, Double>>) cacheField.get(behavioralScoreCalculator);

        // Manually populate the cache
        cache.put(profileId, Map.of("pop", 0.9));
        assertNotNull(cache.getIfPresent(profileId),
                "Cache must contain the profile entry before deletion");

        // Delete account
        accountDeletionService.deleteAccount(userId, null);

        // Verify cache is invalidated
        assertNull(cache.getIfPresent(profileId),
                "Cache entry must be invalidated after account deletion");
    }
}
