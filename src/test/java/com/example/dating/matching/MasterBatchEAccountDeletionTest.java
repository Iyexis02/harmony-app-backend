package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.enums.user.RegistrationStage;
import com.example.dating.exceptions.UserNotFoundException;
import com.example.dating.models.matching.dao.UserSwipe;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.repositories.UserSwipeRepository;
import com.example.dating.services.AccountDeletionService;
import com.example.dating.services.matching.SwipeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Master Batch E — Guard Account Deletion Against Concurrent FK Inserts.
 *
 * <p>Covers:
 * <ol>
 *   <li>Structural: {@code UserEntity} has a {@code deleted} column with correct annotation.</li>
 *   <li>Behavioural: {@code SwipeService.recordSwipe()} throws for a user flagged deleted.</li>
 *   <li>Filter: {@code findCandidateUsers()} excludes {@code deleted=true} users.</li>
 *   <li>Hard delete: {@code deleteAccount()} physically removes the user row (not soft-only).</li>
 *   <li>Concurrency: simultaneous swipe + deletion leaves no orphaned swipe rows.</li>
 * </ol>
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class MasterBatchEAccountDeletionTest {

    @Autowired private AccountDeletionService accountDeletionService;
    @Autowired private SwipeService swipeService;
    @Autowired private UserJpaRepository userJpaRepository;
    @Autowired private UserSwipeRepository userSwipeRepository;
    @Autowired private PlatformTransactionManager txManager;

    private TransactionTemplate txTemplate;

    @BeforeEach
    void setUp() {
        txTemplate = new TransactionTemplate(txManager);
    }

    // ── Helper ──────────────────────────────────────────────────────────────────

    private UserEntity createUser(String emailPrefix) {
        String email = emailPrefix + "@master-e.test";
        return userJpaRepository.findByEmail(email)
                .orElseGet(() -> userJpaRepository.save(
                        UserEntity.builder()
                                .email(email)
                                .name("MasterE_" + emailPrefix)
                                .build()));
    }

    private UserEntity createFinishedUser(String emailPrefix) {
        String email = emailPrefix + "@master-e.test";
        return userJpaRepository.findByEmail(email)
                .orElseGet(() -> userJpaRepository.save(
                        UserEntity.builder()
                                .email(email)
                                .name("MasterE_" + emailPrefix)
                                .registrationStage(RegistrationStage.FINISHED)
                                .build()));
    }

    // ── Test 1: Structural — deleted field and @Column annotation ───────────────

    @Test
    @DisplayName("UserEntity has a 'deleted' boolean field with @Column(name='deleted', nullable=false)")
    void userEntity_hasDeletedField_withCorrectAnnotation() throws Exception {
        Field field = UserEntity.class.getDeclaredField("deleted");
        field.setAccessible(true);

        assertEquals(boolean.class, field.getType(),
                "deleted must be a primitive boolean");

        jakarta.persistence.Column col = field.getAnnotation(jakarta.persistence.Column.class);
        assertNotNull(col, "deleted must have @Column");
        assertEquals("deleted", col.name(), "@Column name must be 'deleted'");
        assertFalse(col.nullable(), "@Column nullable must be false");
    }

    // ── Test 2: SwipeService rejects swipes targeting a deleted user ────────────

    @Test
    @DisplayName("recordSwipe throws UserNotFoundException when swiped user is flagged deleted")
    void recordSwipe_throwsUserNotFound_forDeletedUser() {
        // Create target (deleted) and swiper in their own transactions
        String targetId = txTemplate.execute(s -> createUser("e2-target-" + System.nanoTime()).getId());
        String swiperId = txTemplate.execute(s -> createUser("e2-swiper-" + System.nanoTime()).getId());

        // Mark target as deleted (simulates the state mid-deletion, after saveAndFlush)
        txTemplate.executeWithoutResult(s -> {
            UserEntity target = userJpaRepository.findById(targetId).orElseThrow();
            target.setDeleted(true);
            userJpaRepository.save(target);
        });

        User swiper = User.builder().id(swiperId).build();

        assertThrows(UserNotFoundException.class,
                () -> swipeService.recordSwipe(swiper, targetId, "like", 0.0, "test"),
                "recordSwipe must throw UserNotFoundException for a deleted user");

        // Cleanup
        txTemplate.executeWithoutResult(s -> {
            userSwipeRepository.deleteAllInvolvingUser(targetId);
            userSwipeRepository.deleteAllInvolvingUser(swiperId);
            userJpaRepository.deleteById(targetId);
            userJpaRepository.deleteById(swiperId);
        });
    }

    // ── Test 3: findCandidateUsers excludes deleted users ──────────────────────

    @Test
    @DisplayName("findCandidateUsers excludes users with deleted=true from recommendation pool")
    void findCandidateUsers_excludesDeletedUsers() {
        // Create two FINISHED users; one will be soft-deleted
        String activeId = txTemplate.execute(s -> createFinishedUser("e3-active-" + System.nanoTime()).getId());
        String deletedId = txTemplate.execute(s -> createFinishedUser("e3-deleted-" + System.nanoTime()).getId());

        // Soft-delete one user
        txTemplate.executeWithoutResult(s -> {
            UserEntity target = userJpaRepository.findById(deletedId).orElseThrow();
            target.setDeleted(true);
            userJpaRepository.save(target);
        });

        // Query candidates from the perspective of a third user (activeId as requester)
        // excludedIds must not be empty — use a dummy ID that matches no user
        List<String> excluded = List.of("__dummy__");
        var page = userJpaRepository.findCandidateUsers(
                activeId,
                RegistrationStage.FINISHED,
                excluded,
                null, null,
                PageRequest.of(0, 500)
        );

        List<String> candidateIds = page.getContent().stream()
                .map(UserEntity::getId)
                .toList();

        assertFalse(candidateIds.contains(deletedId),
                "findCandidateUsers must not return users flagged as deleted");

        // Cleanup
        txTemplate.executeWithoutResult(s -> {
            userJpaRepository.deleteById(activeId);
            userJpaRepository.deleteById(deletedId);
        });
    }

    // ── Test 4: deleteAccount performs a hard delete, not soft-only ─────────────

    @Test
    @DisplayName("deleteAccount physically removes the user row after deletion (not soft-only)")
    void deleteAccount_removesUserRow_notSoftOnly() {
        String userId = txTemplate.execute(s -> createUser("e4-" + System.nanoTime()).getId());

        accountDeletionService.deleteAccount(userId, null);

        assertTrue(userJpaRepository.findById(userId).isEmpty(),
                "User row must be physically deleted after deleteAccount()");
    }

    // ── Test 5: Concurrent swipe + deletion — no orphaned rows ──────────────────

    @Test
    @DisplayName("Concurrent swipe during account deletion leaves no orphaned swipe rows")
    void concurrent_swipeDuringDeletion_noOrphanedSwipes() throws Exception {
        // Create target (to be deleted) and swiper in separate transactions
        String targetId = txTemplate.execute(s -> createUser("e5-target-" + System.nanoTime()).getId());
        String swiperId = txTemplate.execute(s -> createUser("e5-swiper-" + System.nanoTime()).getId());

        // Insert a pre-existing swipe so deleteAllInvolvingUser has something to do
        // (gives the deletion TX more work, widening the race window)
        String bystander = txTemplate.execute(s -> {
            UserEntity b = createUser("e5-bystander-" + System.nanoTime());
            UserEntity target = userJpaRepository.findById(targetId).orElseThrow();
            userSwipeRepository.save(UserSwipe.builder()
                    .swiperUser(b).swipedUser(target)
                    .action("pass").swipedAt(LocalDateTime.now())
                    .matchScoreAtSwipe(0.0).resultedInMatch(false)
                    .build());
            return b.getId();
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicReference<Throwable> swipeError = new AtomicReference<>();

        // Thread 1: delete the target account
        Future<?> deletionFuture = executor.submit(() -> {
            try {
                startGate.await(5, TimeUnit.SECONDS);
                accountDeletionService.deleteAccount(targetId, null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // Deletion may throw if the swipe TX committed a new FK row first;
            // that failure is acceptable — the important invariant is no orphans.
        });

        // Thread 2: attempt a swipe on the target simultaneously
        User swiper = User.builder().id(swiperId).build();
        Future<?> swipeFuture = executor.submit(() -> {
            try {
                startGate.await(5, TimeUnit.SECONDS);
                swipeService.recordSwipe(swiper, targetId, "like", 0.0, "test");
            } catch (Exception e) {
                swipeError.set(e);
                // Expected: UserNotFoundException (deleted flag) or FK violation
            }
        });

        startGate.countDown(); // release both threads simultaneously

        deletionFuture.get(15, TimeUnit.SECONDS);
        swipeFuture.get(15, TimeUnit.SECONDS);
        executor.shutdown();

        // ── Invariant: no orphaned swipe rows referencing targetId ────────────
        // Regardless of which thread "won", after both complete:
        //   - If deletion won: swipe TX was rolled back by FK violation → no row
        //   - If swipe won before deleteAllInvolvingUser: deletion cleaned it up
        //   - If soft-delete caught the swipe: UserNotFoundException was thrown
        List<UserSwipe> orphans = userSwipeRepository
                .findLikesByUserId(swiperId, PageRequest.of(0, 100))
                .stream()
                .filter(sw -> targetId.equals(sw.getSwipedUser().getId()))
                .toList();

        assertTrue(orphans.isEmpty(),
                "No swipe rows referencing the deleted user must remain: found " + orphans.size());

        // ── Cleanup ───────────────────────────────────────────────────────────
        txTemplate.executeWithoutResult(s -> {
            userSwipeRepository.deleteAllInvolvingUser(swiperId);
            userSwipeRepository.deleteAllInvolvingUser(bystander);
            if (userJpaRepository.findById(targetId).isPresent()) {
                userSwipeRepository.deleteAllInvolvingUser(targetId);
                userJpaRepository.deleteById(targetId);
            }
            userJpaRepository.deleteById(swiperId);
            userJpaRepository.deleteById(bystander);
        });
    }
}
