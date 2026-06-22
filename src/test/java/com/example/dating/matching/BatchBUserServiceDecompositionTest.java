package com.example.dating.matching;

import com.example.dating.config.DistributedLockService;
import com.example.dating.mappers.UserMapper;
import com.example.dating.models.auth.SpotifyTokenResponse;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.MatchRepository;
import com.example.dating.repositories.UserBehavioralProfileRepository;
import com.example.dating.repositories.UserGenrePreferenceRepository;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.repositories.UserMatchScoreRepository;
import com.example.dating.repositories.UserSwipeRepository;
import com.example.dating.services.AccountDeletionService;
import com.example.dating.services.EncryptionService;
import com.example.dating.services.JwtService;
import com.example.dating.services.SpotifyTokenService;
import com.example.dating.services.UserService;
import com.example.dating.services.impl.AccountDeletionServiceImpl;
import com.example.dating.services.impl.SpotifyTokenServiceImpl;
import com.example.dating.services.impl.UserServiceImpl;
import com.example.dating.services.matching.BehavioralScoreCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Batch B — UserServiceImpl God Class Decomposition.
 *
 * <p>Verifies:
 * <ol>
 *   <li>Structural: {@link UserServiceImpl} has exactly 4 constructor parameters.</li>
 *   <li>Structural: {@link SpotifyTokenServiceImpl} carries {@code distributedLockService} and
 *       has exactly 5 constructor parameters (Batch J: DistributedLockService added).</li>
 *   <li>Structural: {@link AccountDeletionServiceImpl} has exactly 8 constructor parameters
 *       (Batch C: UserRepository removed).</li>
 *   <li>Interface segregation: {@link UserService} does NOT declare
 *       {@code getValidSpotifyToken} or {@code deleteAccount}.</li>
 *   <li>Concurrency: 5 simultaneous token-refresh calls for the same user result in
 *       exactly 1 Spotify API call ({@code distributedLockService} serialises correctly
 *       inside {@code SpotifyTokenServiceImpl}).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class BatchBUserServiceDecompositionTest {

    // ── shared mocks ─────────────────────────────────────────────────────────

    @Mock private EncryptionService               encryptionService;
    @Mock private JwtService                      jwtService;
    @Mock private UserMapper                      userMapper;
    @Mock private PasswordEncoder                 passwordEncoder;
    @Mock private UserJpaRepository               userJpaRepository;
    @Mock private UserSwipeRepository             userSwipeRepository;
    @Mock private MatchRepository                 matchRepository;
    @Mock private UserMatchScoreRepository        userMatchScoreRepository;
    @Mock private UserGenrePreferenceRepository   userGenrePreferenceRepository;
    @Mock private UserBehavioralProfileRepository userBehavioralProfileRepository;
    @Mock private BehavioralScoreCalculator       behavioralScoreCalculator;

    // ── Test 1: UserServiceImpl has exactly 4 constructor dependencies ────────

    @Test
    @DisplayName("UserServiceImpl has exactly 4 constructor parameters (CRUD only)")
    void userServiceImpl_hasFourConstructorDependencies() {
        Constructor<?>[] ctors = UserServiceImpl.class.getDeclaredConstructors();
        // Lombok @RequiredArgsConstructor generates exactly one constructor.
        assertEquals(1, ctors.length, "UserServiceImpl must have exactly one constructor");
        assertEquals(4, ctors[0].getParameterCount(),
                "UserServiceImpl constructor must have exactly 4 parameters: " +
                "UserJpaRepository, EncryptionService, UserMapper, PasswordEncoder");
    }

    // ── Test 2: SpotifyTokenServiceImpl has 5 constructor deps + distributedLockService ──

    @Test
    @DisplayName("SpotifyTokenServiceImpl has 5 constructor parameters (Batch J) and owns distributedLockService")
    void spotifyTokenServiceImpl_hasFiveConstructorDepsAndOwnsDistributedLock() throws Exception {
        Constructor<?>[] ctors = SpotifyTokenServiceImpl.class.getDeclaredConstructors();
        assertEquals(1, ctors.length, "SpotifyTokenServiceImpl must have exactly one constructor");
        assertEquals(5, ctors[0].getParameterCount(),
                "SpotifyTokenServiceImpl constructor must have exactly 5 parameters: " +
                "UserJpaRepository, UserMapper, EncryptionService, JwtService, DistributedLockService");

        // distributedLockService must be a non-static instance field of type DistributedLockService
        Field lockField = SpotifyTokenServiceImpl.class.getDeclaredField("distributedLockService");
        lockField.setAccessible(true);
        assertThat(lockField.getType()).isEqualTo(DistributedLockService.class);
    }

    // ── Test 3: AccountDeletionServiceImpl has 8 constructor dependencies ─────

    @Test
    @DisplayName("AccountDeletionServiceImpl has exactly 8 constructor parameters (Batch C: UserRepository removed)")
    void accountDeletionServiceImpl_hasEightConstructorDependencies() {
        Constructor<?>[] ctors = AccountDeletionServiceImpl.class.getDeclaredConstructors();
        assertEquals(1, ctors.length, "AccountDeletionServiceImpl must have exactly one constructor");
        assertEquals(8, ctors[0].getParameterCount(),
                "AccountDeletionServiceImpl constructor must have exactly 8 parameters");
    }

    // ── Test 4: UserService interface has no token or deletion methods ─────────

    @Test
    @DisplayName("UserService interface does not declare getValidSpotifyToken or deleteAccount")
    void userService_interfaceDoesNotDeclareRemovedMethods() throws Exception {
        // getValidSpotifyToken must NOT exist on UserService
        assertThrows(NoSuchMethodException.class,
                () -> UserService.class.getMethod("getValidSpotifyToken", User.class),
                "UserService must not declare getValidSpotifyToken — it belongs to SpotifyTokenService");

        // deleteAccount must NOT exist on UserService
        assertThrows(NoSuchMethodException.class,
                () -> UserService.class.getMethod("deleteAccount", String.class, String.class),
                "UserService must not declare deleteAccount — it belongs to AccountDeletionService");

        // The moved methods must exist on their new interfaces
        assertNotNull(SpotifyTokenService.class.getMethod("getValidSpotifyToken", User.class),
                "SpotifyTokenService must declare getValidSpotifyToken");
        assertNotNull(AccountDeletionService.class.getMethod("deleteAccount", String.class, String.class),
                "AccountDeletionService must declare deleteAccount");
    }

    // ── Test 5: Concurrent token refresh results in exactly 1 Spotify API call ─

    @Test
    @DisplayName("5 concurrent refreshAndUpdateUserToken calls for the same user result in exactly 1 Spotify API call")
    void spotifyTokenService_concurrent_exactlyOneSpotifyCall() throws Exception {
        String userId          = "user-batch-b-concurrent";
        String encOldRefresh   = "enc_old_refresh_bb";
        String plainOldRefresh = "plain_old_refresh_bb";
        String newAccessToken  = "new_access_bb";
        String encNewAccess    = "enc_new_access_bb";

        User expiredUser = buildExpiredUser(userId, encOldRefresh);
        User freshUser   = buildFreshUser(userId, encNewAccess);

        // Use distinct entity mocks so Mockito can route toDomain() correctly.
        UserEntity expiredEntity = mock(UserEntity.class);
        UserEntity freshEntity   = mock(UserEntity.class);

        // First DB read (the thread that wins the lock) → expired.
        // All subsequent reads (threads that enter the lock after the winner) → fresh.
        when(userJpaRepository.findById(userId))
                .thenReturn(
                    Optional.of(expiredEntity),
                    Optional.of(freshEntity),
                    Optional.of(freshEntity),
                    Optional.of(freshEntity),
                    Optional.of(freshEntity));

        when(userMapper.toDomain(expiredEntity)).thenReturn(expiredUser);
        when(userMapper.toDomain(freshEntity)).thenReturn(freshUser);

        when(encryptionService.decrypt(encOldRefresh)).thenReturn(plainOldRefresh);
        when(encryptionService.decrypt(encNewAccess)).thenReturn(newAccessToken);
        when(encryptionService.encrypt(newAccessToken)).thenReturn(encNewAccess);

        SpotifyTokenResponse tokenResponse = mock(SpotifyTokenResponse.class);
        when(tokenResponse.getAccess_token()).thenReturn(newAccessToken);
        when(tokenResponse.getExpires_in()).thenReturn("3600");
        when(tokenResponse.getRefresh_token()).thenReturn(null);

        AtomicInteger spotifyCallCount = new AtomicInteger(0);
        // Artificial delay so all 5 threads pile up at the lock boundary.
        when(jwtService.refreshToken(plainOldRefresh)).thenAnswer(inv -> {
            spotifyCallCount.incrementAndGet();
            Thread.sleep(60);
            return tokenResponse;
        });

        // DistributedLockService: mock Redis with no stubs — setIfAbsent() returns null by
        // default, triggering the in-memory fallback that preserves the single-instance
        // serialisation behaviour being tested here.
        DistributedLockService distributedLockService =
                new DistributedLockService(mock(StringRedisTemplate.class));

        SpotifyTokenServiceImpl service = new SpotifyTokenServiceImpl(
                userJpaRepository, userMapper, encryptionService, jwtService, distributedLockService);

        int threadCount = 5;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        ExecutorService pool  = Executors.newFixedThreadPool(threadCount);

        Future<?>[] futures = new Future[threadCount];
        for (int i = 0; i < threadCount; i++) {
            User threadUser = buildExpiredUser(userId, encOldRefresh);
            futures[i] = pool.submit(() -> {
                barrier.await();
                return service.refreshAndUpdateUserToken(threadUser);
            });
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS),
                "Thread pool must terminate within 15 seconds");

        for (Future<?> f : futures) {
            assertThat((String) f.get())
                    .as("Every thread must receive a non-blank access token")
                    .isNotNull()
                    .isNotBlank();
        }

        assertThat(spotifyCallCount.get())
                .as("Exactly 1 Spotify API call expected regardless of 5-thread concurrency")
                .isEqualTo(1);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private User buildExpiredUser(String userId, String encRefresh) {
        return User.builder()
                .id(userId)
                .spotifyId("spotify_" + userId)
                .spotifyAccessToken("enc_old_access")
                .spotifyRefreshToken(encRefresh)
                .spotifyTokenExpires(Instant.now().minusSeconds(200))
                .build();
    }

    private User buildFreshUser(String userId, String encAccess) {
        return User.builder()
                .id(userId)
                .spotifyId("spotify_" + userId)
                .spotifyAccessToken(encAccess)
                .spotifyRefreshToken("enc_refresh")
                .spotifyTokenExpires(Instant.now().plusSeconds(3600))
                .build();
    }
}
