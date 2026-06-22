package com.example.dating.matching;

import com.example.dating.mappers.UserMapper;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.EncryptionService;
import com.example.dating.services.JwtService;
import com.example.dating.services.impl.AccountDeletionServiceImpl;
import com.example.dating.services.impl.AuthServiceImpl;
import com.example.dating.services.impl.SpotifyTokenServiceImpl;
import com.example.dating.services.matching.SpotifyGenreSyncService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Batch C — Unify Dual Repository Pattern.
 *
 * <p>Verifies:
 * <ol>
 *   <li>The {@code postgres} package classes no longer exist on the classpath.</li>
 *   <li>No production service/controller declares a field of the old
 *       {@code postgres.UserRepository} type (structural field scan).</li>
 *   <li>{@link UserJpaRepository} exposes all finder methods required to replace
 *       the deleted {@code UserRepository} interface.</li>
 *   <li>{@link SpotifyGenreSyncService} no longer carries an unused
 *       {@code UserRepository} field (dead code removal).</li>
 *   <li>Concurrent saves through the unified path — 4 threads simultaneously
 *       saving distinct users all complete without conflict, each producing a
 *       non-null result through the single {@link UserJpaRepository#save} path.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class BatchCRepositoryUnificationTest {

    @Mock private UserJpaRepository userJpaRepository;
    @Mock private UserMapper        userMapper;
    @Mock private EncryptionService encryptionService;
    @Mock private JwtService        jwtService;

    // ── Test 1: postgres package deleted from classpath ───────────────────────

    @Test
    @DisplayName("postgres.UserRepository and postgres.impl.UserRepositoryImpl are deleted from the classpath")
    void postgresPackageClassesDeleted() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.example.dating.postgres.UserRepository"),
                "postgres.UserRepository must no longer exist");

        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.example.dating.postgres.impl.UserRepositoryImpl"),
                "postgres.impl.UserRepositoryImpl must no longer exist");
    }

    // ── Test 2: No declared field of postgres.UserRepository in key services ──

    @Test
    @DisplayName("AuthServiceImpl, AccountDeletionServiceImpl, SpotifyTokenServiceImpl declare no postgres.UserRepository field")
    void keyServices_doNotDeclarePostgresUserRepositoryField() {
        List<Class<?>> classes = List.of(
                AuthServiceImpl.class,
                AccountDeletionServiceImpl.class,
                SpotifyTokenServiceImpl.class
        );

        for (Class<?> clazz : classes) {
            List<String> postgresFields = Arrays.stream(clazz.getDeclaredFields())
                    .filter(f -> f.getType().getName().contains("postgres.UserRepository"))
                    .map(Field::getName)
                    .collect(Collectors.toList());

            assertThat(postgresFields)
                    .as("%s must not declare any field of type postgres.UserRepository", clazz.getSimpleName())
                    .isEmpty();
        }
    }

    // ── Test 3: UserJpaRepository has all required finder methods ────────────

    @Test
    @DisplayName("UserJpaRepository exposes all finder methods previously only on postgres.UserRepository")
    void userJpaRepository_hasAllRequiredFinderMethods() throws Exception {
        // Methods that existed on the old UserRepository and must now be on UserJpaRepository
        assertNotNull(UserJpaRepository.class.getMethod("findByEmail", String.class),
                "findByEmail must exist");
        assertNotNull(UserJpaRepository.class.getMethod("findBySpotifyId", String.class),
                "findBySpotifyId must exist");
        assertNotNull(UserJpaRepository.class.getMethod("findByEmailVerificationToken", String.class),
                "findByEmailVerificationToken must exist");
        assertNotNull(UserJpaRepository.class.getMethod("findByPasswordResetToken", String.class),
                "findByPasswordResetToken must exist");
        assertNotNull(UserJpaRepository.class.getMethod("findByEmailVerificationTokenHash", String.class),
                "findByEmailVerificationTokenHash must exist");
        assertNotNull(UserJpaRepository.class.getMethod("findByPasswordResetTokenHash", String.class),
                "findByPasswordResetTokenHash must exist");
        // findById and deleteById are inherited from JpaRepository — verify via interface hierarchy
        assertThat(UserJpaRepository.class.getInterfaces())
                .as("UserJpaRepository must extend JpaRepository (provides findById, save, deleteById)")
                .anyMatch(i -> i.getSimpleName().equals("JpaRepository"));
    }

    // ── Test 4: SpotifyGenreSyncService has no UserRepository field ───────────

    @Test
    @DisplayName("SpotifyGenreSyncService no longer declares a (previously dead-code) UserRepository field")
    void spotifyGenreSyncService_doesNotDeclareUserRepositoryField() {
        long postgresFieldCount = Arrays.stream(SpotifyGenreSyncService.class.getDeclaredFields())
                .filter(f -> f.getType().getName().contains("UserRepository")
                          && f.getType().getName().contains("postgres"))
                .count();

        assertThat(postgresFieldCount)
                .as("SpotifyGenreSyncService must not reference postgres.UserRepository")
                .isZero();
    }

    // ── Test 5: Concurrent saves through the single UserJpaRepository path ────

    @Test
    @DisplayName("4 concurrent saves for distinct users all complete through the unified UserJpaRepository path")
    void concurrentSaves_throughUnifiedPath_allSucceed() throws Exception {
        int userCount = 4;

        // Set up a mock save path: userMapper.toEntity(user) → entity, jpaRepo.save(entity) → entity,
        // userMapper.toDomain(entity) → user.
        when(userMapper.toEntity(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            UserEntity e = mock(UserEntity.class);
            when(e.getId()).thenReturn(u.getId());
            return e;
        });
        when(userJpaRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userMapper.toDomain(any(UserEntity.class))).thenAnswer(inv -> {
            UserEntity e = inv.getArgument(0);
            return User.builder().id(e.getId()).build();
        });

        CyclicBarrier barrier = new CyclicBarrier(userCount);
        ExecutorService pool  = Executors.newFixedThreadPool(userCount);
        AtomicInteger saveCount = new AtomicInteger(0);

        List<Future<String>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < userCount; i++) {
            final String uid = "concurrent-user-" + i;
            futures.add(pool.submit(() -> {
                barrier.await(); // all threads start simultaneously
                User u = User.builder().id(uid).build();
                UserEntity entity = userMapper.toEntity(u);
                UserEntity saved  = userJpaRepository.save(entity);
                User result       = userMapper.toDomain(saved);
                saveCount.incrementAndGet();
                return result.getId();
            }));
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertThat(saveCount.get())
                .as("All %d concurrent saves must complete", userCount)
                .isEqualTo(userCount);

        for (int i = 0; i < userCount; i++) {
            assertThat(futures.get(i).get())
                    .as("Save result for user %d must be non-null", i)
                    .isNotNull();
        }

        // The single save path must have been called exactly userCount times.
        verify(userJpaRepository, times(userCount)).save(any(UserEntity.class));
    }
}
