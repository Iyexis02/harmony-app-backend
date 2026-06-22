package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.enums.matching.GenrePreferenceSource;
import com.example.dating.models.matching.dao.CanonicalGenre;
import com.example.dating.models.matching.dao.GenrePreferenceSourceConverter;
import com.example.dating.models.matching.dao.UserGenrePreference;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.photos.dao.UserPhoto;
import com.example.dating.repositories.CanonicalGenreRepository;
import com.example.dating.repositories.UserGenrePreferenceRepository;
import com.example.dating.repositories.UserJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import java.lang.reflect.Method;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.example.dating.enums.matching.GenrePreferenceSource.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Batch H — String UUID to Native UUID Migration + Type Fixes
 *
 * Verifies:
 *   1. GenrePreferenceSourceConverter maps all enum constants to/from their DB strings.
 *   2. GenrePreferenceSource.fromValue() degrades gracefully on null / unknown strings.
 *   3. UserGenrePreference @PrePersist sets both timestamps when created via no-args constructor
 *      (the failure mode that @Builder.Default silently missed).
 *   4. UserPhoto.updatedAt is non-null after @PrePersist fires (was null before the fix).
 *   5. deleteByUserIdAndSource with a typed enum deletes only the matching source rows.
 *   6. Concurrent deleteByUserIdAndSource calls are idempotent (no exception on second delete).
 *   7. GenrePreferenceSource enum survives a full JPA round-trip for every defined constant.
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class BatchHTypeFixTest {

    @Autowired private UserJpaRepository userRepository;
    @Autowired private UserGenrePreferenceRepository genrePreferenceRepository;
    @Autowired private CanonicalGenreRepository canonicalGenreRepository;
    @Autowired private TransactionTemplate txTemplate;

    private UserEntity seedUser;
    private CanonicalGenre seedGenreA;
    private CanonicalGenre seedGenreB;

    @BeforeEach
    void resolveSeedData() {
        List<UserEntity> users = userRepository.findAll(PageRequest.of(0, 1)).getContent();
        assertFalse(users.isEmpty(), "Seed users required — start the app once so UserSeedDataLoader runs");
        seedUser = users.get(0);

        List<CanonicalGenre> genres = canonicalGenreRepository.findAll(PageRequest.of(0, 2)).getContent();
        assertTrue(genres.size() >= 2, "Need at least 2 canonical genres — GenreSeedDataLoader must have run");
        seedGenreA = genres.get(0);
        seedGenreB = genres.get(1);
    }

    // ─── Test 1: Converter enum → DB string ────────────────────────────────────

    @Test
    @DisplayName("Converter maps every enum constant to its lowercase underscore DB string")
    void converterEnumToDbString() {
        var converter = new GenrePreferenceSourceConverter();

        assertAll(
            () -> assertEquals("spotify_derived",  converter.convertToDatabaseColumn(SPOTIFY_DERIVED)),
            () -> assertEquals("manual_selection", converter.convertToDatabaseColumn(MANUAL_SELECTION)),
            () -> assertEquals("inferred",         converter.convertToDatabaseColumn(INFERRED)),
            () -> assertEquals("hybrid",           converter.convertToDatabaseColumn(HYBRID)),
            () -> assertEquals("seed_data",        converter.convertToDatabaseColumn(SEED_DATA)),
            () -> assertNull(converter.convertToDatabaseColumn(null))
        );
    }

    // ─── Test 2: Converter DB string → enum ────────────────────────────────────

    @Test
    @DisplayName("Converter reads existing lowercase DB strings back to the correct enum constant")
    void converterDbStringToEnum() {
        var converter = new GenrePreferenceSourceConverter();

        assertAll(
            // Lowercase — what is actually stored in the DB
            () -> assertEquals(SPOTIFY_DERIVED,  converter.convertToEntityAttribute("spotify_derived")),
            () -> assertEquals(MANUAL_SELECTION, converter.convertToEntityAttribute("manual_selection")),
            () -> assertEquals(SEED_DATA,        converter.convertToEntityAttribute("seed_data")),
            () -> assertEquals(INFERRED,         converter.convertToEntityAttribute("inferred")),
            () -> assertEquals(HYBRID,           converter.convertToEntityAttribute("hybrid")),
            // Uppercase — defensive case (converter is case-insensitive via toUpperCase())
            () -> assertEquals(SPOTIFY_DERIVED,  converter.convertToEntityAttribute("SPOTIFY_DERIVED")),
            () -> assertNull(converter.convertToEntityAttribute(null))
        );
    }

    // ─── Test 3: fromValue() graceful fallback ──────────────────────────────────

    @Test
    @DisplayName("fromValue() parses known strings and falls back to MANUAL_SELECTION for unknown input")
    void fromValueFallback() {
        assertAll(
            () -> assertEquals(SPOTIFY_DERIVED,  GenrePreferenceSource.fromValue("spotify_derived")),
            () -> assertEquals(MANUAL_SELECTION, GenrePreferenceSource.fromValue("manual_selection")),
            () -> assertEquals(SEED_DATA,        GenrePreferenceSource.fromValue("seed_data")),
            // Case-insensitive
            () -> assertEquals(SPOTIFY_DERIVED,  GenrePreferenceSource.fromValue("SPOTIFY_DERIVED")),
            // Graceful degradation — no exception thrown
            () -> assertEquals(MANUAL_SELECTION, GenrePreferenceSource.fromValue(null)),
            () -> assertEquals(MANUAL_SELECTION, GenrePreferenceSource.fromValue("unknown_legacy")),
            () -> assertEquals(MANUAL_SELECTION, GenrePreferenceSource.fromValue(""))
        );
    }

    // ─── Test 4: @PrePersist sets timestamps on the no-args constructor path ───

    @Test
    @DisplayName("@PrePersist sets createdAt and updatedAt when UserGenrePreference is built via new() (not builder)")
    void prePersistSetsTimestampsOnNoArgsConstructorPath() {
        // The @Builder.Default approach silently failed for this path: when GenreExtractionService
        // called findOrCreatePreference() → new UserGenrePreference() → setUser/setGenre/setSource,
        // then saved without explicitly calling setCreatedAt, the @Builder.Default never fired
        // because @Builder.Default only applies to the Lombok builder code path.
        // After the fix, @PrePersist guarantees timestamps regardless of how the entity was created.

        String savedId = txTemplate.execute(status -> {
            UserGenrePreference pref = new UserGenrePreference();
            pref.setUser(seedUser);
            pref.setGenre(seedGenreA);
            pref.setWeight(0.5);
            pref.setSource(INFERRED);
            // Intentionally do NOT set createdAt or updatedAt — @PrePersist must fill them.

            assertNull(pref.getCreatedAt(), "createdAt should be null before JPA fires @PrePersist");
            assertNull(pref.getUpdatedAt(), "updatedAt should be null before JPA fires @PrePersist");

            UserGenrePreference saved = genrePreferenceRepository.saveAndFlush(pref);

            assertNotNull(saved.getCreatedAt(), "createdAt must be set by @PrePersist");
            assertNotNull(saved.getUpdatedAt(), "updatedAt must be set by @PrePersist");
            assertTrue(saved.getCreatedAt().isBefore(LocalDateTime.now().plusSeconds(2)));

            status.setRollbackOnly(); // clean up — no permanent side effect
            return saved.getId();
        });

        assertNotNull(savedId, "Entity ID should have been assigned before rollback");
    }

    // ─── Test 5: UserPhoto.updatedAt non-null after @PrePersist ────────────────

    @Test
    @DisplayName("UserPhoto.updatedAt is non-null after @PrePersist fires (was null before the Batch H fix)")
    void photoUpdatedAtNonNullAfterPrePersist() throws Exception {
        // Before the fix, UserPhoto.onCreate() only set createdAt and left updatedAt null.
        // We test the @PrePersist method directly (as JPA would invoke it) to verify the fix.
        UserPhoto photo = UserPhoto.builder()
                .imageUrl("https://example.com/batch-h-test.jpg")
                .displayOrder(99)
                .isPrimary(false)
                .build();

        assertNull(photo.getCreatedAt(), "createdAt null before @PrePersist");
        assertNull(photo.getUpdatedAt(), "updatedAt null before @PrePersist");

        // Invoke the lifecycle method exactly as JPA does before INSERT
        Method onCreateMethod = UserPhoto.class.getDeclaredMethod("onCreate");
        onCreateMethod.setAccessible(true);
        onCreateMethod.invoke(photo);

        assertNotNull(photo.getCreatedAt(), "createdAt must be set by @PrePersist");
        assertNotNull(photo.getUpdatedAt(), "updatedAt must be set by @PrePersist — this was the bug");
        assertEquals(photo.getCreatedAt(), photo.getUpdatedAt(),
                "Both timestamps should be set to the same instant on first persist");
    }

    // ─── Test 6: deleteByUserIdAndSource only removes the matching source ──────

    @Test
    @DisplayName("deleteByUserIdAndSource(SPOTIFY_DERIVED) deletes only SPOTIFY_DERIVED rows, leaving MANUAL_SELECTION intact")
    void deleteBySourceOnlyRemovesMatchingSource() {
        // Create two prefs with different sources — use separate transactions so each is committed
        String spotifyId = txTemplate.execute(status ->
                genrePreferenceRepository.saveAndFlush(buildPref(seedGenreA, SPOTIFY_DERIVED, 0.9)).getId());

        String manualId = txTemplate.execute(status ->
                genrePreferenceRepository.saveAndFlush(buildPref(seedGenreB, MANUAL_SELECTION, 0.7)).getId());

        assertNotNull(spotifyId);
        assertNotNull(manualId);

        try {
            // Delete only SPOTIFY_DERIVED — this is the call that silently did nothing with a typo
            txTemplate.execute(status -> {
                genrePreferenceRepository.deleteByUserIdAndSource(seedUser.getId(), SPOTIFY_DERIVED);
                return null;
            });

            boolean spotifyStillExists = txTemplate.execute(status ->
                    genrePreferenceRepository.findById(spotifyId).isPresent());
            boolean manualStillExists  = txTemplate.execute(status ->
                    genrePreferenceRepository.findById(manualId).isPresent());

            assertFalse(spotifyStillExists, "SPOTIFY_DERIVED pref must be deleted");
            assertTrue(manualStillExists,   "MANUAL_SELECTION pref must survive (different source)");
        } finally {
            txTemplate.execute(status -> {
                genrePreferenceRepository.findById(manualId).ifPresent(genrePreferenceRepository::delete);
                return null;
            });
        }
    }

    // ─── Test 7: Concurrent deleteByUserIdAndSource is idempotent ──────────────

    @Test
    @DisplayName("Concurrent deleteByUserIdAndSource calls complete without exceptions and are idempotent")
    void concurrentDeletesBySourceAreIdempotent() throws InterruptedException {
        // Seed one SPOTIFY_DERIVED pref
        txTemplate.execute(status -> {
            genrePreferenceRepository.saveAndFlush(buildPref(seedGenreA, SPOTIFY_DERIVED, 0.8));
            return null;
        });

        int threadCount = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch allReady = new CountDownLatch(threadCount);
        CountDownLatch go       = new CountDownLatch(1);
        AtomicInteger errors    = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                allReady.countDown();
                try {
                    go.await(); // all threads start simultaneously
                    txTemplate.execute(status -> {
                        genrePreferenceRepository.deleteByUserIdAndSource(
                                seedUser.getId(), SPOTIFY_DERIVED);
                        return null;
                    });
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }

        allReady.await();
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "Threads should finish within 15 s");

        assertEquals(0, errors.get(),
                "deleteByUserIdAndSource must be idempotent — no exception on concurrent deletes");

        // Verify the pref is gone
        List<UserGenrePreference> remaining = txTemplate.execute(status ->
                genrePreferenceRepository.findByUserAndSource(seedUser, SPOTIFY_DERIVED));
        assertNotNull(remaining);
        assertTrue(remaining.isEmpty(), "No SPOTIFY_DERIVED prefs should remain after concurrent deletes");
    }

    // ─── Test 8: Enum survives JPA round-trip ──────────────────────────────────

    @Test
    @DisplayName("GenrePreferenceSource enum round-trips through JPA for every defined constant")
    void sourceEnumRoundTripsThroughJpa() {
        List<GenrePreferenceSource> sources = List.of(
                SPOTIFY_DERIVED, MANUAL_SELECTION, INFERRED, HYBRID, SEED_DATA
        );

        for (GenrePreferenceSource expected : sources) {
            String savedId = txTemplate.execute(status -> {
                UserGenrePreference pref = UserGenrePreference.builder()
                        .user(seedUser)
                        .genre(seedGenreA)
                        .weight(0.5)
                        .source(expected)
                        .confidence(1.0)
                        .build();
                return genrePreferenceRepository.saveAndFlush(pref).getId();
            });

            // Fresh transaction — bypasses first-level cache, forces a real SELECT
            GenrePreferenceSource roundTripped = txTemplate.execute(status ->
                    genrePreferenceRepository.findById(savedId)
                            .orElseThrow(() -> new AssertionError("Pref not found: " + savedId))
                            .getSource());

            assertEquals(expected, roundTripped,
                    "Source " + expected + " must survive a full JPA write + read cycle");

            // Clean up before the next iteration (unique constraint on user_id + genre_id)
            txTemplate.execute(status -> {
                genrePreferenceRepository.findById(savedId).ifPresent(genrePreferenceRepository::delete);
                return null;
            });
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private UserGenrePreference buildPref(CanonicalGenre genre,
                                          GenrePreferenceSource source,
                                          double weight) {
        return UserGenrePreference.builder()
                .user(seedUser)
                .genre(genre)
                .weight(weight)
                .source(source)
                .confidence(1.0)
                .build();
    }
}
