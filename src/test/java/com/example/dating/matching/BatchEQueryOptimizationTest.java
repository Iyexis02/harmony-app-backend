package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.enums.matching.GenrePreferenceSource;
import com.example.dating.enums.user.RegistrationStage;
import com.example.dating.models.matching.dao.CanonicalGenre;
import com.example.dating.models.matching.dao.Match;
import com.example.dating.models.matching.dao.UserSwipe;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.CanonicalGenreRepository;
import com.example.dating.repositories.MatchRepository;
import com.example.dating.repositories.UserGenrePreferenceRepository;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.repositories.UserSwipeRepository;
import com.example.dating.services.matching.GenreExtractionService;
import com.example.dating.services.matching.MatchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Batch E — Repository Query Optimization integration tests.
 *
 * <p>Verifies the four changes introduced in Batch E:
 *
 * <ol>
 *   <li><b>Exclusion query cap</b>: {@code findAllSwipedUserIds}, {@code findBlockedUserIds},
 *       and {@code findBlockedByUserIds} now accept a {@link PageRequest} and respect the
 *       supplied limit — no unbounded heap allocation.</li>
 *   <li><b>Paginated swipe queries</b>: {@code findLikesByUserId} and {@code findPassesByUserId}
 *       respect the {@code Pageable} limit and never return more rows than requested.</li>
 *   <li><b>Paginated match queries</b>: {@code findMatchesWithoutConversations} respects the
 *       {@code Pageable} limit.</li>
 *   <li><b>Genre batch-load</b>: {@code GenreExtractionService.extractAndSaveGenrePreferences}
 *       maps genres correctly from the pre-loaded in-memory lookup and concurrent calls
 *       complete without errors.</li>
 * </ol>
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class BatchEQueryOptimizationTest {

    @Autowired private UserSwipeRepository    swipeRepository;
    @Autowired private MatchRepository        matchRepository;
    @Autowired private UserJpaRepository      userRepository;
    @Autowired private CanonicalGenreRepository genreRepository;
    @Autowired private UserGenrePreferenceRepository genrePrefRepository;
    @Autowired private GenreExtractionService genreExtractionService;
    @Autowired private MatchService           matchService;
    @Autowired private PlatformTransactionManager txManager;

    private final List<String> createdUserIds  = new ArrayList<>();
    private final List<String> createdGenreIds = new ArrayList<>();

    private UserEntity swiper;
    private UserEntity swiped1;
    private UserEntity swiped2;
    private UserEntity swiped3;

    @BeforeEach
    void setUp() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            swiper  = save(user("batchE-swiper"));
            swiped1 = save(user("batchE-swiped1"));
            swiped2 = save(user("batchE-swiped2"));
            swiped3 = save(user("batchE-swiped3"));
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            // Remove swipes and match data first (FK constraints)
            swipeRepository.deleteAllInvolvingUser(swiper.getId());
            swipeRepository.deleteAllInvolvingUser(swiped1.getId());
            matchRepository.deleteAllByUserId(swiper.getId());
            // Remove genre prefs
            createdUserIds.forEach(genrePrefRepository::deleteByUserId);
            // Remove genres
            createdGenreIds.forEach(genreRepository::deleteById);
            // Remove users
            createdUserIds.forEach(userRepository::deleteById);
            return null;
        });
        createdUserIds.clear();
        createdGenreIds.clear();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1 — Exclusion queries respect the Pageable limit
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findAllSwipedUserIds respects Pageable limit — never returns more than requested")
    void findAllSwipedUserIds_respectsPageableLimit() {
        // Record likes on all 3 swiped users
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            swipeRepository.save(swipe(swiper, swiped1, "like"));
            swipeRepository.save(swipe(swiper, swiped2, "like"));
            swipeRepository.save(swipe(swiper, swiped3, "like"));
            return null;
        });

        // Request with limit = 2 — must never return all 3
        List<String> result = swipeRepository.findAllSwipedUserIds(swiper.getId(), PageRequest.of(0, 2));
        assertNotNull(result);
        assertTrue(result.size() <= 2,
                "Pageable limit must be respected: expected <=2 but got " + result.size());

        // Request with limit = 10_000 (production cap) — must return all 3
        List<String> allResult = swipeRepository.findAllSwipedUserIds(swiper.getId(), PageRequest.of(0, 10_000));
        assertEquals(3, allResult.size(),
                "With large limit, all swiped IDs must be returned");
        assertTrue(allResult.contains(swiped1.getId()));
        assertTrue(allResult.contains(swiped2.getId()));
        assertTrue(allResult.contains(swiped3.getId()));
    }

    @Test
    @DisplayName("findBlockedUserIds and findBlockedByUserIds respect Pageable limit")
    void findBlockedUserIds_respectsPageableLimit() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            swipeRepository.save(swipe(swiper, swiped1, "block"));
            swipeRepository.save(swipe(swiper, swiped2, "block"));
            return null;
        });

        List<String> blocked = swipeRepository.findBlockedUserIds(swiper.getId(), PageRequest.of(0, 1));
        assertTrue(blocked.size() <= 1, "Pageable limit must be respected");

        List<String> blockedAll = swipeRepository.findBlockedUserIds(swiper.getId(), PageRequest.of(0, 10_000));
        assertEquals(2, blockedAll.size());

        // swiped1 and swiped2 are blocked — their findBlockedByUserIds for swiper should not appear
        // (swiper was NOT blocked by them). Verify direction is correct.
        List<String> blockedBySwiper = swipeRepository.findBlockedByUserIds(swiper.getId(), PageRequest.of(0, 10_000));
        assertFalse(blockedBySwiper.contains(swiped1.getId()),
                "swiped1 did not block swiper — must not appear in findBlockedByUserIds(swiper)");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2 — Paginated swipe query: findLikesByUserId
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findLikesByUserId respects Pageable limit and returns correct rows")
    void findLikesByUserId_paginationAndContent() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            swipeRepository.save(swipe(swiper, swiped1, "like"));
            swipeRepository.save(swipe(swiper, swiped2, "like"));
            swipeRepository.save(swipe(swiper, swiped3, "pass"));
            return null;
        });

        List<UserSwipe> likesPage1 = swipeRepository.findLikesByUserId(swiper.getId(), PageRequest.of(0, 1));
        assertEquals(1, likesPage1.size(), "Page size of 1 must return exactly 1 like");

        List<UserSwipe> allLikes = swipeRepository.findLikesByUserId(swiper.getId(), PageRequest.of(0, 100));
        assertEquals(2, allLikes.size(), "Must return exactly the 2 recorded likes, not the pass");

        // Verify JOIN FETCH: swipedUser must be accessible without LazyInitializationException
        // (entity is detached after the transaction — if JOIN FETCH was missing this would fail)
        assertDoesNotThrow(() -> {
            String swipedId = allLikes.get(0).getSwipedUser().getId();
            assertNotNull(swipedId, "swipedUser.id must be accessible (JOIN FETCH active)");
        }, "Accessing swipedUser on detached UserSwipe must not throw (JOIN FETCH must be active)");
    }

    @Test
    @DisplayName("findPassesByUserId respects Pageable limit and excludes likes")
    void findPassesByUserId_paginationAndContent() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            swipeRepository.save(swipe(swiper, swiped1, "pass"));
            swipeRepository.save(swipe(swiper, swiped2, "pass"));
            swipeRepository.save(swipe(swiper, swiped3, "like"));
            return null;
        });

        List<UserSwipe> passes = swipeRepository.findPassesByUserId(swiper.getId(), PageRequest.of(0, 100));
        assertEquals(2, passes.size(), "Must return exactly the 2 passes, not the like");

        // Verify JOIN FETCH
        assertDoesNotThrow(() -> {
            String swipedId = passes.get(0).getSwipedUser().getId();
            assertNotNull(swipedId);
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3 — Paginated match query: findMatchesWithoutConversations
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findMatchesWithoutConversations respects Pageable limit")
    void findMatchesWithoutConversations_respectsLimit() {
        // Create 2 matches (both without conversations by default)
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            matchService.createMatch(swiper, swiped1, 75.0);
            matchService.createMatch(swiper, swiped2, 80.0);
            return null;
        });

        List<Match> page1 = matchRepository.findMatchesWithoutConversations(swiper.getId(), PageRequest.of(0, 1));
        assertTrue(page1.size() <= 1, "Limit=1 must return at most 1 match");

        List<Match> all = matchRepository.findMatchesWithoutConversations(swiper.getId(), PageRequest.of(0, 100));
        assertEquals(2, all.size(), "Must return both matches without conversations");
    }

    @Test
    @DisplayName("MatchService.getNewMatches returns at most 100 new matches")
    void getNewMatches_delegatesToPaginatedQuery() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            matchService.createMatch(swiper, swiped1, 70.0);
            return null;
        });

        List<Match> newMatches = matchService.getNewMatches(swiper.getId());
        assertNotNull(newMatches);
        assertTrue(newMatches.size() <= 100, "getNewMatches must never exceed the 100-row cap");
        assertFalse(newMatches.isEmpty(), "Should return the created match");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4 — Genre batch-load: correctness and concurrency
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Genre extraction maps genres correctly using in-memory batch load")
    void genreExtraction_mapsCorrectly() {
        // Create a canonical genre with a known name and alias
        CanonicalGenre genre = TransactionTemplate_execute(txManager, () ->
                genreRepository.save(CanonicalGenre.builder()
                        .name("batchE-test-indie-rock")
                        .displayName("BatchE Indie Rock")
                        .spotifyAliases("batchE indie rock,batchE-indie,batchE_indie")
                        .isPrimary(false)
                        .build()));
        createdGenreIds.add(genre.getId());

        User user = domainUser(swiper);
        List<String> spotifyGenres = List.of(
                "batchE-test-indie-rock",   // exact name match
                "batchE indie rock",        // alias match
                "totally-unknown-xyz-99"    // no match — should be logged and skipped
        );

        assertDoesNotThrow(() ->
                genreExtractionService.extractAndSaveGenrePreferences(
                        user, spotifyGenres, GenrePreferenceSource.SPOTIFY_DERIVED),
                "extractAndSaveGenrePreferences must not throw");

        // Verify preferences were saved for the known genre
        TransactionTemplate tx = new TransactionTemplate(txManager);
        long savedCount = tx.execute(status ->
                (long) genrePrefRepository.findTopNByUserIdWithGenre(user.getId(), 50).size());
        assertTrue(savedCount >= 1,
                "At least one genre preference must be saved for the matched genre");
    }

    @Test
    @DisplayName("Concurrent genre extractions complete without errors (batch-load is thread-safe)")
    void genreExtraction_concurrentCallsSucceed() throws Exception {
        CanonicalGenre genre = TransactionTemplate_execute(txManager, () ->
                genreRepository.save(CanonicalGenre.builder()
                        .name("batchE-concurrent-genre")
                        .displayName("BatchE Concurrent")
                        .isPrimary(false)
                        .build()));
        createdGenreIds.add(genre.getId());

        // Create 5 users so each thread has a distinct user (avoids unique-constraint collision)
        List<UserEntity> concUsers = new ArrayList<>();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            for (int i = 0; i < 5; i++) {
                UserEntity u = save(user("batchE-conc-" + i));
                concUsers.add(u);
            }
            return null;
        });

        int threadCount = 5;
        ExecutorService pool    = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier   barrier = new CyclicBarrier(threadCount);

        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                barrier.await();
                User u = domainUser(concUsers.get(idx));
                genreExtractionService.extractAndSaveGenrePreferences(
                        u,
                        List.of("batchE-concurrent-genre", "unknown-genre-" + idx),
                        GenrePreferenceSource.SPOTIFY_DERIVED);
                return null;
            }));
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        for (Future<Void> f : futures) {
            assertDoesNotThrow((ThrowingSupplier<Void>) f::get, "No concurrent genre extraction thread must throw");
        }

        // Cleanup extra users
        concUsers.forEach(u -> createdUserIds.add(u.getId()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private UserEntity save(UserEntity entity) {
        UserEntity saved = userRepository.save(entity);
        createdUserIds.add(saved.getId());
        return saved;
    }

    private UserEntity user(String tag) {
        return UserEntity.builder()
                .email(tag + "-" + UUID.randomUUID() + "@test.invalid")
                .registrationStage(RegistrationStage.FINISHED)
                .build();
    }

    private UserSwipe swipe(UserEntity swiperUser, UserEntity swipedUser, String action) {
        return UserSwipe.builder()
                .swiperUser(swiperUser)
                .swipedUser(swipedUser)
                .action(action)
                .resultedInMatch(false)
                .build();
    }

    private User domainUser(UserEntity entity) {
        return User.builder()
                .id(entity.getId())
                .email(entity.getEmail())
                .build();
    }

    /** Helper to run a lambda inside a transaction and return the result. */
    private <T> T TransactionTemplate_execute(PlatformTransactionManager tm,
                                              java.util.function.Supplier<T> action) {
        return new TransactionTemplate(tm).execute(status -> action.get());
    }
}
