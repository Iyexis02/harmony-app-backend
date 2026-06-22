package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.models.matching.dao.Match;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.dating.dao.UserDatingPreferences;
import com.example.dating.models.user.lifestyle.dao.UserLifestyle;
import com.example.dating.models.user.personality.dao.UserPersonality;
import com.example.dating.models.user.photos.dao.UserPhoto;
import com.example.dating.models.user.preferences.dao.UserMusicPreferences;
import com.example.dating.models.user.privacy.dao.UserPrivacySettings;
import com.example.dating.repositories.MatchRepository;
import com.example.dating.repositories.UserJpaRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies Batch G — Bidirectional Relationship Helpers and Cascade Safety.
 *
 * <h3>Sections</h3>
 * <ol>
 *   <li><strong>Bidirectional @OneToOne helpers</strong> — each custom setter on
 *       {@link UserEntity} syncs the owning side (sub-entity.user) so that the
 *       object graph and the JPA persistence context agree.</li>
 *   <li><strong>Bidirectional @OneToMany helpers</strong> — {@code addPhoto} /
 *       {@code removePhoto} keep the {@code UserPhoto.user} back-reference in sync.</li>
 *   <li><strong>Match ordering invariant — @PrePersist guard</strong> — a direct
 *       {@code matchRepository.save()} with wrong ordering throws
 *       {@code IllegalStateException} before the row reaches the database.</li>
 *   <li><strong>Concurrent invariant guard</strong> — multiple threads that race to
 *       save inverted {@code Match} rows are all rejected; the database stays clean.</li>
 * </ol>
 *
 * <p>Tests 1–8 are pure in-memory (no Spring context needed for assertions, but the
 * full context is available for consistency with the rest of the test suite).
 * Tests 9–12 hit the database via a real JPA transaction.
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BatchGBidirectionalHelperTest {

    @Autowired private UserJpaRepository userRepository;
    @Autowired private MatchRepository   matchRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    /** Lower-id user — will be used as userA in correct-order matches. */
    private UserEntity alpha;
    /** Higher-id user — will be used as userB in correct-order matches. */
    private UserEntity beta;

    @BeforeEach
    void setUp() {
        alpha = userRepository.findByEmail("batch.g.alpha@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("batch.g.alpha@test.com")
                                .name("BatchGAlpha")
                                .build()));

        beta = userRepository.findByEmail("batch.g.beta@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("batch.g.beta@test.com")
                                .name("BatchGBeta")
                                .build()));

        // Clean up any leftover match rows from a previous failed run.
        transactionTemplate.execute(status -> {
            matchRepository.deleteAllByUserId(alpha.getId());
            return null;
        });
    }

    // ── Section 1: @OneToOne bidirectional helpers ────────────────────────────

    @Test
    @Order(1)
    void setLifestyle_syncsBothSides() {
        UserEntity user      = new UserEntity();
        UserLifestyle lifestyle = new UserLifestyle();

        user.setLifestyle(lifestyle);

        assertSame(lifestyle, user.getLifestyle(), "user.lifestyle must point to the new object");
        assertSame(user,      lifestyle.getUser(), "lifestyle.user must back-reference the user");
    }

    @Test
    @Order(2)
    void setLifestyle_withNull_doesNotThrow() {
        UserEntity user = new UserEntity();
        assertDoesNotThrow(() -> user.setLifestyle(null));
        assertNull(user.getLifestyle());
    }

    @Test
    @Order(3)
    void setMusicPreferences_syncsBothSides() {
        UserEntity user              = new UserEntity();
        UserMusicPreferences prefs   = new UserMusicPreferences();

        user.setMusicPreferences(prefs);

        assertSame(prefs, user.getMusicPreferences());
        assertSame(user,  prefs.getUser());
    }

    @Test
    @Order(4)
    void setPersonality_syncsBothSides() {
        UserEntity user          = new UserEntity();
        UserPersonality personality = new UserPersonality();

        user.setPersonality(personality);

        assertSame(personality, user.getPersonality());
        assertSame(user,        personality.getUser());
    }

    @Test
    @Order(5)
    void setDatingPreferences_syncsBothSides() {
        UserEntity user                 = new UserEntity();
        UserDatingPreferences prefs     = new UserDatingPreferences();

        user.setDatingPreferences(prefs);

        assertSame(prefs, user.getDatingPreferences());
        assertSame(user,  prefs.getUser());
    }

    @Test
    @Order(6)
    void setPrivacySettings_syncsBothSides() {
        UserEntity user              = new UserEntity();
        UserPrivacySettings settings = new UserPrivacySettings();

        user.setPrivacySettings(settings);

        assertSame(settings, user.getPrivacySettings());
        assertSame(user,     settings.getUser());
    }

    // ── Section 2: @OneToMany bidirectional helpers ───────────────────────────

    @Test
    @Order(7)
    void addPhoto_syncsBothSides() {
        UserEntity user = UserEntity.builder().email("photosync@test.com").build();
        UserPhoto  photo = new UserPhoto();

        user.addPhoto(photo);

        assertTrue(user.getPhotos().contains(photo), "photo must appear in user.photos");
        assertSame(user, photo.getUser(),             "photo.user must back-reference the user");
    }

    @Test
    @Order(8)
    void removePhoto_syncsBothSides() {
        UserEntity user  = UserEntity.builder().email("photosync@test.com").build();
        UserPhoto  photo = new UserPhoto();

        user.addPhoto(photo);
        user.removePhoto(photo);

        assertFalse(user.getPhotos().contains(photo), "photo must be removed from user.photos");
        assertNull(photo.getUser(),                   "photo.user must be cleared to null");
    }

    // ── Section 3: Match ordering invariant — @PrePersist guard ──────────────

    @Test
    @Order(9)
    void matchRepository_save_throwsWhenUserAIdGreaterThanUserBId() {
        // Guarantee wrong ordering: put the larger-id user as userA.
        final String alphaId = alpha.getId();
        final String betaId  = beta.getId();
        final String wrongAId = alphaId.compareTo(betaId) > 0 ? alphaId : betaId;
        final String wrongBId = alphaId.compareTo(betaId) > 0 ? betaId  : alphaId;

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                transactionTemplate.execute(status -> {
                    UserEntity a = userRepository.getReferenceById(wrongAId);
                    UserEntity b = userRepository.getReferenceById(wrongBId);
                    Match wrongOrder = Match.builder()
                            .userA(a)
                            .userB(b)
                            .matchScore(80.0)
                            .build();
                    matchRepository.saveAndFlush(wrongOrder);
                    return null;
                }));

        assertTrue(containsInvariantViolation(ex),
                "Expected IllegalStateException with 'invariant' in cause chain. Got: "
                + ex.getClass().getName() + " — " + ex.getMessage());
    }

    @Test
    @Order(10)
    void matchRepository_save_succeedsWhenUserAIdLessThanUserBId() {
        // Guarantee correct ordering.
        final String alphaId = alpha.getId();
        final String betaId  = beta.getId();
        final String aId = alphaId.compareTo(betaId) < 0 ? alphaId : betaId;
        final String bId = alphaId.compareTo(betaId) < 0 ? betaId  : alphaId;

        assertDoesNotThrow(() ->
                transactionTemplate.execute(status -> {
                    UserEntity a = userRepository.getReferenceById(aId);
                    UserEntity b = userRepository.getReferenceById(bId);
                    Match correct = Match.builder()
                            .userA(a)
                            .userB(b)
                            .matchScore(75.0)
                            .build();
                    matchRepository.saveAndFlush(correct);
                    // Roll back so the row doesn't persist between tests.
                    status.setRollbackOnly();
                    return null;
                }));
    }

    // ── Section 4: Concurrent invariant guard ─────────────────────────────────

    /**
     * Four threads race to {@code save()} an inverted {@link Match} concurrently.
     * All four must be rejected by the {@code @PrePersist} guard before any SQL
     * reaches the database.
     *
     * <p>Without the guard, at least one thread would succeed and create a row
     * {@code (bigId, smallId)} that cannot be found by the {@code (smallId, bigId)}
     * unique constraint — producing a phantom duplicate pair in the database.
     */
    @Test
    @Order(11)
    void concurrentSaveWithWrongOrdering_allThreadsRejected() throws InterruptedException {
        final String alphaId = alpha.getId();
        final String betaId  = beta.getId();
        final String wrongAId = alphaId.compareTo(betaId) > 0 ? alphaId : betaId;
        final String wrongBId = alphaId.compareTo(betaId) > 0 ? betaId  : alphaId;

        int threadCount = 4;
        ExecutorService pool   = Executors.newFixedThreadPool(threadCount);
        CountDownLatch  ready  = new CountDownLatch(threadCount);
        CountDownLatch  go     = new CountDownLatch(1);
        AtomicInteger   rejected = new AtomicInteger(0);
        List<Future<?>> futures  = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();

                try {
                    transactionTemplate.execute(status -> {
                        UserEntity a = userRepository.getReferenceById(wrongAId);
                        UserEntity b = userRepository.getReferenceById(wrongBId);
                        Match m = Match.builder()
                                .userA(a)
                                .userB(b)
                                .matchScore(50.0)
                                .build();
                        matchRepository.saveAndFlush(m);
                        return null;
                    });
                } catch (RuntimeException e) {
                    if (containsInvariantViolation(e)) {
                        rejected.incrementAndGet();
                    }
                }
                return null;
            }));
        }

        ready.await();
        go.countDown();
        for (Future<?> f : futures) {
            try {
                f.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException ignored) {
                // Unexpected thread failure — the assertion below will catch it.
            }
        }
        pool.shutdown();

        assertEquals(threadCount, rejected.get(),
                "All " + threadCount + " threads must be rejected by the @PrePersist ordering guard");

        // Confirm no inverted row was written.
        assertEquals(0, matchRepository.countActiveMatchesByUserId(wrongAId),
                "No inverted match row must exist in the database after all threads are rejected");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Walks the exception cause chain looking for an {@link IllegalStateException}
     * whose message contains the word "invariant".
     *
     * <p>Hibernate/Spring may wrap the callback exception in a
     * {@code JpaSystemException} or {@code PersistenceException}, so checking
     * only the top-level type is not reliable.
     */
    private static boolean containsInvariantViolation(Throwable t) {
        while (t != null) {
            if (t instanceof IllegalStateException
                    && t.getMessage() != null
                    && t.getMessage().contains("invariant")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            matchRepository.deleteAllByUserId(alpha.getId());
            return null;
        });
    }
}
