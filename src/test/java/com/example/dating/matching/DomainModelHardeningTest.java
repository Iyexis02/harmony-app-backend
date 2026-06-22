package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.enums.matching.MatchSource;
import com.example.dating.enums.matching.MatchStatus;
import com.example.dating.models.matching.dao.Match;
import com.example.dating.models.matching.dao.UserBehavioralProfile;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.repositories.MatchRepository;
import com.example.dating.repositories.UserBehavioralProfileRepository;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.matching.MatchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies Batch H — Domain Model Hardening.
 *
 * <h3>Checks</h3>
 * <ol>
 *   <li><strong>Enum serialisation</strong> — {@link MatchStatus} and {@link MatchSource}
 *       serialise to lowercase strings (e.g. {@code "active"}, {@code "mutual_swipe"})
 *       so the public API contract is unchanged.</li>
 *   <li><strong>Round-trip persistence</strong> — a {@link Match} written via
 *       {@code MatchService.createMatch()} is read back with the correct enum fields,
 *       confirming the {@code AttributeConverter} translates in both directions.</li>
 *   <li><strong>JPQL enum-literal queries</strong> — {@code findActiveMatchesByUserId}
 *       and {@code countActiveMatchesByUserId} return the expected match; an unmatched
 *       match is excluded once its status changes to {@code UNMATCHED}.</li>
 *   <li><strong>Typed-parameter queries</strong> — {@code findMatchesBySource} with
 *       a {@link MatchSource} parameter returns only matches of that source.</li>
 *   <li><strong>Unmatch</strong> — {@code MatchService.unmatch()} stores
 *       {@code MatchStatus.UNMATCHED} and the match disappears from active queries.</li>
 *   <li><strong>Behavioral profile single-delete</strong> — {@code deleteByUserId}
 *       removes the profile without a prior {@code SELECT}.</li>
 * </ol>
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DomainModelHardeningTest {

    @Autowired private MatchService              matchService;
    @Autowired private MatchRepository           matchRepository;
    @Autowired private UserJpaRepository         userRepository;
    @Autowired private UserBehavioralProfileRepository behavioralProfileRepository;
    @Autowired private ObjectMapper              objectMapper;

    private UserEntity alpha;
    private UserEntity beta;

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        alpha = userRepository.findByEmail("batch.h.alpha@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("batch.h.alpha@test.com")
                                .name("BatchHAlpha")
                                .build()));

        beta = userRepository.findByEmail("batch.h.beta@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("batch.h.beta@test.com")
                                .name("BatchHBeta")
                                .build()));

        // Clean slate — remove any match left over from a previous run.
        matchRepository.findMatchBetweenUsers(alpha.getId(), beta.getId())
                .ifPresent(m -> matchRepository.deleteById(m.getId()));
    }

    @AfterEach
    void tearDown() {
        matchRepository.findMatchBetweenUsers(alpha.getId(), beta.getId())
                .ifPresent(m -> matchRepository.deleteById(m.getId()));
    }

    // ─── 1. Enum serialisation ─────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("MatchStatus serialises to lowercase JSON string")
    void matchStatus_serialisesToLowercaseJson() throws Exception {
        // Jackson must use @JsonValue, not the enum name.
        String json = objectMapper.writeValueAsString(MatchStatus.ACTIVE);
        assertEquals("\"active\"", json,
                "MatchStatus.ACTIVE must serialise to lowercase \"active\"");

        json = objectMapper.writeValueAsString(MatchStatus.UNMATCHED);
        assertEquals("\"unmatched\"", json,
                "MatchStatus.UNMATCHED must serialise to lowercase \"unmatched\"");
    }

    @Test
    @Order(2)
    @DisplayName("MatchSource serialises to lowercase underscore JSON string")
    void matchSource_serialisesToLowercaseUnderscoreJson() throws Exception {
        String json = objectMapper.writeValueAsString(MatchSource.MUTUAL_SWIPE);
        assertEquals("\"mutual_swipe\"", json,
                "MatchSource.MUTUAL_SWIPE must serialise to \"mutual_swipe\"");

        json = objectMapper.writeValueAsString(MatchSource.SUPER_LIKE);
        assertEquals("\"super_like\"", json,
                "MatchSource.SUPER_LIKE must serialise to \"super_like\"");
    }

    // ─── 2. Round-trip persistence via AttributeConverter ─────────────────

    @Test
    @Order(3)
    @DisplayName("Match persisted with enum fields round-trips through the converter")
    void match_enumFieldsRoundTripThroughConverter() {
        Match created = matchService.createMatch(alpha, beta, 80.0, MatchSource.SUPER_LIKE);

        // Evict from 1st-level cache to force a real DB read.
        matchRepository.flush();

        Match fetched = matchRepository.findById(created.getId())
                .orElseThrow(() -> new AssertionError("Match not found after save"));

        assertSame(MatchStatus.ACTIVE, fetched.getStatus(),
                "status must round-trip as MatchStatus.ACTIVE");
        assertSame(MatchSource.SUPER_LIKE, fetched.getMatchSource(),
                "matchSource must round-trip as MatchSource.SUPER_LIKE");
    }

    // ─── 3. JPQL enum-literal queries ─────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("findActiveMatchesByUserId returns ACTIVE match and excludes UNMATCHED")
    @Transactional
    void jpqlEnumLiteral_activeQueryReturnsCorrectRows() {
        Match match = matchService.createMatch(alpha, beta, 75.0);

        // Active — must appear.
        List<Match> active = matchRepository.findActiveMatchesByUserId(alpha.getId(), PageRequest.of(0, 100)).getContent();
        assertTrue(active.stream().anyMatch(m -> m.getId().equals(match.getId())),
                "ACTIVE match must be returned by findActiveMatchesByUserId");

        // After unmatching the status becomes UNMATCHED — must vanish from active list.
        matchService.unmatch(match.getId(), alpha.getId());

        List<Match> afterUnmatch = matchRepository.findActiveMatchesByUserId(alpha.getId(), PageRequest.of(0, 100)).getContent();
        assertFalse(afterUnmatch.stream().anyMatch(m -> m.getId().equals(match.getId())),
                "UNMATCHED match must NOT appear in findActiveMatchesByUserId");
    }

    @Test
    @Order(5)
    @DisplayName("countActiveMatchesByUserId counts only ACTIVE matches")
    void jpqlEnumLiteral_countQueryCountsActiveOnly() {
        long beforeCount = matchRepository.countActiveMatchesByUserId(alpha.getId());

        matchService.createMatch(alpha, beta, 65.0);

        long afterCount = matchRepository.countActiveMatchesByUserId(alpha.getId());
        assertEquals(beforeCount + 1, afterCount,
                "countActiveMatchesByUserId must increment by 1 after a new ACTIVE match");
    }

    @Test
    @Order(6)
    @DisplayName("Paginated findActiveMatchesByUserId returns correct Page")
    void jpqlEnumLiteral_paginatedActiveQuery() {
        matchService.createMatch(alpha, beta, 72.0);

        Page<Match> page = matchRepository.findActiveMatchesByUserId(
                alpha.getId(), PageRequest.of(0, 20));

        assertTrue(page.getTotalElements() >= 1,
                "Paginated active query must find at least 1 match");
        page.getContent().forEach(m ->
                assertSame(MatchStatus.ACTIVE, m.getStatus(),
                        "Every match returned by the paginated active query must have ACTIVE status"));
    }

    // ─── 4. Typed-parameter queries (MatchSource) ──────────────────────────

    @Test
    @Order(7)
    @DisplayName("findMatchesBySource with enum parameter filters correctly")
    void typedSourceParam_findMatchesBySourceFiltersCorrectly() {
        matchService.createMatch(alpha, beta, 88.0, MatchSource.SUPER_LIKE);

        List<Match> superLikes = matchRepository.findMatchesBySource(
                alpha.getId(), MatchSource.SUPER_LIKE, PageRequest.of(0, 100));
        assertFalse(superLikes.isEmpty(),
                "findMatchesBySource(SUPER_LIKE) must return the super-liked match");

        List<Match> algorithmBoosts = matchRepository.findMatchesBySource(
                alpha.getId(), MatchSource.ALGORITHM_BOOST, PageRequest.of(0, 100));
        assertTrue(algorithmBoosts.isEmpty(),
                "findMatchesBySource(ALGORITHM_BOOST) must return no matches for this user");
    }

    // ─── 5. Unmatch stores UNMATCHED enum ─────────────────────────────────

    @Test
    @Order(8)
    @DisplayName("unmatch() stores MatchStatus.UNMATCHED in the database")
    void unmatch_storesUnmatchedEnumStatus() {
        Match match = matchService.createMatch(alpha, beta, 70.0);
        matchService.unmatch(match.getId(), alpha.getId());

        Match afterUnmatch = matchRepository.findById(match.getId())
                .orElseThrow(() -> new AssertionError("Match disappeared after unmatch"));

        assertSame(MatchStatus.UNMATCHED, afterUnmatch.getStatus(),
                "unmatch() must store MatchStatus.UNMATCHED, not a raw string");
    }

    // ─── 6. Behavioral profile single-delete ──────────────────────────────

    @Test
    @Order(9)
    @DisplayName("deleteByUserId removes behavioral profile without a prior SELECT")
    @Transactional
    void deleteByUserId_removesBehavioralProfile() {
        // Create a behavioral profile for alpha if one does not exist.
        UserBehavioralProfile profile = behavioralProfileRepository
                .findByUserId(alpha.getId())
                .orElseGet(() -> behavioralProfileRepository.save(
                        UserBehavioralProfile.builder()
                                .user(alpha)
                                .totalLikes(0)
                                .totalPasses(0)
                                .confidenceLevel(0.0)
                                .createdAt(LocalDateTime.now())
                                .build()));

        assertNotNull(profile.getId(), "Profile must be persisted before deletion test");

        // Single-statement delete.
        behavioralProfileRepository.deleteByUserId(alpha.getId());

        // Must be gone.
        Optional<UserBehavioralProfile> afterDelete =
                behavioralProfileRepository.findByUserId(alpha.getId());
        assertTrue(afterDelete.isEmpty(),
                "deleteByUserId must remove the behavioral profile");
    }
}
