package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.enums.matching.MatchSource;
import com.example.dating.events.MatchCreatedEvent;
import com.example.dating.events.MatchUnmatchedEvent;
import com.example.dating.events.UserBlockedEvent;
import com.example.dating.models.matching.dao.Match;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.MatchRepository;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.repositories.UserSwipeRepository;
import com.example.dating.services.matching.MatchService;
import com.example.dating.services.matching.SwipeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies Batch B — Match Lifecycle Event Infrastructure.
 *
 * <p>Covers:
 * <ol>
 *   <li>{@code MatchCreatedEvent} published on mutual like — correct fields.</li>
 *   <li>{@code MatchCreatedEvent} includes super_like source.</li>
 *   <li>{@code MatchUnmatchedEvent} published on unmatch — correct fields.</li>
 *   <li>{@code UserBlockedEvent} published on block — correct fields.</li>
 *   <li>{@code Match.unmatchedBy} and {@code unmatchedAt} populated on unmatch.</li>
 * </ol>
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class IntegrityBatchBMatchEventTest {

    // ── Shared test listener component ────────────────────────────────────────

    /**
     * In-process event sink. Tests clear the lists before each scenario and
     * read them after. Registered as a Spring bean so Spring's event bus finds it.
     */
    @Component
    static class TestEventSink {

        final List<MatchCreatedEvent>   matchCreatedEvents   = new ArrayList<>();
        final List<MatchUnmatchedEvent> matchUnmatchedEvents = new ArrayList<>();
        final List<UserBlockedEvent>    userBlockedEvents    = new ArrayList<>();

        @EventListener
        void onMatchCreated(MatchCreatedEvent event) {
            matchCreatedEvents.add(event);
        }

        @EventListener
        void onMatchUnmatched(MatchUnmatchedEvent event) {
            matchUnmatchedEvents.add(event);
        }

        @EventListener
        void onUserBlocked(UserBlockedEvent event) {
            userBlockedEvents.add(event);
        }

        void clear() {
            matchCreatedEvents.clear();
            matchUnmatchedEvents.clear();
            userBlockedEvents.clear();
        }
    }

    // ── Injected deps ─────────────────────────────────────────────────────────

    @Autowired private SwipeService swipeService;
    @Autowired private MatchService matchService;
    @Autowired private UserJpaRepository userRepository;
    @Autowired private MatchRepository matchRepository;
    @Autowired private UserSwipeRepository swipeRepository;
    @Autowired private TestEventSink sink;

    // ── Per-test users ────────────────────────────────────────────────────────

    private UserEntity userA;
    private UserEntity userB;
    private User domainA;
    private User domainB;

    @BeforeEach
    void setUp() {
        sink.clear();

        userA = userRepository.findByEmail("batch.b.event.a@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("batch.b.event.a@test.com")
                                .name("BatchBEventUserA")
                                .build()));

        userB = userRepository.findByEmail("batch.b.event.b@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("batch.b.event.b@test.com")
                                .name("BatchBEventUserB")
                                .build()));

        domainA = User.builder().id(userA.getId()).build();
        domainB = User.builder().id(userB.getId()).build();

        // Clean swipes and matches from any prior run.
        swipeRepository.findByUserIds(userA.getId(), userB.getId()).ifPresent(s -> swipeRepository.delete(s));
        swipeRepository.findByUserIds(userB.getId(), userA.getId()).ifPresent(s -> swipeRepository.delete(s));
        matchRepository.findMatchBetweenUsers(userA.getId(), userB.getId())
                .ifPresent(m -> matchRepository.deleteById(m.getId()));
    }

    @AfterEach
    void tearDown() {
        swipeRepository.findByUserIds(userA.getId(), userB.getId()).ifPresent(s -> swipeRepository.delete(s));
        swipeRepository.findByUserIds(userB.getId(), userA.getId()).ifPresent(s -> swipeRepository.delete(s));
        matchRepository.findMatchBetweenUsers(userA.getId(), userB.getId())
                .ifPresent(m -> matchRepository.deleteById(m.getId()));
    }

    // ── Test 1: MatchCreatedEvent on mutual like ──────────────────────────────

    @Test
    @DisplayName("MatchCreatedEvent published on mutual like with correct fields")
    void mutualLike_publishesMatchCreatedEvent() {
        // A likes B
        swipeService.recordSwipe(domainA, userB.getId(), "like", null, "test");
        assertTrue(sink.matchCreatedEvents.isEmpty(),
                "No MatchCreatedEvent yet — only one side has liked");

        // B likes A back (creates match)
        swipeService.recordSwipe(domainB, userA.getId(), "like", null, "test");

        assertEquals(1, sink.matchCreatedEvents.size(),
                "Exactly one MatchCreatedEvent must fire on mutual like");

        MatchCreatedEvent evt = sink.matchCreatedEvents.get(0);

        assertNotNull(evt.matchId(),    "matchId must be populated");
        assertNotNull(evt.userAId(),    "userAId must be populated");
        assertNotNull(evt.userBId(),    "userBId must be populated");

        // userAId and userBId are stored in alphabetical order (DB convention).
        String expectedA = userA.getId().compareTo(userB.getId()) < 0 ? userA.getId() : userB.getId();
        String expectedB = userA.getId().compareTo(userB.getId()) < 0 ? userB.getId() : userA.getId();
        assertEquals(expectedA, evt.userAId(), "userAId must be the alphabetically-first user ID");
        assertEquals(expectedB, evt.userBId(), "userBId must be the alphabetically-second user ID");

        // Initiator is B — the swipe that triggered the match.
        assertEquals(userB.getId(), evt.initiatorId(),
                "initiatorId must be the user whose swipe triggered the match");

        assertEquals(MatchSource.MUTUAL_SWIPE.getValue(), evt.matchSource(),
                "matchSource must be 'mutual_swipe' for a plain like");

        // Verify the matchId refers to a real row.
        assertTrue(matchRepository.findById(evt.matchId()).isPresent(),
                "matchId in the event must correspond to an existing Match row");
    }

    // ── Test 2: MatchCreatedEvent carries super_like source ───────────────────

    @Test
    @DisplayName("MatchCreatedEvent includes super_like source when triggered by super_like")
    void superLike_matchCreatedEvent_hasCorrectSource() {
        // B likes A first so A's super_like is the trigger.
        swipeService.recordSwipe(domainB, userA.getId(), "like", null, "test");
        sink.clear();  // discard the non-match swipe event noise

        // A super_likes B — B already liked A, so this creates the match.
        swipeService.recordSwipe(domainA, userB.getId(), "super_like", null, "test");

        assertEquals(1, sink.matchCreatedEvents.size(),
                "Exactly one MatchCreatedEvent must fire");

        MatchCreatedEvent evt = sink.matchCreatedEvents.get(0);
        assertEquals(MatchSource.SUPER_LIKE.getValue(), evt.matchSource(),
                "matchSource must be 'super_like'");
        assertEquals(userA.getId(), evt.initiatorId(),
                "initiatorId must be the super_like sender");
    }

    // ── Test 3: MatchUnmatchedEvent on unmatch ────────────────────────────────

    @Test
    @DisplayName("MatchUnmatchedEvent published on unmatch with correct fields")
    void unmatch_publishesMatchUnmatchedEvent() {
        // Create a match directly (bypasses swipe logic).
        Match match = matchService.createMatch(userA, userB, 75.0, MatchSource.MUTUAL_SWIPE);
        sink.clear();

        matchService.unmatch(match.getId(), userA.getId());

        assertEquals(1, sink.matchUnmatchedEvents.size(),
                "Exactly one MatchUnmatchedEvent must fire on unmatch");

        MatchUnmatchedEvent evt = sink.matchUnmatchedEvents.get(0);
        assertEquals(match.getId(),   evt.matchId(),           "matchId must match");
        assertEquals(userA.getId(),   evt.unmatchedByUserId(), "unmatchedByUserId must be the initiator");
        assertEquals(userB.getId(),   evt.otherUserId(),       "otherUserId must be the other participant");
    }

    // ── Test 4: UserBlockedEvent on block ─────────────────────────────────────

    @Test
    @DisplayName("UserBlockedEvent published on block with correct fields")
    void block_publishesUserBlockedEvent() {
        swipeService.recordSwipe(domainA, userB.getId(), "block", null, "test");

        assertEquals(1, sink.userBlockedEvents.size(),
                "Exactly one UserBlockedEvent must fire on block");

        UserBlockedEvent evt = sink.userBlockedEvents.get(0);
        assertEquals(userA.getId(), evt.blockerId(), "blockerId must be the blocking user");
        assertEquals(userB.getId(), evt.blockedId(), "blockedId must be the blocked user");

        // Block must NOT produce a MatchCreatedEvent.
        assertTrue(sink.matchCreatedEvents.isEmpty(),
                "Block must not produce a MatchCreatedEvent");
    }

    // ── Test 5: Match.unmatchedBy and unmatchedAt populated ───────────────────

    @Test
    @DisplayName("Match.unmatchedBy and unmatchedAt populated after unmatch")
    void unmatch_populatesUnmatchedByAndUnmatchedAt() {
        Match match = matchService.createMatch(userA, userB, 80.0, MatchSource.MUTUAL_SWIPE);

        matchService.unmatch(match.getId(), userB.getId());

        Match updated = matchRepository.findById(match.getId())
                .orElseThrow(() -> new AssertionError("Match must still exist after unmatch"));

        assertNotNull(updated.getUnmatchedAt(),
                "unmatchedAt must be set after unmatch");
        assertEquals(userB.getId(), updated.getUnmatchedBy(),
                "unmatchedBy must equal the requesting user's ID");
    }
}
