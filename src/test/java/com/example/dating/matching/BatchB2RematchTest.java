package com.example.dating.matching;

import com.example.dating.events.UserBlockedEvent;
import com.example.dating.models.matching.dao.Match;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.repositories.MatchRepository;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.matching.MatchLifecycleListener;
import com.example.dating.services.matching.MatchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Batch B2 — Fix Re-Matching After Unmatch (INSERT ON CONFLICT + Status).
 *
 * <p>The root problem: {@code insertMatchIfAbsent} used {@code ON CONFLICT DO NOTHING}.
 * The unique constraint on {@code (user_a_id, user_b_id)} is status-blind — only one row
 * can ever exist per pair. When two users re-match after an unmatch, the UNMATCHED row
 * occupies the unique slot, the INSERT is silently skipped, and {@code findMatchBetweenUsers}
 * (no status filter) returns the stale UNMATCHED row. SwipeService then links the swipe to
 * it and fires {@code MatchCreatedEvent}, but the match status stays UNMATCHED —
 * permanently invisible to both users.
 *
 * <p>Fix:
 * <ul>
 *   <li>{@code insertMatchIfAbsent} now uses {@code ON CONFLICT DO UPDATE SET ... WHERE
 *       matches.status != 'active'} — reactivates UNMATCHED rows, leaves ACTIVE rows alone.</li>
 *   <li>{@code findActiveMatchBetweenUsers} added to {@link MatchRepository} — status-filtered
 *       query for contexts that must not act on historical UNMATCHED rows.</li>
 *   <li>{@link MatchLifecycleListener#onUserBlocked} now calls {@code findActiveMatchBetweenUsers}
 *       and removes the redundant manual {@code getStatus() == ACTIVE} guard.</li>
 * </ul>
 *
 * <p>Verifies:
 * <ol>
 *   <li>Structural: {@code insertMatchIfAbsent} query contains {@code DO UPDATE SET},
 *       not {@code DO NOTHING}.</li>
 *   <li>Structural: {@code WHERE matches.status != 'active'} predicate is present —
 *       ensures already-active matches are never overwritten by concurrent swipes.</li>
 *   <li>Behavioral: {@code createMatch()} when {@code insertMatchIfAbsent} returns 0
 *       (ACTIVE conflict) still fetches and returns the existing match (no exception).</li>
 *   <li>Behavioral: {@code onUserBlocked} calls {@code findActiveMatchBetweenUsers}
 *       (not the status-unaware {@code findMatchBetweenUsers}) and passes the match
 *       directly to {@code unmatch()} without a manual status check.</li>
 *   <li>Concurrent: 20 threads calling {@code createMatch()} for the same pair all
 *       receive the same match — no duplicates, no exceptions.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class BatchB2RematchTest {

    // -----------------------------------------------------------------------
    // 1. insertMatchIfAbsent uses DO UPDATE SET (not DO NOTHING)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("insertMatchIfAbsent uses ON CONFLICT DO UPDATE SET — not DO NOTHING")
    void insertMatchIfAbsent_queryUsesDOUpdate() throws Exception {
        Method method = MatchRepository.class.getMethod("insertMatchIfAbsent",
                String.class, String.class, String.class,
                Double.class, Double.class,
                String.class, LocalDateTime.class, LocalDateTime.class, LocalDateTime.class);

        Query queryAnnotation = method.getAnnotation(Query.class);
        String sql = queryAnnotation.value().toUpperCase();

        assertThat(sql)
                .as("query must use DO UPDATE SET to reactivate unmatched rows")
                .contains("DO UPDATE SET");

        assertThat(sql)
                .as("query must not fall back to DO NOTHING (that permanently blocks re-matching)")
                .doesNotContain("DO NOTHING");
    }

    // -----------------------------------------------------------------------
    // 2. WHERE clause guards already-active matches from concurrent overwrites
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("insertMatchIfAbsent DO UPDATE has WHERE matches.status != 'active' guard")
    void insertMatchIfAbsent_doUpdateHasActiveStatusGuard() throws Exception {
        Method method = MatchRepository.class.getMethod("insertMatchIfAbsent",
                String.class, String.class, String.class,
                Double.class, Double.class,
                String.class, LocalDateTime.class, LocalDateTime.class, LocalDateTime.class);

        String sql = method.getAnnotation(Query.class).value();

        assertThat(sql)
                .as("DO UPDATE must include WHERE matches.status != 'active' so that " +
                    "already-active matches are not overwritten during concurrent swipe races")
                .contains("WHERE matches.status != 'active'");

        assertThat(sql)
                .as("reactivation must clear unmatched_at")
                .containsIgnoringCase("unmatched_at");
        assertThat(sql)
                .as("reactivation must clear unmatched_by")
                .containsIgnoringCase("unmatched_by");
    }

    // -----------------------------------------------------------------------
    // 3. createMatch() returns existing match when insertMatchIfAbsent returns 0
    //    (the ACTIVE-conflict / concurrent-swipe-race path)
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("createMatch() returns the fetched match even when insertMatchIfAbsent returns 0")
    void createMatch_insertReturnsZero_returnsExistingActiveMatch() {
        MatchRepository matchRepo = mock(MatchRepository.class);
        UserJpaRepository userRepo = mock(UserJpaRepository.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        MatchService service = new MatchService(matchRepo, userRepo, ctx, publisher);

        UserEntity userA = mock(UserEntity.class);
        when(userA.getId()).thenReturn("aaa-000");
        UserEntity userB = mock(UserEntity.class);
        when(userB.getId()).thenReturn("bbb-111");

        Match existingActive = new Match();
        existingActive.setId("match-already-active");

        // 0 = row already ACTIVE (concurrent race, DO UPDATE WHERE was false)
        when(matchRepo.insertMatchIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);
        when(matchRepo.findMatchBetweenUsers("aaa-000", "bbb-111"))
                .thenReturn(Optional.of(existingActive));

        Match result = service.createMatch(userA, userB, 80.0, 70.0,
                com.example.dating.enums.matching.MatchSource.MUTUAL_SWIPE);

        assertThat(result)
                .as("createMatch must return the existing match when insert returns 0")
                .isSameAs(existingActive);
        assertThat(result.getId()).isEqualTo("match-already-active");
    }

    // -----------------------------------------------------------------------
    // 4. onUserBlocked uses findActiveMatchBetweenUsers — no manual status guard
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("onUserBlocked calls findActiveMatchBetweenUsers and passes match directly to unmatch()")
    void onUserBlocked_callsFindActiveMatchBetweenUsers() {
        MatchRepository matchRepo = mock(MatchRepository.class);
        MatchService matchService = mock(MatchService.class);
        MatchLifecycleListener listener = new MatchLifecycleListener(matchRepo, matchService);

        Match activeMatch = new Match();
        activeMatch.setId("active-match-for-blocker");

        when(matchRepo.findActiveMatchBetweenUsers("blocker-99", "blocked-99"))
                .thenReturn(Optional.of(activeMatch));

        listener.onUserBlocked(new UserBlockedEvent("blocker-99", "blocked-99"));

        // Must call the status-filtered query
        verify(matchRepo).findActiveMatchBetweenUsers("blocker-99", "blocked-99");
        // Must pass the match ID to unmatch — no manual status check needed
        verify(matchService).unmatch("active-match-for-blocker", "blocker-99");
        // Must NOT fall back to the status-unaware query
        verify(matchRepo, never()).findMatchBetweenUsers(anyString(), anyString());
    }

    // -----------------------------------------------------------------------
    // 5. Concurrent: 20 threads calling createMatch for the same pair —
    //    all receive the same match, no exceptions
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("20 concurrent createMatch calls for the same pair all return the same match")
    void createMatch_concurrent_allThreadsReturnSameMatch() throws InterruptedException {
        MatchRepository matchRepo = mock(MatchRepository.class);
        UserJpaRepository userRepo = mock(UserJpaRepository.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        MatchService service = new MatchService(matchRepo, userRepo, ctx, publisher);

        UserEntity userA = mock(UserEntity.class);
        when(userA.getId()).thenReturn("aaa-concurrent");
        UserEntity userB = mock(UserEntity.class);
        when(userB.getId()).thenReturn("bbb-concurrent");

        Match theMatch = new Match();
        theMatch.setId("the-one-true-match");

        // First call inserts (1), subsequent calls hit ACTIVE conflict (0)
        when(matchRepo.insertMatchIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1).thenReturn(0);
        when(matchRepo.findMatchBetweenUsers("aaa-concurrent", "bbb-concurrent"))
                .thenReturn(Optional.of(theMatch));

        int threads = 20;
        CountDownLatch start  = new CountDownLatch(1);
        CountDownLatch done   = new CountDownLatch(threads);
        AtomicInteger errors  = new AtomicInteger(0);
        Set<String> matchIds  = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    Match m = service.createMatch(userA, userB, 85.0, 75.0,
                            com.example.dating.enums.matching.MatchSource.MUTUAL_SWIPE);
                    matchIds.add(m.getId());
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }

        start.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS))
                .as("all %d threads must complete within 5 s", threads)
                .isTrue();

        assertThat(errors.get())
                .as("no thread should throw an exception")
                .isEqualTo(0);
        assertThat(matchIds)
                .as("all threads must return the same match ID — no duplicates")
                .hasSize(1)
                .containsExactly("the-one-true-match");
    }
}
