package com.example.dating.matching;

import com.example.dating.config.DistributedLockService;
import com.example.dating.enums.matching.GenrePreferenceSource;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.CanonicalGenreRepository;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.repositories.UserGenrePreferenceRepository;
import com.example.dating.services.SpotifyService;
import com.example.dating.services.matching.GenreExtractionService;
import com.example.dating.services.matching.GenreWeightCalculator;
import com.example.dating.services.matching.SpotifyGenreSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Batch D — Genre Sync Transaction Atomicity.
 *
 * <p>Verifies:
 * <ol>
 *   <li>Structural: {@code GenreExtractionService.replaceSpotifyPreferences} is annotated
 *       with {@link Transactional}, guaranteeing delete + save share one transaction.</li>
 *   <li>Operation order: delete is called before saveAll inside
 *       {@code replaceSpotifyPreferences}, so no window exists where preferences are
 *       absent while new ones are being inserted.</li>
 *   <li>Concurrent-sync guard: a second {@code syncUserGenrePreferences} call for the
 *       same user while one is already in progress returns 0 immediately and does not
 *       contact Spotify.</li>
 *   <li>Guard cleanup: after a successful sync the lock is released from
 *       {@code DistributedLockService.localFallback}, allowing a subsequent sync to
 *       proceed normally.</li>
 *   <li>Exception propagation: if {@code saveAll} throws inside
 *       {@code replaceSpotifyPreferences}, the exception escapes to the caller (and
 *       Spring's {@code @Transactional} will roll back the delete).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class BatchDGenreSyncAtomicityTest {

    @Mock private CanonicalGenreRepository canonicalGenreRepository;
    @Mock private UserGenrePreferenceRepository genrePreferenceRepository;
    @Mock private GenreWeightCalculator genreWeightCalculator;
    @Mock private SpotifyService spotifyService;
    @Mock private UserJpaRepository userJpaRepository;

    private GenreExtractionService genreExtractionService;
    private SpotifyGenreSyncService syncService;
    private DistributedLockService distributedLockService;

    @BeforeEach
    void setUp() {
        // Build real service instances backed by mocked repositories.
        // A direct (inline) Executor is used so CompletableFuture.supplyAsync() completes
        // synchronously on the calling thread — no real thread pool needed in unit tests.
        // DistributedLockService: mock Redis with no stubs — setIfAbsent() returns null by
        // default, triggering the in-memory fallback path without requiring any lenient stubs.
        StringRedisTemplate mockRedis = mock(StringRedisTemplate.class);
        distributedLockService = new DistributedLockService(mockRedis);

        genreExtractionService = new GenreExtractionService(
                canonicalGenreRepository, genrePreferenceRepository, genreWeightCalculator,
                userJpaRepository);
        syncService = new SpotifyGenreSyncService(
                spotifyService, genreExtractionService, userJpaRepository, Runnable::run,
                distributedLockService);
    }

    // ── Test 1: structural ────────────────────────────────────────────────────

    @Test
    @DisplayName("replaceSpotifyPreferences is annotated @Transactional")
    void replaceSpotifyPreferences_isAnnotatedTransactional() throws NoSuchMethodException {
        Method method = GenreExtractionService.class.getDeclaredMethod(
                "replaceSpotifyPreferences", User.class, List.class);

        assertThat(method.isAnnotationPresent(Transactional.class))
                .as("replaceSpotifyPreferences must be @Transactional so delete + save " +
                        "share one transaction and roll back together on failure")
                .isTrue();
    }

    // ── Test 2: operation order ────────────────────────────────────────────────

    @Test
    @DisplayName("replaceSpotifyPreferences: deleteByUserIdAndSource is called before saveAll")
    void replaceSpotifyPreferences_deleteCalledBeforeSave() {
        User user = User.builder().id("user-order-test").build();
        // canonicalGenreRepository.findAll() returns empty list by default (Mockito),
        // so extractAndSaveGenrePreferences builds an empty preferences list and still
        // calls saveAll([]) — enough to verify ordering.

        genreExtractionService.replaceSpotifyPreferences(user, List.of("rock", "pop"));

        InOrder order = inOrder(genrePreferenceRepository);
        order.verify(genrePreferenceRepository)
                .deleteByUserIdAndSource("user-order-test", GenrePreferenceSource.SPOTIFY_DERIVED);
        order.verify(genrePreferenceRepository).saveAll(any());
    }

    // ── Test 3: concurrent guard — duplicate skipped ────────────────────────

    @Test
    @DisplayName("syncUserGenrePreferences returns 0 and skips Spotify when sync is already in progress")
    void syncInProgress_guard_skipsWhenAlreadyInProgress() throws Exception {
        User user = User.builder().id("user-guard-test").build();
        String lockKey = "lock:spotify:sync:" + user.getId();

        // Simulate an in-progress sync by pre-populating the distributed lock fallback map.
        ConcurrentHashMap<String, Boolean> fallback = getLocalFallbackMap(distributedLockService);
        fallback.put(lockKey, Boolean.TRUE);

        int result = syncService.syncUserGenrePreferences(user, "some-token");

        assertThat(result)
                .as("Second concurrent sync must return 0 (skipped)")
                .isEqualTo(0);
        verify(spotifyService, never())
                .getGenresFromTopArtists(anyString(), anyInt(), anyString());
    }

    // ── Test 4: guard is cleaned up after a successful sync ─────────────────

    @Test
    @DisplayName("Lock is released from DistributedLockService after a successful syncUserGenrePreferences call")
    void syncInProgress_clearedAfterSuccessfulSync() throws Exception {
        User user = User.builder().id("user-cleanup-test").build();
        String lockKey = "lock:spotify:sync:" + user.getId();

        // Mock Spotify to return a non-empty genre list so the sync proceeds to save.
        when(spotifyService.getGenresFromTopArtists(anyString(), anyInt(), anyString()))
                .thenReturn(List.of("rock", "pop"));
        // canonicalGenreRepository.findAll() returns empty list (Mockito default) →
        // no preferences built → saveAll([]) called cleanly.

        syncService.syncUserGenrePreferences(user, "token");

        ConcurrentHashMap<String, Boolean> fallback = getLocalFallbackMap(distributedLockService);
        assertThat(fallback.containsKey(lockKey))
                .as("Lock must be released from DistributedLockService after sync completes, " +
                        "otherwise subsequent syncs would be permanently blocked")
                .isFalse();
    }

    // ── Test 5: exception propagates cleanly ────────────────────────────────

    @Test
    @DisplayName("replaceSpotifyPreferences propagates exception from saveAll to caller")
    void replaceSpotifyPreferences_exceptionDuringSave_propagatesToCaller() {
        User user = User.builder().id("user-ex-test").build();
        RuntimeException dbError = new RuntimeException("simulated DB write failure");
        doThrow(dbError).when(genrePreferenceRepository).saveAll(any());

        // The exception must escape replaceSpotifyPreferences so that the caller
        // (and Spring's @Transactional interceptor) can trigger a rollback of the
        // preceding deleteByUserIdAndSource call.
        assertThatThrownBy(
                () -> genreExtractionService.replaceSpotifyPreferences(user, List.of("rock")))
                .isSameAs(dbError);

        // Confirm delete was still invoked before the failure — the rollback assertion
        // itself is the @Transactional annotation verified in test 1.
        verify(genrePreferenceRepository)
                .deleteByUserIdAndSource("user-ex-test", GenrePreferenceSource.SPOTIFY_DERIVED);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, Boolean> getLocalFallbackMap(DistributedLockService svc)
            throws Exception {
        Field field = DistributedLockService.class.getDeclaredField("localFallback");
        field.setAccessible(true);
        return (ConcurrentHashMap<String, Boolean>) field.get(svc);
    }
}
