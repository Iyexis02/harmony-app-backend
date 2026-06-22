package com.example.dating.matching;

import com.example.dating.models.matching.dao.Match;
import com.example.dating.models.matching.dao.UserSwipe;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.repositories.MatchRepository;
import com.example.dating.repositories.UserSwipeRepository;
import com.example.dating.services.matching.MatchReconciliationService;
import com.example.dating.services.matching.MatchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Batch H — Reconciliation for Failed Block-Unmatch Operations.
 *
 * <p>Covers:
 * <ol>
 *   <li>Structural: {@code reconcileBlockedMatches()} carries {@code @Scheduled(fixedDelay=300_000)}.</li>
 *   <li>Structural: {@code UserSwipeRepository} exposes {@code findBlockSwipesWithActiveMatches(Pageable)}.</li>
 *   <li>Behaviour: stale block-swipes are resolved via {@code matchService.unmatch()}.</li>
 *   <li>No-op: when no stale swipes exist the service calls {@code unmatch()} zero times.</li>
 *   <li>Concurrency: 20 simultaneous reconciliation invocations all complete without throwing.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class BatchHReconciliationTest {

    // -----------------------------------------------------------------------
    // 1. @Scheduled(fixedDelay = 300_000) is on reconcileBlockedMatches()
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("reconcileBlockedMatches() is annotated @Scheduled(fixedDelay = 300_000)")
    void reconcileBlockedMatches_hasScheduledAnnotation() throws NoSuchMethodException {
        Method method = MatchReconciliationService.class
                .getMethod("reconcileBlockedMatches");

        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertThat(scheduled)
                .as("reconcileBlockedMatches must be annotated with @Scheduled")
                .isNotNull();
        assertThat(scheduled.fixedDelay())
                .as("fixedDelay must be 300_000 ms (5 minutes)")
                .isEqualTo(300_000L);
    }

    // -----------------------------------------------------------------------
    // 2. UserSwipeRepository has findBlockSwipesWithActiveMatches(Pageable)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("UserSwipeRepository exposes findBlockSwipesWithActiveMatches(Pageable)")
    void userSwipeRepository_hasFindBlockSwipesWithActiveMatchesMethod() throws NoSuchMethodException {
        Method method = UserSwipeRepository.class
                .getMethod("findBlockSwipesWithActiveMatches", Pageable.class);

        assertThat(method.getReturnType())
                .as("return type must be List")
                .isEqualTo(List.class);
    }

    // -----------------------------------------------------------------------
    // 3. Stale block-swipes are resolved — unmatch() called for each stale pair
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("reconcileBlockedMatches resolves each stale block-swipe by calling unmatch()")
    void reconcile_callsUnmatch_forEachStalePair() {
        UserSwipeRepository swipeRepo = mock(UserSwipeRepository.class);
        MatchRepository matchRepo      = mock(MatchRepository.class);
        MatchService matchService      = mock(MatchService.class);

        // Two stale block swipes
        UserEntity swiperA = userEntity("swiperA");
        UserEntity swipedB = userEntity("swipedB");
        UserEntity swiperC = userEntity("swiperC");
        UserEntity swipedD = userEntity("swipedD");

        UserSwipe swipe1 = blockSwipe(swiperA, swipedB);
        UserSwipe swipe2 = blockSwipe(swiperC, swipedD);

        Match match1 = matchWithId("match-1");
        Match match2 = matchWithId("match-2");

        when(swipeRepo.findBlockSwipesWithActiveMatches(any(Pageable.class)))
                .thenReturn(List.of(swipe1, swipe2));
        when(matchRepo.findActiveMatchBetweenUsers("swiperA", "swipedB"))
                .thenReturn(Optional.of(match1));
        when(matchRepo.findActiveMatchBetweenUsers("swiperC", "swipedD"))
                .thenReturn(Optional.of(match2));

        new MatchReconciliationService(swipeRepo, matchRepo, matchService)
                .reconcileBlockedMatches();

        verify(matchService).unmatch("match-1", "swiperA");
        verify(matchService).unmatch("match-2", "swiperC");
        verifyNoMoreInteractions(matchService);
    }

    // -----------------------------------------------------------------------
    // 4. No-op when no stale swipes — unmatch() never called
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("reconcileBlockedMatches is a no-op when no stale block-swipes exist")
    void reconcile_isNoOp_whenNoStalePairs() {
        UserSwipeRepository swipeRepo = mock(UserSwipeRepository.class);
        MatchRepository matchRepo      = mock(MatchRepository.class);
        MatchService matchService      = mock(MatchService.class);

        when(swipeRepo.findBlockSwipesWithActiveMatches(any(Pageable.class)))
                .thenReturn(List.of());

        new MatchReconciliationService(swipeRepo, matchRepo, matchService)
                .reconcileBlockedMatches();

        verifyNoInteractions(matchService);
        verifyNoInteractions(matchRepo);
    }

    // -----------------------------------------------------------------------
    // 5. Concurrent: 20 simultaneous reconciliation calls all complete safely
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("20 concurrent reconcileBlockedMatches() invocations all complete without throwing")
    void reconcile_concurrent_allCompleteCleanly() throws InterruptedException {
        UserSwipeRepository swipeRepo = mock(UserSwipeRepository.class);
        MatchRepository matchRepo      = mock(MatchRepository.class);
        MatchService matchService      = mock(MatchService.class);

        // Each call sees one stale swipe with an active match — exercises the resolve path
        UserEntity swiperA = userEntity("concurrent-swiper");
        UserEntity swipedB = userEntity("concurrent-swiped");
        UserSwipe staleSwipe = blockSwipe(swiperA, swipedB);
        Match staleMatch = matchWithId("concurrent-match");

        when(swipeRepo.findBlockSwipesWithActiveMatches(any(Pageable.class)))
                .thenReturn(List.of(staleSwipe));
        when(matchRepo.findActiveMatchBetweenUsers("concurrent-swiper", "concurrent-swiped"))
                .thenReturn(Optional.of(staleMatch));

        MatchReconciliationService service =
                new MatchReconciliationService(swipeRepo, matchRepo, matchService);

        int threads = 20;
        CountDownLatch start    = new CountDownLatch(1);
        CountDownLatch done     = new CountDownLatch(threads);
        AtomicInteger  errors   = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    service.reconcileBlockedMatches();
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS))
                .as("All %d threads must complete within 10 s", threads)
                .isTrue();
        pool.shutdown();

        assertThat(errors.get())
                .as("No concurrent invocation must throw an uncaught exception")
                .isZero();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static UserEntity userEntity(String id) {
        UserEntity e = new UserEntity();
        // Set id via reflection — avoids needing a public setter or builder on UserEntity
        try {
            java.lang.reflect.Field f = UserEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(e, id);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return e;
    }

    private static UserSwipe blockSwipe(UserEntity swiper, UserEntity swiped) {
        return UserSwipe.builder()
                .swiperUser(swiper)
                .swipedUser(swiped)
                .action("block")
                .swipedAt(java.time.LocalDateTime.now())
                .matchScoreAtSwipe(0.0)
                .resultedInMatch(false)
                .build();
    }

    private static Match matchWithId(String id) {
        Match m = new Match();
        try {
            java.lang.reflect.Field f = Match.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(m, id);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return m;
    }
}
