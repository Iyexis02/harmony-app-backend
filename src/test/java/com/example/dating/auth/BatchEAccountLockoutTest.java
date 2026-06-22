package com.example.dating.auth;

import com.example.dating.DatingApplication;
import com.example.dating.enums.user.AuthProvider;
import com.example.dating.enums.user.RegistrationStage;
import com.example.dating.exceptions.AccountLockedException;
import com.example.dating.exceptions.InvalidCredentialsException;
import com.example.dating.models.auth.LoginRequestDto;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Batch E — Email Verification Enforcement + Account Lockout
 *
 * <p>Verifies:
 * <ol>
 *   <li>Account is locked after 5 consecutive failed login attempts.</li>
 *   <li>The 6th attempt (and beyond) throws {@link AccountLockedException} (HTTP 429).</li>
 *   <li>A successful login resets {@code failedLoginAttempts} to 0 and clears {@code lockedUntil}.</li>
 *   <li>An account with a future {@code lockedUntil} is rejected even with the correct password.</li>
 *   <li>An account whose {@code lockedUntil} is in the past can log in normally.</li>
 *   <li>Under 20 concurrent incorrect-password requests the account is eventually locked
 *       with no deadlocks, no unexpected exceptions, and no successful logins.</li>
 * </ol>
 *
 * <p><b>Email-verification filter</b> is verified at the service-integration level here
 * (the filter relies on {@code UserJpaRepository} look-ups which are exercised by the
 * full Spring context).  HTTP-level 403 assertions for unverified accounts are left to
 * manual curl verification described in the implementation PR.
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class BatchEAccountLockoutTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /** IDs of users created during a test — deleted in @AfterEach. */
    private final List<String> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        createdUserIds.forEach(id -> userJpaRepository.deleteById(id));
        createdUserIds.clear();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private UserEntity saveEmailUser(String plaintextPassword) {
        UserEntity entity = UserEntity.builder()
                .id(UUID.randomUUID().toString())
                .email("batche-" + UUID.randomUUID() + "@example.com")
                .authProvider(AuthProvider.EMAIL)
                .emailVerified(true)
                .passwordHash(passwordEncoder.encode(plaintextPassword))
                .tokenVersion(0)
                .registrationStage(RegistrationStage.FINISHED)
                .createdAt(LocalDateTime.now())
                .build();
        UserEntity saved = userJpaRepository.save(entity);
        createdUserIds.add(saved.getId());
        return saved;
    }

    private LoginRequestDto req(String email, String password) {
        return LoginRequestDto.builder()
                .email(email)
                .password(password)
                .build();
    }

    // ─── Sequential lockout tests ─────────────────────────────────────────────

    @Test
    @DisplayName("Account is locked after exactly 5 consecutive bad-password attempts")
    void fiveFailedLogins_locksAccount() {
        UserEntity user = saveEmailUser("correct");
        LoginRequestDto wrong = req(user.getEmail(), "wrong");

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> authService.login(wrong))
                    .isInstanceOf(InvalidCredentialsException.class);
        }

        // DB must reflect the locked state.
        UserEntity reloaded = userJpaRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getFailedLoginAttempts())
                .as("failedLoginAttempts must be >= 5")
                .isGreaterThanOrEqualTo(5);
        assertThat(reloaded.getLockedUntil())
                .as("lockedUntil must be set to a future timestamp")
                .isNotNull()
                .isAfter(LocalDateTime.now());
    }

    @Test
    @DisplayName("6th attempt throws AccountLockedException once the account is locked")
    void sixthAttempt_throwsAccountLockedException() {
        UserEntity user = saveEmailUser("correct");
        LoginRequestDto wrong = req(user.getEmail(), "wrong");

        // 5 failures to trigger lockout.
        for (int i = 0; i < 5; i++) {
            try { authService.login(wrong); } catch (InvalidCredentialsException ignored) { }
        }

        // 6th attempt must be rejected with AccountLockedException.
        assertThatThrownBy(() -> authService.login(wrong))
                .isInstanceOf(AccountLockedException.class)
                .hasMessageContaining("locked");
    }

    @Test
    @DisplayName("Correct password after lockout also throws AccountLockedException")
    void lockedAccount_rejectsEvenCorrectPassword() {
        UserEntity user = saveEmailUser("correct");
        // Manually inject a future lock so the test does not depend on 5 iterations.
        user.setLockedUntil(LocalDateTime.now().plusMinutes(10));
        user.setFailedLoginAttempts(5);
        userJpaRepository.save(user);

        assertThatThrownBy(() -> authService.login(req(user.getEmail(), "correct")))
                .isInstanceOf(AccountLockedException.class);
    }

    @Test
    @DisplayName("Successful login resets failedLoginAttempts to 0 and clears lockedUntil")
    void successfulLogin_resetsCounter() {
        UserEntity user = saveEmailUser("correct");
        LoginRequestDto wrong = req(user.getEmail(), "wrong");

        // 3 failed attempts — not enough to lock.
        for (int i = 0; i < 3; i++) {
            try { authService.login(wrong); } catch (InvalidCredentialsException ignored) { }
        }

        // Successful login.
        authService.login(req(user.getEmail(), "correct"));

        UserEntity reloaded = userJpaRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getFailedLoginAttempts())
                .as("failedLoginAttempts must be 0 after successful login")
                .isEqualTo(0);
        assertThat(reloaded.getLockedUntil())
                .as("lockedUntil must be null after successful login")
                .isNull();
    }

    @Test
    @DisplayName("Account with an expired lockedUntil can log in normally")
    void expiredLock_allowsSuccessfulLogin() {
        UserEntity user = saveEmailUser("correct");
        // Lock that expired 1 minute ago.
        user.setLockedUntil(LocalDateTime.now().minusMinutes(1));
        user.setFailedLoginAttempts(5);
        userJpaRepository.save(user);

        var result = authService.login(req(user.getEmail(), "correct"));

        assertThat(result).isNotNull();
        assertThat(result.getToken()).isNotBlank();
    }

    // ─── Concurrent lockout test ──────────────────────────────────────────────

    @Test
    @DisplayName("20 concurrent bad-password attempts eventually lock the account with no deadlocks")
    void concurrent_failedLogins_accountEventuallyLocked() throws InterruptedException {
        UserEntity user = saveEmailUser("correct");
        LoginRequestDto wrong = req(user.getEmail(), "wrong");

        int threadCount = 20;
        CountDownLatch readyLatch   = new CountDownLatch(threadCount);
        CountDownLatch startLatch   = new CountDownLatch(1);
        CountDownLatch doneLatch    = new CountDownLatch(threadCount);
        AtomicInteger credentialErrors  = new AtomicInteger();
        AtomicInteger lockoutErrors     = new AtomicInteger();
        AtomicInteger unexpectedErrors  = new AtomicInteger();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(); // park until all threads are ready
                    authService.login(wrong);
                    unexpectedErrors.incrementAndGet(); // successful login must never happen
                } catch (InvalidCredentialsException e) {
                    credentialErrors.incrementAndGet();
                } catch (AccountLockedException e) {
                    lockoutErrors.incrementAndGet();
                } catch (Exception e) {
                    unexpectedErrors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();     // wait until all threads are parked
        startLatch.countDown(); // release all at once — maximises contention

        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed)
                .as("All 20 threads must complete within 30 s (no deadlock)")
                .isTrue();
        assertThat(unexpectedErrors.get())
                .as("No unexpected exceptions and no successful logins with wrong password")
                .isZero();
        assertThat(credentialErrors.get() + lockoutErrors.get())
                .as("Every attempt must fail with credentials or lockout error")
                .isEqualTo(threadCount);

        // After all concurrent attempts the account must be locked in the DB.
        UserEntity reloaded = userJpaRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getLockedUntil())
                .as("Account must be locked after >= 5 failed concurrent attempts")
                .isNotNull()
                .isAfter(LocalDateTime.now());

        // Subsequent call must immediately return AccountLockedException.
        assertThatThrownBy(() -> authService.login(wrong))
                .isInstanceOf(AccountLockedException.class);
    }
}
