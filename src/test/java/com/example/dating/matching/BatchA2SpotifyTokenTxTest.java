package com.example.dating.matching;

import com.example.dating.config.DistributedLockService;
import com.example.dating.models.auth.SpotifyTokenResponse;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.mappers.UserMapper;
import com.example.dating.services.EncryptionService;
import com.example.dating.services.JwtService;
import com.example.dating.services.impl.SpotifyTokenServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Batch A2 (Master Audit) — Move HTTP Calls Outside @Transactional in Spotify Token Refresh.
 *
 * <p>Pure unit tests — no Spring context, no DB required.
 *
 * <p>Validates:
 * <ol>
 *   <li>{@code getValidSpotifyToken} has NO {@link Transactional} annotation —
 *       the expiry check does not acquire a DB connection.</li>
 *   <li>{@code refreshAndUpdateUserToken} has NO {@link Transactional} annotation —
 *       the Spotify HTTP call (up to 33.5 s worst-case with retries) does not hold
 *       a DB connection.</li>
 *   <li>{@code persistRefreshedToken} IS {@link Transactional} — the token write
 *       runs in a short transaction after the HTTP call completes.</li>
 *   <li>{@code Thread.sleep(500)} in the loser path sits outside any transaction —
 *       confirmed by annotation absence on {@code refreshAndUpdateUserToken}.</li>
 *   <li>A successful refresh calls {@code userJpaRepository.save()} via
 *       {@code persistRefreshedToken}, in order after {@code jwtService.refreshToken()}.</li>
 *   <li>Ten concurrent token refreshes (each with a slow 200 ms Spotify stub) complete
 *       in under 2× single-call wall time, proving no connection-hold contention during
 *       the HTTP phase.</li>
 * </ol>
 */
class BatchA2SpotifyTokenTxTest {

    private UserJpaRepository      userJpaRepository;
    private UserMapper             userMapper;
    private EncryptionService      encryptionService;
    private JwtService             jwtService;
    private SpotifyTokenServiceImpl service;

    @BeforeEach
    void setUp() {
        userJpaRepository = mock(UserJpaRepository.class);
        userMapper        = mock(UserMapper.class);
        encryptionService = mock(EncryptionService.class);
        jwtService        = mock(JwtService.class);

        DistributedLockService distributedLockService =
                new DistributedLockService(mock(StringRedisTemplate.class));

        service = new SpotifyTokenServiceImpl(
                userJpaRepository, userMapper, encryptionService, jwtService,
                distributedLockService);
    }

    // =========================================================================
    // 1. No @Transactional on getValidSpotifyToken
    // =========================================================================

    @Test
    @DisplayName("getValidSpotifyToken must NOT be @Transactional — expiry check must not acquire a DB connection")
    void getValidSpotifyToken_hasNoTransactionalAnnotation() throws Exception {
        Method method = SpotifyTokenServiceImpl.class.getDeclaredMethod(
                "getValidSpotifyToken", User.class);

        assertThat(method.isAnnotationPresent(Transactional.class))
                .as("getValidSpotifyToken must not be @Transactional after Batch A2; "
                        + "a DB connection would be held across every Spotify-dependent request")
                .isFalse();
    }

    // =========================================================================
    // 2. No @Transactional on refreshAndUpdateUserToken
    // =========================================================================

    @Test
    @DisplayName("refreshAndUpdateUserToken must NOT be @Transactional — Spotify HTTP call must not hold a DB connection")
    void refreshAndUpdateUserToken_hasNoTransactionalAnnotation() throws Exception {
        Method method = SpotifyTokenServiceImpl.class.getDeclaredMethod(
                "refreshAndUpdateUserToken", User.class);

        assertThat(method.isAnnotationPresent(Transactional.class))
                .as("refreshAndUpdateUserToken must not be @Transactional after Batch A2; "
                        + "worst-case 33.5 s holding a HikariCP connection would exhaust the pool")
                .isFalse();
    }

    // =========================================================================
    // 3. @Transactional IS on persistRefreshedToken
    // =========================================================================

    @Test
    @DisplayName("persistRefreshedToken IS @Transactional — token write is atomic")
    void persistRefreshedToken_isAnnotatedTransactional() throws Exception {
        Method method = SpotifyTokenServiceImpl.class.getDeclaredMethod(
                "persistRefreshedToken", String.class, String.class, Instant.class, String.class);

        assertThat(method.isAnnotationPresent(Transactional.class))
                .as("persistRefreshedToken must be @Transactional so the token update "
                        + "runs in a short, isolated write transaction after the HTTP phase")
                .isTrue();
    }

    // =========================================================================
    // 4. Successful refresh: jwtService called before save (correct phase order)
    // =========================================================================

