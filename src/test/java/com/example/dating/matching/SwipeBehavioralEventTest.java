package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.events.SwipeRecordedEvent;
import com.example.dating.models.matching.dao.UserBehavioralProfile;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.repositories.UserBehavioralProfileRepository;
import com.example.dating.repositories.UserJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies Batch D — Async Behavioral Update After Commit.
 *
 * <p>The core invariant: {@code BehavioralProfileService.updateAfterSwipe()} must
 * only run after the swipe transaction has successfully committed.  If the swipe
 * transaction rolls back, the behavioral profile must remain unchanged.
 *
 * <p>We exercise the event mechanism directly (bypassing {@code SwipeService}) so
 * the test is isolated from swipe-layer concerns (duplicate checks, match creation,
 * etc.) and focuses purely on the transaction-lifecycle guarantee.
 *
 * <h3>Test 1 — Rollback scenario</h3>
 * Publishes a {@link SwipeRecordedEvent} inside a transaction that is then marked
 * rollback-only.  Because {@code @TransactionalEventListener(phase = AFTER_COMMIT)}
 * discards events when the transaction does not commit, the behavioral profile must
 * not be updated.
 *
 * <h3>Test 2 — Commit scenario</h3>
 * Publishes the same event inside a transaction that commits normally.  The
 * {@code @Async @TransactionalEventListener} method fires in a background thread
 * after the commit.  After waiting for the async work to complete, the behavioral
 * profile must show exactly one like recorded.
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class SwipeBehavioralEventTest {

    /** Maximum time (ms) to wait for the async listener to finish in the commit test. */
    private static final long ASYNC_TIMEOUT_MS = 3_000;

    /** How often (ms) to poll the DB while waiting for the async listener. */
    private static final long POLL_INTERVAL_MS = 100;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private UserBehavioralProfileRepository behavioralProfileRepository;

    @Autowired
    private UserJpaRepository userRepository;

    private UserEntity swiper;
    private UserEntity swiped;

    @BeforeEach
    void setUp() {
        swiper = userRepository.findByEmail("behavioral.event.swiper@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("behavioral.event.swiper@test.com")
                                .name("BehavioralEventSwiper")
                                .build()));

        swiped = userRepository.findByEmail("behavioral.event.swiped@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("behavioral.event.swiped@test.com")
                                .name("BehavioralEventSwiped")
                                .build()));
    }

    @AfterEach
    void tearDown() {
        behavioralProfileRepository.findByUserId(swiper.getId())
                .ifPresent(p -> behavioralProfileRepository.deleteById(p.getId()));
    }

    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Behavioral profile NOT updated when swipe transaction rolls back")
    void behavioralProfile_notUpdated_whenTransactionRollsBack() throws InterruptedException {
        // Publish the event inside a transaction that is immediately rolled back.
        // setRollbackOnly() prevents commit without throwing from the template itself.
        transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publishEvent(
                    new SwipeRecordedEvent(swiper.getId(), swiped.getId(), "like", 75.0));
            status.setRollbackOnly();
        });

        // Wait longer than the async listener would take to run, to make sure it truly
        // does not fire.  If the fix were broken, the listener would update the profile
        // in this window.
        Thread.sleep(1_500);

        Optional<UserBehavioralProfile> profile =
                behavioralProfileRepository.findByUserId(swiper.getId());

        assertTrue(
                profile.isEmpty() || profile.get().getTotalLikes() == 0,
                "Behavioral profile must NOT be updated when the swipe transaction rolled back. " +
                "totalLikes = " + profile.map(UserBehavioralProfile::getTotalLikes).orElse(0));
    }

    @Test
    @DisplayName("Behavioral profile IS updated after swipe transaction commits")
    void behavioralProfile_updated_afterTransactionCommits() throws InterruptedException {
        // Publish the event inside a transaction that commits normally.
        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(
                        new SwipeRecordedEvent(swiper.getId(), swiped.getId(), "like", 75.0)));

        // Poll until the async listener writes the profile row, or we time out.
        long deadline = System.currentTimeMillis() + ASYNC_TIMEOUT_MS;
        Optional<UserBehavioralProfile> profile = Optional.empty();
        while (System.currentTimeMillis() < deadline) {
            profile = behavioralProfileRepository.findByUserId(swiper.getId());
            if (profile.isPresent() && profile.get().getTotalLikes() > 0) {
                break;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }

        assertTrue(
                profile.isPresent(),
                "Behavioral profile must exist after the swipe transaction committed");
        assertEquals(
                1,
                profile.get().getTotalLikes(),
                "totalLikes must be 1 after one like event committed");
    }
}
