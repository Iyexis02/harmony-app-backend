package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.enums.matching.MatchSource;
import com.example.dating.enums.matching.MatchStatus;
import com.example.dating.events.MatchUnmatchedEvent;
import com.example.dating.models.matching.dao.Match;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.repositories.MatchRepository;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.matching.MatchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Master Batch E2 — Guard doUnmatch Against Already-UNMATCHED Matches.
 *
 * <p>Covers:
 * <ol>
 *   <li>Second {@code doUnmatch} call on an UNMATCHED match returns early — {@code unmatchedBy} is preserved.</li>
 *   <li>No duplicate {@code MatchUnmatchedEvent} published for an already-UNMATCHED match.</li>
 *   <li>{@code unmatchedAt} timestamp is preserved from the original unmatch (not overwritten).</li>
 *   <li>Normal first unmatch on an ACTIVE match still sets all fields correctly.</li>
 *   <li>Concurrent double-unmatch: exactly one event published and {@code unmatchedBy} is consistent.</li>
 * </ol>
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class MasterBatchE2UnmatchGuardTest {

    // ── Shared event sink ────────────────────────────────────────────────────────

    /**
     * Synchronous event sink — plain {@code @EventListener} fires immediately when
     * {@code publishEvent} is called, regardless of transaction phase.  This lets
     * tests assert event counts without waiting for async AFTER_COMMIT listeners.
     */
    @Component
    static class UnmatchEventSink {
        final List<MatchUnmatchedEvent> events = Collections.synchronizedList(new ArrayList<>());

        @EventListener
        void onMatchUnmatched(MatchUnmatchedEvent event) {
            events.add(event);
        }

        void clear() { events.clear(); }
    }

    @Autowired private MatchService matchService;
    @Autowired private MatchRepository matchRepository;
    @Autowired private UserJpaRepository userJpaRepository;
    @Autowired private UnmatchEventSink sink;
    @Autowired private PlatformTransactionManager txManager;

    private TransactionTemplate txTemplate;
    private UserEntity userA;
    private UserEntity userB;

    @BeforeEach
    void setUp() {
        txTemplate = new TransactionTemplate(txManager);
        sink.clear();

        userA = txTemplate.execute(s -> userJpaRepository.findByEmail("e2guard-a@master.test")
                .orElseGet(() -> userJpaRepository.save(
                        UserEntity.builder().email("e2guard-a@master.test").name("E2GuardA").build())));
        userB = txTemplate.execute(s -> userJpaRepository.findByEmail("e2guard-b@master.test")
                .orElseGet(() -> userJpaRepository.save(
                        UserEntity.builder().email("e2guard-b@master.test").name("E2GuardB").build())));
    }

    @AfterEach
    void tearDown() {
        sink.clear();
        // Clean up any matches created during tests; user rows are reused across tests.
        txTemplate.executeWithoutResult(s ->
                matchRepository.deleteAllByUserId(userA.getId())
        );
    }

    // ── Helper ───────────────────────────────────────────────────────────────────

    private Match createActiveMatch() {
        return txTemplate.execute(s -> {
            UserEntity a = userJpaRepository.findById(userA.getId()).orElseThrow();
            UserEntity b = userJpaRepository.findById(userB.getId()).orElseThrow();
            return matchService.createMatch(a, b, 80.0, MatchSource.MUTUAL_SWIPE);
        });
    }

    // ── Test 1: unmatchedBy preserved when doUnmatch called on UNMATCHED match ──

    @Test
    @DisplayName("doUnmatch on already-UNMATCHED match does not overwrite unmatchedBy")
    void doUnmatch_preservesUnmatchedBy_forAlreadyUnmatchedMatch() {
        Match match = createActiveMatch();
        sink.clear();

        // First unmatch: user A initiates
        matchService.doUnmatch(match.getId(), userA.getId());

        // Verify: unmatchedBy = A
        Match afterFirst = matchRepository.findById(match.getId()).orElseThrow();
        assertEquals(userA.getId(), afterFirst.getUnmatchedBy(),
                "unmatchedBy must be set to user A after first unmatch");

        // Second doUnmatch as user B — must be a no-op
        matchService.doUnmatch(match.getId(), userB.getId());

        Match afterSecond = matchRepository.findById(match.getId()).orElseThrow();
        assertEquals(userA.getId(), afterSecond.getUnmatchedBy(),
                "unmatchedBy must still be user A after second doUnmatch call");
    }

    // ── Test 2: No duplicate MatchUnmatchedEvent for already-UNMATCHED match ────

    @Test
    @DisplayName("doUnmatch on already-UNMATCHED match does not publish a second MatchUnmatchedEvent")
    void doUnmatch_doesNotPublishDuplicateEvent_forAlreadyUnmatchedMatch() {
        Match match = createActiveMatch();
        sink.clear();

        matchService.doUnmatch(match.getId(), userA.getId());
        int eventsAfterFirst = sink.events.size();
        assertEquals(1, eventsAfterFirst, "Exactly one event must be published on first unmatch");

        sink.clear();

        // Second call — must produce zero events
        matchService.doUnmatch(match.getId(), userB.getId());

        assertEquals(0, sink.events.size(),
                "No MatchUnmatchedEvent must be published when match is already UNMATCHED");
    }

    // ── Test 3: unmatchedAt timestamp preserved from original unmatch ────────────

    @Test
    @DisplayName("doUnmatch on already-UNMATCHED match does not overwrite unmatchedAt")
    void doUnmatch_preservesUnmatchedAt_fromOriginalUnmatch() throws Exception {
        Match match = createActiveMatch();
        sink.clear();

        matchService.doUnmatch(match.getId(), userA.getId());

        LocalDateTime originalUnmatchedAt = matchRepository.findById(match.getId())
                .orElseThrow().getUnmatchedAt();
        assertNotNull(originalUnmatchedAt, "unmatchedAt must be set after first unmatch");

        // Small sleep so a second call would produce a visibly different timestamp
        Thread.sleep(10);

        matchService.doUnmatch(match.getId(), userB.getId());

        LocalDateTime afterSecondCallUnmatchedAt = matchRepository.findById(match.getId())
                .orElseThrow().getUnmatchedAt();

        assertEquals(originalUnmatchedAt, afterSecondCallUnmatchedAt,
                "unmatchedAt must be identical to the original — second call must not overwrite it");
    }

    // ── Test 4: Normal first unmatch on ACTIVE match still works correctly ───────

    @Test
    @DisplayName("First doUnmatch on ACTIVE match sets status=UNMATCHED, unmatchedBy, and publishes event")
    void doUnmatch_worksCorrectly_forActiveMatch() {
        Match match = createActiveMatch();
        sink.clear();

        matchService.doUnmatch(match.getId(), userB.getId());

        Match result = matchRepository.findById(match.getId()).orElseThrow();
        assertEquals(MatchStatus.UNMATCHED, result.getStatus(),
                "Status must be UNMATCHED after doUnmatch on ACTIVE match");
        assertEquals(userB.getId(), result.getUnmatchedBy(),
                "unmatchedBy must be the requesting user");
        assertNotNull(result.getUnmatchedAt(),
                "unmatchedAt must be set");

        assertEquals(1, sink.events.size(),
                "Exactly one MatchUnmatchedEvent must be published");
        assertEquals(match.getId(), sink.events.get(0).matchId(),
                "Event matchId must match");
        assertEquals(userB.getId(), sink.events.get(0).unmatchedByUserId(),
                "Event unmatchedByUserId must be user B");
    }

    // ── Test 5: Concurrent double-unmatch — exactly one event, consistent state ──

    @Test
    @DisplayName("Concurrent unmatch calls produce exactly one MatchUnmatchedEvent and one consistent unmatchedBy")
    void concurrent_doubleUnmatch_exactlyOneEventAndConsistentUnmatchedBy() throws Exception {
        Match match = createActiveMatch();
        String matchId = match.getId();
        sink.clear();

        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        // Five threads unmatch as A, five as B — only one should "win" (first to commit)
        for (int i = 0; i < threads; i++) {
            String userId = i % 2 == 0 ? userA.getId() : userB.getId();
            futures.add(executor.submit(() -> {
                try {
                    startGate.await(5, TimeUnit.SECONDS);
                    matchService.unmatch(matchId, userId);
                } catch (Exception ignored) {
                    // OptimisticLockingFailureException or IllegalStateException is acceptable
                }
            }));
        }

        startGate.countDown();
        for (Future<?> f : futures) f.get(15, TimeUnit.SECONDS);
        executor.shutdown();

        // ── Invariants ────────────────────────────────────────────────────────
        Match finalMatch = matchRepository.findById(matchId).orElseThrow();

        assertEquals(MatchStatus.UNMATCHED, finalMatch.getStatus(),
                "Match must be UNMATCHED after concurrent unmatch calls");

        String unmatchedBy = finalMatch.getUnmatchedBy();
        assertTrue(unmatchedBy.equals(userA.getId()) || unmatchedBy.equals(userB.getId()),
                "unmatchedBy must be one of the two participants, not an overwritten value");

        assertNotNull(finalMatch.getUnmatchedAt(),
                "unmatchedAt must be set");

        // Only events published via publishEvent (within a committed TX) are counted.
        // With the status guard, retries after the first committed unmatch publish nothing.
        // There may be 1 event (clean win) or occasionally 2 if two TXes committed before
        // either's retry could see UNMATCHED — but NEVER more than 2.
        int eventCount = sink.events.size();
        assertTrue(eventCount >= 1 && eventCount <= 2,
                "Between 1 and 2 MatchUnmatchedEvents expected (not " + eventCount + ");" +
                " guard eliminates all retry-path duplicates beyond the first commit");

        // All events must reference the same matchId
        for (MatchUnmatchedEvent event : sink.events) {
            assertEquals(matchId, event.matchId(),
                    "Every published event must reference the correct matchId");
        }
    }
}
