package com.example.dating.auth;

import com.example.dating.DatingApplication;
import com.example.dating.enums.user.AuthProvider;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.AuthService;
import com.example.dating.services.EmailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Verifies Batch J — Auth Error Handling Consistency.
 *
 * <p>Before the fix, {@code AuthServiceImpl.forgotPassword()} rethrew a
 * {@code RuntimeException} when {@code EmailService.sendPasswordResetEmail()} failed.
 * Spring's {@code @Transactional} interceptor then marked the transaction for rollback
 * and {@code GlobalExceptionHandler} returned HTTP 500 — even though the DB write
 * had already succeeded and the token was valid.
 *
 * <p>After the fix the catch block only logs and the method returns normally,
 * matching the behaviour of {@code register()} (which has always swallowed email
 * failures silently).
 *
 * <h3>Test 1 — email service throws → no exception propagated</h3>
 * Stubs {@link EmailService#sendPasswordResetEmail} to throw, calls
 * {@code forgotPassword()}, and asserts no exception escapes.
 *
 * <h3>Test 2 — unknown email → returns silently (security: no user-existence leak)</h3>
 * Confirms that requests for unregistered addresses also return without throwing.
 *
 * <h3>Test 3 — email service throws → the send was still attempted</h3>
 * Verifies the stubbed method was invoked, confirming the code reaches the send call
 * and does not short-circuit before it.
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class ForgotPasswordEmailFailureTest {

    private static final String TEST_EMAIL = "forgot.password.batch.j@test.com";

    @Autowired
    private AuthService authService;

    /** Replace the real EmailService bean so no SMTP call is made during tests. */
    @MockBean
    private EmailService emailService;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @BeforeEach
    void setUp() {
        userJpaRepository.findByEmail(TEST_EMAIL).orElseGet(() ->
                userJpaRepository.save(UserEntity.builder()
                        .email(TEST_EMAIL)
                        .name("Batch J Test User")
                        .authProvider(AuthProvider.EMAIL)
                        .emailVerified(false)
                        .build()));
    }

    @AfterEach
    void tearDown() {
        userJpaRepository.findByEmail(TEST_EMAIL)
                .ifPresent(userJpaRepository::delete);
    }

    // -------------------------------------------------------------------------

    @Test
    @DisplayName("forgotPassword() returns normally when email service is unreachable — no 500")
    void forgotPassword_doesNotThrow_whenEmailServiceFails() {
        doThrow(new RuntimeException("SMTP unreachable"))
                .when(emailService).sendPasswordResetEmail(anyString(), anyString(), anyString());

        assertDoesNotThrow(
                () -> authService.forgotPassword(TEST_EMAIL),
                "forgotPassword() must not propagate email failures; callers must receive 200, not 500");
    }

    @Test
    @DisplayName("forgotPassword() for unknown email returns silently — no user-existence leak")
    void forgotPassword_doesNotThrow_forUnknownEmail() {
        assertDoesNotThrow(
                () -> authService.forgotPassword("nobody.batch.j@nowhere.example"),
                "forgotPassword() must not throw for unknown emails");
    }

    @Test
    @DisplayName("forgotPassword() still attempts to send the email before swallowing the failure")
    void forgotPassword_attemptsEmailSend_beforeSwallowingFailure() {
        doThrow(new RuntimeException("SMTP unreachable"))
                .when(emailService).sendPasswordResetEmail(anyString(), anyString(), anyString());

        authService.forgotPassword(TEST_EMAIL);

        verify(emailService).sendPasswordResetEmail(anyString(), anyString(), anyString());
    }
}
