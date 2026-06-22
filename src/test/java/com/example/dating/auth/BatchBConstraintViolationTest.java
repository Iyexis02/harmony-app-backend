package com.example.dating.auth;

import com.example.dating.DatingApplication;
import com.example.dating.enums.user.AuthProvider;
import com.example.dating.enums.user.RegistrationStage;
import com.example.dating.exceptions.EmailAlreadyExistsException;
import com.example.dating.exceptions.GlobalExceptionHandler;
import com.example.dating.exceptions.SpotifyAlreadyConnectedException;
import com.example.dating.models.auth.ConnectSpotifyRequestDto;
import com.example.dating.models.auth.RegisterRequestDto;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Batch B — Constraint Violation Error Handling in AuthServiceImpl
 *
 * <p>Verifies:
 * <ol>
 *   <li>Structural: {@link GlobalExceptionHandler} has a {@code @ExceptionHandler} for
 *       {@link DataIntegrityViolationException} and returns 409 CONFLICT.</li>
 *   <li>Sequential duplicate email registration throws {@link EmailAlreadyExistsException}
 *       (advisory fast-path).</li>
 *   <li>Sequential duplicate Spotify ID connection throws {@link SpotifyAlreadyConnectedException}
 *       (advisory fast-path) with a probe-resistant generic message.</li>
 *   <li>Concurrent duplicate email registration: exactly one thread succeeds;
 *       the other throws {@link EmailAlreadyExistsException} or
 *       {@link DataIntegrityViolationException} — never an unhandled 500-class exception.</li>
 *   <li>Concurrent duplicate Spotify ID connection: exactly one thread succeeds;
 *       the other throws {@link SpotifyAlreadyConnectedException} or
 *       {@link DataIntegrityViolationException} — never an unhandled 500-class exception.</li>
 * </ol>
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class BatchBConstraintViolationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /** IDs of every user created during a test — deleted in @AfterEach. */
    private final List<String> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        createdUserIds.forEach(id -> userJpaRepository.deleteById(id));
        createdUserIds.clear();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private RegisterRequestDto registerReq(String email) {
        return RegisterRequestDto.builder()
                .email(email)
                .name("BatchB Test User")
                .password("password123")
                .build();
    }

    private UserEntity saveEmailUser(String email) {
        UserEntity entity = UserEntity.builder()
                .id(UUID.randomUUID().toString())
                .email(email)
                .authProvider(AuthProvider.EMAIL)
                .emailVerified(true)
                .passwordHash(passwordEncoder.encode("password123"))
                .tokenVersion(0)
                .registrationStage(RegistrationStage.FINISHED)
                .createdAt(LocalDateTime.now())
                .build();
        UserEntity saved = userJpaRepository.save(entity);
        createdUserIds.add(saved.getId());
        return saved;
    }

    private ConnectSpotifyRequestDto spotifyReq(String spotifyId) {
        return ConnectSpotifyRequestDto.builder()
                .spotifyId(spotifyId)
                .spotifyAccessToken("access-token-" + UUID.randomUUID())
                .spotifyRefreshToken("refresh-token-" + UUID.randomUUID())
                .spotifyTokenExpiresAt(Instant.now().plusSeconds(3600).getEpochSecond())
                .build();
    }

    // ─── Test 1: structural ───────────────────────────────────────────────────

    @Test
    @DisplayName("GlobalExceptionHandler has @ExceptionHandler for DataIntegrityViolationException")
    void globalExceptionHandler_hasDataIntegrityHandler() throws NoSuchMethodException {
        Method handler = GlobalExceptionHandler.class
                .getDeclaredMethod("handleDataIntegrity", DataIntegrityViolationException.class);

        ExceptionHandler annotation = handler.getAnnotation(ExceptionHandler.class);
        assertThat(annotation).as("handleDataIntegrity must be annotated with @ExceptionHandler").isNotNull();

        assertThat(annotation.value())
                .as("@ExceptionHandler must target DataIntegrityViolationException")
                .contains(DataIntegrityViolationException.class);
    }

    // ─── Test 2: sequential duplicate email ──────────────────────────────────

    @Test
    @DisplayName("Sequential duplicate email registration throws EmailAlreadyExistsException")
    void sequentialDuplicateEmail_throwsEmailAlreadyExistsException() {
        String email = "batchb-seq-email-" + UUID.randomUUID() + "@example.com";

        var result = authService.register(registerReq(email));
        assertThat(result).isNotNull();
        // Track the created user for cleanup.
        userJpaRepository.findByEmail(email).ifPresent(u -> createdUserIds.add(u.getId()));

        assertThatThrownBy(() -> authService.register(registerReq(email)))
                .isInstanceOf(EmailAlreadyExistsException.class)
                .hasMessageContaining("already registered");
    }

    // ─── Test 3: sequential duplicate Spotify ID ─────────────────────────────

    @Test
    @DisplayName("Sequential duplicate Spotify ID connection throws SpotifyAlreadyConnectedException")
    void sequentialDuplicateSpotifyId_throwsSpotifyAlreadyConnectedException() {
        String spotifyId = "spotify-seq-" + UUID.randomUUID();

        UserEntity userA = saveEmailUser("batchb-seq-spotify-a-" + UUID.randomUUID() + "@example.com");
        UserEntity userB = saveEmailUser("batchb-seq-spotify-b-" + UUID.randomUUID() + "@example.com");

        authService.connectSpotify(userA.getId(), spotifyReq(spotifyId));
        // Reload A's id after the connect (connectSpotify saves and returns a new domain User).
        userJpaRepository.findByEmail(userA.getEmail()).ifPresent(u -> {
            createdUserIds.remove(u.getId());
            createdUserIds.add(u.getId());
        });

        // Probe-resistance: message is generic, does not confirm whether the *other* user's
        // Spotify ID is in use. Status is 409 (mapped from SpotifyAlreadyConnectedException).
        assertThatThrownBy(() -> authService.connectSpotify(userB.getId(), spotifyReq(spotifyId)))
                .isInstanceOf(SpotifyAlreadyConnectedException.class)
                .hasMessage("Cannot connect this Spotify account");
    }

    // ─── Test 4: concurrent duplicate email ──────────────────────────────────

    @Test
    @DisplayName("Concurrent duplicate email registration: one success, one domain/DB exception — no 500")
    void concurrentDuplicateEmail_onlyOneSucceeds_noUnhandledException() throws InterruptedException {
        String email = "batchb-conc-email-" + UUID.randomUUID() + "@example.com";

        int threadCount = 2;
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(threadCount);

        AtomicInteger successCount      = new AtomicInteger();
        AtomicInteger expectedErrorCount = new AtomicInteger();
        AtomicInteger unexpectedErrors  = new AtomicInteger();
        // Capture the actual unexpected exception for a useful assertion message.
        AtomicReference<Throwable> unexpectedThrowable = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    authService.register(registerReq(email));
                    successCount.incrementAndGet();
                } catch (EmailAlreadyExistsException | DataIntegrityViolationException e) {
                    expectedErrorCount.incrementAndGet();
                } catch (Exception e) {
                    unexpectedThrowable.set(e);
                    unexpectedErrors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();

        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // Track for cleanup.
        userJpaRepository.findByEmail(email).ifPresent(u -> createdUserIds.add(u.getId()));

        assertThat(completed)
                .as("Both threads must complete within 30 s (no deadlock)")
                .isTrue();

        assertThat(unexpectedErrors.get())
                .as("Unexpected exception: " + unexpectedThrowable.get())
                .isZero();

        assertThat(successCount.get())
                .as("Exactly one registration must succeed")
                .isEqualTo(1);

        assertThat(expectedErrorCount.get())
                .as("The other thread must receive a domain or DB constraint exception")
                .isEqualTo(1);
    }

    // ─── Test 5: concurrent duplicate Spotify ID ─────────────────────────────

    @Test
    @DisplayName("Concurrent duplicate Spotify ID connection: one success, one domain/DB exception — no 500")
    void concurrentDuplicateSpotifyId_onlyOneSucceeds_noUnhandledException() throws InterruptedException {
        String spotifyId = "spotify-conc-" + UUID.randomUUID();

        UserEntity userA = saveEmailUser("batchb-conc-spotify-a-" + UUID.randomUUID() + "@example.com");
        UserEntity userB = saveEmailUser("batchb-conc-spotify-b-" + UUID.randomUUID() + "@example.com");

        int threadCount = 2;
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(threadCount);

        AtomicInteger successCount       = new AtomicInteger();
        AtomicInteger expectedErrorCount = new AtomicInteger();
        AtomicInteger unexpectedErrors   = new AtomicInteger();
        AtomicReference<Throwable> unexpectedThrowable = new AtomicReference<>();

        String[] userIds = { userA.getId(), userB.getId() };

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            final String userId = userIds[i];
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    authService.connectSpotify(userId, spotifyReq(spotifyId));
                    successCount.incrementAndGet();
                } catch (SpotifyAlreadyConnectedException | DataIntegrityViolationException e) {
                    expectedErrorCount.incrementAndGet();
                } catch (Exception e) {
                    unexpectedThrowable.set(e);
                    unexpectedErrors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();

        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed)
                .as("Both threads must complete within 30 s (no deadlock)")
                .isTrue();

        assertThat(unexpectedErrors.get())
                .as("Unexpected exception: " + unexpectedThrowable.get())
                .isZero();

        assertThat(successCount.get())
                .as("Exactly one Spotify connection must succeed")
                .isEqualTo(1);

        assertThat(expectedErrorCount.get())
                .as("The other thread must receive a domain or DB constraint exception")
                .isEqualTo(1);
    }
}
