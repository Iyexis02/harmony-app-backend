package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.models.matching.dao.CanonicalGenre;
import com.example.dating.enums.matching.GenrePreferenceSource;
import com.example.dating.models.matching.dao.UserGenrePreference;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.photos.dao.UserPhoto;
import com.example.dating.repositories.CanonicalGenreRepository;
import com.example.dating.repositories.UserGenrePreferenceRepository;
import com.example.dating.repositories.UserJpaRepository;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import org.junit.jupiter.api.function.ThrowingSupplier;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Batch B — Fetch Strategy Correction Tests
 *
 * Verifies:
 * 1. UserPhoto.user is FetchType.LAZY (was default EAGER)
 * 2. UserGenrePreference.genre is FetchType.LAZY (was explicit EAGER)
 * 3. The new JOIN FETCH queries load genre data in a single SQL statement so that
 *    pref.getGenre() is safe to call outside a transaction boundary
 * 4. The old non-JOIN-FETCH query produces LazyInitializationException when genre
 *    is accessed outside a transaction (documents the original failure scenario)
 * 5. Concurrent JOIN FETCH loads succeed without any persistence exceptions
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class BatchBFetchStrategyTest {

    @Autowired
    private UserGenrePreferenceRepository preferenceRepository;

    @Autowired
    private CanonicalGenreRepository canonicalGenreRepository;

    @Autowired
    private UserJpaRepository userRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    private String testUserId;
    private String testGenreId;

    // -----------------------------------------------------------------------
    // Test data setup / teardown
    // -----------------------------------------------------------------------

    @BeforeEach
    void setUp() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            UserEntity user = UserEntity.builder()
                    // A unique email avoids unique-constraint conflicts on repeated runs
                    .email("batchb-" + UUID.randomUUID() + "@test.invalid")
                    .build();
            user = userRepository.save(user);
            testUserId = user.getId();

            CanonicalGenre genre = CanonicalGenre.builder()
                    .name("batchb-test-" + UUID.randomUUID())
                    .displayName("Batch B Test Genre")
                    .build();
            genre = canonicalGenreRepository.save(genre);
            testGenreId = genre.getId();

            UserGenrePreference pref = UserGenrePreference.builder()
                    .user(user)
                    .genre(genre)
                    .weight(0.8)
                    .source(GenrePreferenceSource.MANUAL_SELECTION)
                    .build();
            preferenceRepository.save(pref);

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            // Delete in FK-safe order: preferences → genre → user
            preferenceRepository.deleteByUserId(testUserId);
            canonicalGenreRepository.deleteById(testGenreId);
            userRepository.deleteById(testUserId);
            return null;
        });
    }

    // -----------------------------------------------------------------------
    // Test 1 — Annotation check: UserPhoto.user
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("UserPhoto.user must be FetchType.LAZY")
    void userPhotoUser_isFetchTypeLazy() throws NoSuchFieldException {
        Field userField = UserPhoto.class.getDeclaredField("user");
        ManyToOne annotation = userField.getAnnotation(ManyToOne.class);

        assertNotNull(annotation, "UserPhoto.user must carry @ManyToOne");
        assertEquals(
                FetchType.LAZY,
                annotation.fetch(),
                "UserPhoto.user was FetchType.EAGER (JPA default). "
                + "EAGER causes UserEntity to be reloaded for every UserPhoto when a user's "
                + "photos list is loaded — wasted N queries where N = number of photos."
        );
    }

    // -----------------------------------------------------------------------
    // Test 2 — Annotation check: UserGenrePreference.genre
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("UserGenrePreference.genre must be FetchType.LAZY")
    void userGenrePreferenceGenre_isFetchTypeLazy() throws NoSuchFieldException {
        Field genreField = UserGenrePreference.class.getDeclaredField("genre");
        ManyToOne annotation = genreField.getAnnotation(ManyToOne.class);

        assertNotNull(annotation, "UserGenrePreference.genre must carry @ManyToOne");
        assertEquals(
                FetchType.LAZY,
                annotation.fetch(),
                "UserGenrePreference.genre was FetchType.EAGER. "
                + "In the scoring loop (500 candidates × 10 genres each), EAGER caused "
                + "5000+ unnecessary canonical_genres joins per feed request."
        );
    }

    // -----------------------------------------------------------------------
    // Test 3 — JOIN FETCH query makes genre accessible outside a transaction
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("findByUserIdWithGenreOrderByWeightDesc loads genre accessible outside transaction")
    void joinFetchQuery_genreAccessibleOutsideTransaction() {
        // No @Transactional on this test method — the JPA session closes after the repository call.
        // With LAZY genre and no JOIN FETCH this would throw LazyInitializationException.
        List<UserGenrePreference> prefs =
                preferenceRepository.findByUserIdWithGenreOrderByWeightDesc(testUserId);

        assertFalse(prefs.isEmpty(), "Test user must have at least one preference");

        UserGenrePreference pref = prefs.get(0);

        assertDoesNotThrow(
                () -> pref.getGenre().getName(),
                "pref.getGenre().getName() must not throw — genre was JOIN FETCHed in the same query"
        );
        assertDoesNotThrow(
                () -> pref.getGenre().getDisplayName(),
                "pref.getGenre().getDisplayName() must not throw"
        );
        assertEquals("Batch B Test Genre", pref.getGenre().getDisplayName());
    }

    // -----------------------------------------------------------------------
    // Test 4 — Negative: old query without JOIN FETCH throws LazyInitializationException
    //           outside a transaction. This documents the original failure scenario.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("findByUserIdOrderByWeightDesc (no JOIN FETCH) throws LazyInitializationException outside transaction")
    void oldQueryWithoutJoinFetch_throwsLazyInitializationException() {
        // Load preferences WITHOUT JOIN FETCH — the JPA session closes after the call.
        List<UserGenrePreference> prefs =
                preferenceRepository.findByUserIdOrderByWeightDesc(testUserId);

        assertFalse(prefs.isEmpty());

        UserGenrePreference pref = prefs.get(0);

        // Accessing the LAZY proxy outside the session must throw.
        // This is the exact failure that existed before Batch B in every caller that used
        // findByUserIdOrderByWeightDesc and then called pref.getGenre() outside a @Transactional.
        assertThrows(
                LazyInitializationException.class,
                () -> pref.getGenre().getName(),
                "Accessing genre outside a transaction via the non-JOIN-FETCH query must throw "
                + "LazyInitializationException — this confirms the original issue was real."
        );
    }

    // -----------------------------------------------------------------------
    // Test 5 — Concurrent JOIN FETCH loads: 10 threads, no persistence exceptions
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Concurrent JOIN FETCH genre loads: 10 threads, no LazyInitializationException")
    void concurrent_joinFetchLoads_allSucceed() throws Exception {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                // Each thread executes its own repository call (own connection from pool).
                // No shared transaction — validates that JOIN FETCH is safe under concurrent load.
                List<UserGenrePreference> prefs =
                        preferenceRepository.findByUserIdWithGenreOrderByWeightDesc(testUserId);
                assertFalse(prefs.isEmpty());
                // Genre access outside the repository session — must not throw
                return prefs.get(0).getGenre().getName();
            }));
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS),
                "All threads must complete within 30 s");

        for (Future<String> future : futures) {
            // future.get() re-throws any exception thrown inside the Callable as ExecutionException
            assertDoesNotThrow(
                    (ThrowingSupplier<String>) future::get,
                    "No thread should throw a LazyInitializationException or any other exception"
            );
            assertNotNull(future.get(), "Genre name must be non-null in every thread");
        }
    }
}
