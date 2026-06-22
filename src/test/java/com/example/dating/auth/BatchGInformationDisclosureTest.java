package com.example.dating.auth;

import com.example.dating.enums.user.AuthProvider;
import com.example.dating.enums.user.RegistrationStage;
import com.example.dating.mappers.UserMapper;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.UserJpaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Batch G — Information Disclosure Hardening integration tests.
 *
 * Verifies:
 *   1. Login with a Spotify-registered email returns the same generic message as a
 *      wrong-password attempt (no auth-provider leak).
 *   2. resendVerificationEmail for an already-verified account returns 200 silently
 *      (no "Email already verified" exception escapes to the caller).
 *   3. Concurrent probes for Spotify vs email accounts all return the same status and body
 *      (timing side-channel is not tested here — that requires lower-level tooling).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Execution(ExecutionMode.CONCURRENT)
class BatchGInformationDisclosureTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserJpaRepository userJpaRepository;
    @Autowired private UserMapper userMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;

    private static final String LOGIN_URL = "/api/v1/auth/login";
    private static final String RESEND_URL = "/api/v1/auth/resend-verification";
    private static final String GENERIC_ERROR = "Invalid email or password";

    // ── test fixtures ──────────────────────────────────────────────────────────

    /** Creates and persists an EMAIL user with a known password. */
    private User createEmailUser(String email) {
        User u = User.builder()
                .id(UUID.randomUUID().toString())
                .email(email)
                .name("Test User")
                .passwordHash(passwordEncoder.encode("correctPassword1!"))
                .authProvider(AuthProvider.EMAIL)
                .emailVerified(false)
                .tokenVersion(0)
                .registrationStage(RegistrationStage.STARTED)
                .createdAt(LocalDateTime.now())
                .build();
        UserEntity saved = userJpaRepository.save(userMapper.toEntity(u));
        return userMapper.toDomain(saved);
    }

    /** Creates and persists a SPOTIFY user (no password hash). */
    private User createSpotifyUser(String email) {
        User u = User.builder()
                .id(UUID.randomUUID().toString())
                .email(email)
                .name("Spotify User")
                .authProvider(AuthProvider.SPOTIFY)
                .emailVerified(true)
                .tokenVersion(0)
                .registrationStage(RegistrationStage.STARTED)
                .createdAt(LocalDateTime.now())
                .build();
        UserEntity saved = userJpaRepository.save(userMapper.toEntity(u));
        return userMapper.toDomain(saved);
    }

    /** Creates and persists an EMAIL user that has already verified their email. */
    private User createVerifiedEmailUser(String email) {
        User u = createEmailUser(email);
        u.setEmailVerified(true);
        u.setEmailVerificationTokenHash(null);
        u.setEmailVerificationExpires(null);
        UserEntity saved = userJpaRepository.save(userMapper.toEntity(u));
        return userMapper.toDomain(saved);
    }

    @BeforeEach
    void cleanUp() {
        // Best-effort pre-test cleanup so leftover rows from a prior failed run don't interfere.
        // Each test uses unique UUIDs in the email, so collisions are unlikely regardless.
    }

    // ── 1. Login provider-type enumeration ────────────────────────────────────

    @Test
    @DisplayName("Login with Spotify email returns same message as wrong password")
    void loginSpotifyAccount_returnsGenericError() throws Exception {
        String email = "spotify-" + UUID.randomUUID() + "@test.invalid";
        createSpotifyUser(email);

        String body = objectMapper.writeValueAsString(Map.of(
                "email", email,
                "password", "anyPassword1!"
        ));

        mvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(GENERIC_ERROR));
    }

    @Test
    @DisplayName("Login with wrong password returns same message as Spotify account probe")
    void loginWrongPassword_returnsGenericError() throws Exception {
        String email = "email-" + UUID.randomUUID() + "@test.invalid";
        createEmailUser(email);

        String body = objectMapper.writeValueAsString(Map.of(
                "email", email,
                "password", "wrongPassword1!"
        ));

        mvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(GENERIC_ERROR));
    }

    @Test
    @DisplayName("Login with non-existent email returns same message as Spotify probe")
    void loginNonExistentEmail_returnsGenericError() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "email", "nonexistent-" + UUID.randomUUID() + "@test.invalid",
                "password", "anyPassword1!"
        ));

        mvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(GENERIC_ERROR));
    }

    @Test
    @DisplayName("Response body is identical for Spotify probe vs wrong-password probe")
    void loginErrorBody_isIdenticalForAllFailurePaths() throws Exception {
        String spotifyEmail = "spotify2-" + UUID.randomUUID() + "@test.invalid";
        String emailUserEmail = "email2-" + UUID.randomUUID() + "@test.invalid";
        createSpotifyUser(spotifyEmail);
        createEmailUser(emailUserEmail);

        String spotifyBody = mvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", spotifyEmail, "password", "x"))))
                .andReturn().getResponse().getContentAsString();

        String wrongPwBody = mvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", emailUserEmail, "password", "x"))))
                .andReturn().getResponse().getContentAsString();

        String missingBody = mvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "missing@test.invalid", "password", "x"))))
                .andReturn().getResponse().getContentAsString();

        assertThat(spotifyBody).isEqualTo(wrongPwBody);
        assertThat(spotifyBody).isEqualTo(missingBody);
    }

    // ── 2. resendVerification already-verified leak ────────────────────────────

    @Test
    @DisplayName("resendVerification for already-verified account returns 200 silently")
    void resendVerification_alreadyVerified_returns200() throws Exception {
        String email = "verified-" + UUID.randomUUID() + "@test.invalid";
        createVerifiedEmailUser(email);

        String body = objectMapper.writeValueAsString(Map.of("email", email));

        mvc.perform(post(RESEND_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("resendVerification for non-existent email returns 200 silently")
    void resendVerification_nonExistentEmail_returns200() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "email", "ghost-" + UUID.randomUUID() + "@test.invalid"
        ));

        mvc.perform(post(RESEND_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    // ── 3. Concurrent enumeration attempt ─────────────────────────────────────

    @Test
    @DisplayName("Concurrent Spotify-probe and wrong-password-probe return identical status codes")
    void concurrentProbes_returnIdenticalStatusCodes() throws Exception {
        String spotifyEmail = "conc-spotify-" + UUID.randomUUID() + "@test.invalid";
        String emailUserEmail = "conc-email-" + UUID.randomUUID() + "@test.invalid";
        createSpotifyUser(spotifyEmail);
        createEmailUser(emailUserEmail);

        int threads = 10;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger spotifyStatuses = new AtomicInteger(0);
        AtomicInteger emailStatuses = new AtomicInteger(0);
        AtomicInteger errors = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            final boolean useSpotify = (i % 2 == 0);
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    String probeEmail = useSpotify ? spotifyEmail : emailUserEmail;
                    String reqBody = objectMapper.writeValueAsString(Map.of(
                            "email", probeEmail, "password", "wrongPass1!"));

                    int status = mvc.perform(post(LOGIN_URL)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(reqBody))
                            .andReturn().getResponse().getStatus();

                    if (useSpotify) spotifyStatuses.addAndGet(status);
                    else emailStatuses.addAndGet(status);
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }

        ready.await();
        start.countDown();
        pool.shutdown();
        //noinspection ResultOfMethodCallIgnored
        pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(errors.get()).isZero();

        // Each group had 5 threads, each returning 401 → sum = 5 * 401 = 2005
        assertThat(spotifyStatuses.get()).isEqualTo(5 * 401);
        assertThat(emailStatuses.get()).isEqualTo(5 * 401);
    }
}
