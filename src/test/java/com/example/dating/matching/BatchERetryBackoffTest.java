package com.example.dating.matching;

import com.example.dating.config.DistributedLockService;
import com.example.dating.mappers.UserMapper;
import com.example.dating.models.auth.SpotifyTokenResponse;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.UserBehavioralProfileRepository;
import com.example.dating.repositories.UserGenrePreferenceRepository;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.EncryptionService;
import com.example.dating.services.JwtService;
import com.example.dating.services.impl.SpotifyTokenServiceImpl;
import com.example.dating.services.impl.UserServiceImpl;
import com.example.dating.services.matching.BehavioralProfileService;
import com.example.dating.services.matching.BehavioralScoreCalculator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Batch E — Behavioral Profile Retry Backoff + Token Refresh Locking.
 *
 * <p>Verifies:
 * <ol>
 *   <li>Behavioral retry: proxy invoked exactly 3 times when all attempts fail with
 *       {@link OptimisticLockingFailureException}.</li>
 *   <li>Behavioral retry: exponential backoff adds ≥ 100 ms total delay when all 3
 *       attempts fail (40 ms after attempt 1 + 80 ms after attempt 2).</li>
 *   <li>Behavioral retry: {@link InterruptedException} during sleep stops retrying
 *       immediately and restores the thread interrupt flag.</li>
 *   <li>Token refresh lock: if the user re-read inside the lock is already fresh,
 *       the Spotify API is not called a second time.</li>
 *   <li>Token refresh lock: 3 concurrent calls for the same user result in exactly
 *       1 Spotify API call; the other 2 threads return the cached fresh token.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class BatchERetryBackoffTest {

    // ── Behavioral-profile mocks ──────────────────────────────────────────────

    @Mock private UserBehavioralProfileRepository behavioralProfileRepository;
    @Mock private UserGenrePreferenceRepository   genrePreferenceRepository;
    @Mock private UserJpaRepository               userJpaRepository;
    @Mock private ApplicationContext              applicationContext;
    @Mock private BehavioralScoreCalculator       behavioralScoreCalculator;

    private BehavioralProfileService behavioralService;
    private BehavioralProfileService proxyMock;

    // ── SpotifyTokenService mocks ─────────────────────────────────────────────

    @Mock private UserMapper        userMapper;
    @Mock private EncryptionService encryptionService;
    @Mock private JwtService        jwtService;

    private SpotifyTokenServiceImpl spotifyTokenService;

    // ── UserServiceImpl mocks (CRUD only — 4 deps post-Batch-B) ──────────────

    @Mock private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        proxyMock = mock(BehavioralProfileService.class);
        lenient().when(applicationContext.getBean(BehavioralProfileService.class))
                 .thenReturn(proxyMock);

        behavioralService = new BehavioralProfileService(
                behavioralProfileRepository, genrePreferenceRepository,
                userJpaRepository, new ObjectMapper(), applicationContext,
                behavioralScoreCalculator);

        // DistributedLockService: mock Redis with no stubs — setIfAbsent() returns null by
        // default, triggering the in-memory fallback without requiring any lenient stubs.
        DistributedLockService distributedLockService =
                new DistributedLockService(mock(StringRedisTemplate.class));
        spotifyTokenService = new SpotifyTokenServiceImpl(
                userJpaRepository, userMapper, encryptionService, jwtService, distributedLockService);

        // UserServiceImpl is constructed to verify its constructor compiles with 4 deps.
        // It is not exercised by any test in this file.
        new UserServiceImpl(userJpaRepository, encryptionService, userMapper, passwordEncoder);
    }

    // ── Test 1: proxy called exactly 3 times ─────────────────────────────────

    @Test
    @DisplayName("Behavioral retry: proxy invoked 3 times when all attempts fail with OptimisticLockingFailureException")
    void behavioralRetry_allAttemptsFail_proxyCalledThreeTimes() {
        doThrow(new OptimisticLockingFailureException("conflict"))
                .when(proxyMock).doUpdateAfterSwipe(any(), any(), any(), any());

        behavioralService.updateAfterSwipe("swiper-1", "swiped-1", "like", 80.0);

        verify(proxyMock, times(3)).doUpdateAfterSwipe("swiper-1", "swiped-1", "like", 80.0);
    }

    // ── Test 2: exponential backoff timing ────────────────────────────────────

    @Test
    @DisplayName("Behavioral retry: total elapsed ≥ 100 ms when all 3 attempts fail (40 ms + 80 ms backoff)")
    void behavioralRetry_exponentialBackoff_takesAtLeast100ms() {
        doThrow(new OptimisticLockingFailureException("conflict"))
                .when(proxyMock).doUpdateAfterSwipe(any(), any(), any(), any());

        long start   = System.currentTimeMillis();
        behavioralService.updateAfterSwipe("swiper-2", "swiped-2", "like", 70.0);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed)
                .as("Exponential backoff must add at least 100 ms (40 ms + 80 ms scheduled delays)")
                .isGreaterThanOrEqualTo(100L);
    }

    // ── Test 3: interrupt during backoff ─────────────────────────────────────

    @Test
    @DisplayName("Behavioral retry: InterruptedException during sleep stops retrying and restores interrupt flag")
    void behavioralRetry_interruptedDuringBackoff_stopsAndRestoresInterruptFlag() {
        doThrow(new OptimisticLockingFailureException("conflict"))
                .when(proxyMock).doUpdateAfterSwipe(any(), any(), any(), any());

        // Pre-interrupt the thread so the very first Thread.sleep() throws immediately.
        Thread.currentThread().interrupt();
        behavioralService.updateAfterSwipe("swiper-3", "swiped-3", "like", 60.0);

        // Thread.interrupted() reads AND clears the flag — assert it was re-set by the catch block.
        assertThat(Thread.interrupted())
                .as("Interrupt flag must be restored after InterruptedException in backoff sleep")
                .isTrue();

        // Only attempt 1 ran before the interrupt aborted the loop.
        verify(proxyMock, times(1)).doUpdateAfterSwipe(any(), any(), any(), any());
    }

    // ── Test 4: already-fresh guard skips Spotify ─────────────────────────────

    @Test
    @DisplayName("Token refresh lock: re-reads user inside lock; skips Spotify if already fresh")
    void tokenRefreshLock_alreadyFresh_skipsSpotifyCall() {
        String userId       = "user-already-fresh";
        String encAccess    = "enc_access_token";
        String plainAccess  = "plain_access_token";

        // The user object passed in looks expired, but the DB read inside the lock is fresh.
        User expiredArg = buildExpiredUser(userId);
        User freshInDb  = buildFreshUser(userId, encAccess);

        UserEntity freshEntity = mock(UserEntity.class);
        when(userJpaRepository.findById(userId)).thenReturn(Optional.of(freshEntity));
        when(userMapper.toDomain(freshEntity)).thenReturn(freshInDb);
        when(encryptionService.decrypt(encAccess)).thenReturn(plainAccess);

        String result = spotifyTokenService.refreshAndUpdateUserToken(expiredArg);

        assertThat(result).isEqualTo(plainAccess);
        verifyNoInteractions(jwtService);
    }

    // ── Test 5: concurrent calls — single Spotify call ────────────────────────

    @Test
    @DisplayName("Token refresh lock: 3 concurrent calls for the same user result in exactly 1 Spotify call")
    void tokenRefreshLock_concurrent_spotifyCalledOnce() throws Exception {
        String userId          = "user-concurrent-refresh";
        String encOldRefresh   = "enc_old_refresh";
        String plainOldRefresh = "plain_old_refresh";
        String newAccessToken  = "new_access_token";
        String encNewAccess    = "enc_new_access";

        User expiredUser = buildExpiredUser(userId);
        expiredUser.setSpotifyRefreshToken(encOldRefresh);
        User freshUser = buildFreshUser(userId, encNewAccess);

        UserEntity expiredEntity = mock(UserEntity.class);
        UserEntity freshEntity   = mock(UserEntity.class);

        // First DB read (Thread A inside lock) → expired; subsequent reads → fresh.
        when(userJpaRepository.findById(userId))
                .thenReturn(Optional.of(expiredEntity), Optional.of(freshEntity), Optional.of(freshEntity));

        when(userMapper.toDomain(expiredEntity)).thenReturn(expiredUser);
        when(userMapper.toDomain(freshEntity)).thenReturn(freshUser);

        when(encryptionService.decrypt(encOldRefresh)).thenReturn(plainOldRefresh);
        when(encryptionService.decrypt(encNewAccess)).thenReturn(newAccessToken);
        when(encryptionService.encrypt(newAccessToken)).thenReturn(encNewAccess);

        SpotifyTokenResponse tokenResponse = mock(SpotifyTokenResponse.class);
        when(tokenResponse.getAccess_token()).thenReturn(newAccessToken);
        when(tokenResponse.getExpires_in()).thenReturn("3600");
        when(tokenResponse.getRefresh_token()).thenReturn(null);

        // Slow Spotify call so Threads B and C reach the lock while Thread A holds it.
        AtomicInteger spotifyCallCount = new AtomicInteger(0);
        when(jwtService.refreshToken(plainOldRefresh)).thenAnswer(inv -> {
            spotifyCallCount.incrementAndGet();
            Thread.sleep(50);
            return tokenResponse;
        });

        int threadCount = 3;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        ExecutorService pool  = Executors.newFixedThreadPool(threadCount);

        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            // Each thread gets its own expired user object — only getId() matters internally.
            User threadUser = buildExpiredUser(userId);
            threadUser.setSpotifyRefreshToken(encOldRefresh);
            futures.add(pool.submit(() -> {
                barrier.await();
                return spotifyTokenService.refreshAndUpdateUserToken(threadUser);
            }));
        }

        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        for (Future<String> f : futures) {
            assertThat(f.get())
                    .as("Every thread must receive a non-blank access token")
                    .isNotNull()
                    .isNotBlank();
        }

        assertThat(spotifyCallCount.get())
                .as("Exactly 1 Spotify token-refresh call expected regardless of concurrency")
                .isEqualTo(1);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private User buildExpiredUser(String userId) {
        return User.builder()
                .id(userId)
                .spotifyId("spotify_" + userId)
                .spotifyAccessToken("enc_old_access")
                .spotifyRefreshToken("enc_old_refresh")
                .spotifyTokenExpires(Instant.now().minusSeconds(100))
                .build();
    }

    private User buildFreshUser(String userId, String encryptedAccessToken) {
        return User.builder()
                .id(userId)
                .spotifyId("spotify_" + userId)
                .spotifyAccessToken(encryptedAccessToken)
                .spotifyRefreshToken("enc_refresh")
                .spotifyTokenExpires(Instant.now().plusSeconds(3600))
                .build();
    }
}