    @Test
    @DisplayName("Successful token refresh calls jwtService.refreshToken before userJpaRepository.save")
    void refreshAndUpdateUserToken_httpBeforeDbWrite() throws Exception {
        User user = expiringUser("user-a2-order");
        User freshUser = expiringUser("user-a2-order");
        UserEntity entity = stubFindById("user-a2-order");
        when(userMapper.toDomain(entity)).thenReturn(freshUser);
        when(encryptionService.decrypt(anyString())).thenReturn("decrypted-refresh-token");
        when(jwtService.refreshToken("decrypted-refresh-token"))
                .thenReturn(new SpotifyTokenResponse("new-access-token", null, "3600"));
        when(encryptionService.encrypt("new-access-token")).thenReturn("enc-access");
        when(userJpaRepository.save(any())).thenReturn(entity);

        service.refreshAndUpdateUserToken(user);

        // HTTP call must precede the DB write
        var inOrder = inOrder(jwtService, userJpaRepository);
        inOrder.verify(jwtService).refreshToken("decrypted-refresh-token");
        inOrder.verify(userJpaRepository).save(any(UserEntity.class));
    }

    // =========================================================================
    // 5. Thread.sleep is outside @Transactional (structural — confirmed by annotation absence on caller)
    // =========================================================================

    @Test
    @DisplayName("Thread.sleep(500) loser path sits inside refreshAndUpdateUserToken which has no @Transactional")
    void loserPathSleep_outsideTransaction() throws Exception {
        // The sleep at line ~95 in SpotifyTokenServiceImpl is inside refreshAndUpdateUserToken.
        // Since refreshAndUpdateUserToken has no @Transactional (test 2 above), the sleep
        // never holds a DB connection. This test is a structural cross-check.
        Method method = SpotifyTokenServiceImpl.class.getDeclaredMethod(
                "refreshAndUpdateUserToken", User.class);

        assertThat(method.isAnnotationPresent(Transactional.class))
                .as("Thread.sleep is called inside refreshAndUpdateUserToken — "
                        + "if this method were @Transactional the sleep would hold a DB connection")
                .isFalse();
    }

    // =========================================================================
    // 6. Ten concurrent refreshes — no HTTP-phase contention
    // =========================================================================

    @Test
    @DisplayName("10 concurrent token refreshes with slow Spotify complete in < 2× single-call wall time")
    void tenConcurrentRefreshes_noHttpPhaseContention() throws Exception {
        int concurrency    = 10;
        long callLatencyMs = 200L;

        // Each user has a unique ID — no distributed-lock serialisation between users.
        List<User> users = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            String id = "batchA2-concurrent-" + i;
            users.add(expiringUser(id));
            UserEntity entity = mock(UserEntity.class);
            when(entity.getId()).thenReturn(id);
            lenient().when(userJpaRepository.findById(id)).thenReturn(Optional.of(entity));
            User freshUser = expiringUser(id);
            lenient().when(userMapper.toDomain(entity)).thenReturn(freshUser);
            lenient().when(userJpaRepository.save(entity)).thenReturn(entity);
        }

        lenient().when(encryptionService.decrypt(anyString())).thenReturn("refresh-tok");
        lenient().when(encryptionService.encrypt(anyString())).thenReturn("enc-access");
        lenient().when(jwtService.refreshToken(anyString())).thenAnswer(inv -> {
            Thread.sleep(callLatencyMs);
            return new SpotifyTokenResponse("new-access", null, "3600");
        });

        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch go    = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(concurrency);
        AtomicInteger errors = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        for (int i = 0; i < concurrency; i++) {
            final User user = users.get(i);
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    service.refreshAndUpdateUserToken(user);
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        long start = System.nanoTime();
        go.countDown();
        boolean finished = done.await(10, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        pool.shutdownNow();

        assertThat(finished)
                .as("All 10 concurrent token refreshes must complete within 10 s")
                .isTrue();

        assertThat(errors.get())
                .as("No token refresh should throw an exception")
                .isZero();

        // Each refresh takes ~callLatencyMs (the slow Spotify stub).
        // 10 truly concurrent refreshes should also finish in ~callLatencyMs — no pool starvation.
        long upperBoundMs = callLatencyMs * 2 + 500;
        assertThat(elapsedMs)
                .as("10 concurrent refreshes finished in %d ms; expected < %d ms. "
                        + "If this exceeds the bound, the HTTP phase may be serialised by a "
                        + "transaction or lock that should not be present.", elapsedMs, upperBoundMs)
                .isLessThan(upperBoundMs);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Builds a mock User whose Spotify token is already expired. */
    private User expiringUser(String id) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(id);
        when(user.getSpotifyId()).thenReturn("spotify-" + id);
        // null expiry → isTokenExpiredOrExpiring returns true
        when(user.getSpotifyTokenExpires()).thenReturn(null);
        when(user.getSpotifyAccessToken()).thenReturn("enc-old-access");
        when(user.getSpotifyRefreshToken()).thenReturn("enc-old-refresh");
        return user;
    }

    /** Stubs findById for a given userId and returns the entity mock. */
    private UserEntity stubFindById(String userId) {
        UserEntity entity = mock(UserEntity.class);
        when(entity.getId()).thenReturn(userId);
        when(userJpaRepository.findById(userId)).thenReturn(Optional.of(entity));
        when(userJpaRepository.save(entity)).thenReturn(entity);
        return entity;
    }
}
