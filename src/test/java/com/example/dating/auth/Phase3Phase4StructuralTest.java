package com.example.dating.auth;

import com.example.dating.security.EmailVerificationFilter;
import com.example.dating.services.impl.AuthServiceImpl;
import com.example.dating.services.impl.EmailServiceImpl;
import com.example.dating.services.matching.MatchLifecycleListener;
import com.example.dating.services.matching.MatchReconciliationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural regression guards for Phase 3 + 4 (Async robustness + Perf).
 * Pure JUnit — no Spring context, no DB.
 *
 * <p>These tests fail fast if a future change reverts a critical contract:
 * <ul>
 *   <li>{@code MatchLifecycleListener} has the {@code unmatchWithRetry} helper
 *       (block→unmatch retry is the difference between "5-minute reconciliation
 *       lag" and "user sees blocked profile in their match list").</li>
 *   <li>{@code MatchReconciliationService.reconcileBlockedMatches} is still
 *       {@code @Scheduled} — losing the annotation silently turns the safety
 *       net into dead code.</li>
 *   <li>{@code EmailVerificationFilter} exposes the {@code evictEmailVerified}
 *       eviction hook that {@code AuthServiceImpl.verifyEmail} calls.</li>
 *   <li>{@code AuthServiceImpl} depends on {@code EmailVerificationFilter} —
 *       proves the eviction is wired in (compile-time guarantee, but explicit
 *       test makes the dependency contract visible).</li>
 *   <li>{@code EmailServiceImpl} exposes the {@code sendWithRetry} private
 *       helper — proves the per-method retry consolidation hasn't been
 *       quietly reverted to per-method copy-paste.</li>
 * </ul>
 */
class Phase3Phase4StructuralTest {

    @Test
    @DisplayName("MatchLifecycleListener has unmatchWithRetry helper")
    void matchLifecycleListener_hasRetryHelper() {
        boolean hasHelper = Arrays.stream(MatchLifecycleListener.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("unmatchWithRetry"));
        assertThat(hasHelper)
                .as("MatchLifecycleListener.unmatchWithRetry must exist for block→unmatch contention recovery")
                .isTrue();
    }

    @Test
    @DisplayName("MatchReconciliationService.reconcileBlockedMatches keeps @Scheduled")
    void matchReconciliationService_stillScheduled() throws NoSuchMethodException {
        Method m = MatchReconciliationService.class.getMethod("reconcileBlockedMatches");
        assertThat(m.isAnnotationPresent(Scheduled.class))
                .as("reconcileBlockedMatches must remain @Scheduled — losing the annotation silently disables the safety net")
                .isTrue();
        assertThat(m.getAnnotation(Scheduled.class).fixedDelay())
                .as("Reconciliation interval should remain at 5 minutes (300s)")
                .isEqualTo(300_000L);
    }

    @Test
    @DisplayName("EmailVerificationFilter exposes evictEmailVerified")
    void emailVerificationFilter_hasEviction() throws NoSuchMethodException {
        Method m = EmailVerificationFilter.class.getMethod("evictEmailVerified", String.class);
        assertThat(m.getReturnType()).isEqualTo(void.class);
    }

    @Test
    @DisplayName("AuthServiceImpl depends on EmailVerificationFilter (eviction wired)")
    void authServiceImpl_dependsOnEmailVerificationFilter() {
        boolean wired = Arrays.stream(AuthServiceImpl.class.getDeclaredFields())
                .map(Field::getType)
                .anyMatch(EmailVerificationFilter.class::equals);
        assertThat(wired)
                .as("AuthServiceImpl must inject EmailVerificationFilter so verifyEmail can evict the cache")
                .isTrue();
    }

    @Test
    @DisplayName("EmailServiceImpl consolidates retry into a single helper (no per-method copy-paste)")
    void emailServiceImpl_hasSendWithRetryHelper() {
        boolean hasHelper = Arrays.stream(EmailServiceImpl.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("sendWithRetry"));
        assertThat(hasHelper)
                .as("EmailServiceImpl.sendWithRetry must exist — per-method retry loops were consolidated in Phase 3.3")
                .isTrue();
    }
}
