package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.enums.matching.MatchSource;
import com.example.dating.mappers.MatchDtoMapper;
import com.example.dating.models.matching.dao.Match;
import com.example.dating.models.matching.dto.MatchResponseDto;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.repositories.MatchRepository;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.matching.MatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies Integrity Batch H — Bidirectional Match Scores and Randomized Candidate Pool.
 *
 * <p>Covers:
 * <ol>
 *   <li>Match stores both directional scores (matchScore and matchScoreB).</li>
 *   <li>MatchDtoMapper returns correct directional score per requesting user.</li>
 *   <li>Native INSERT includes match_score_b column (structural).</li>
 *   <li>Candidate query includes ORDER BY randomization (structural).</li>
 *   <li>Backward compatibility — existing matches with null matchScoreB handled gracefully.</li>
 * </ol>
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class IntegrityBatchHBidirectionalScoreTest {

    @Autowired private MatchService matchService;
    @Autowired private MatchRepository matchRepository;
    @Autowired private UserJpaRepository userJpaRepository;
    @Autowired private MatchDtoMapper matchDtoMapper;
    @Autowired private PlatformTransactionManager txManager;

    private TransactionTemplate txTemplate;

    @BeforeEach
    void setUp() {
        txTemplate = new TransactionTemplate(txManager);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private UserEntity createUser(String emailPrefix) {
        return userJpaRepository.findByEmail(emailPrefix + "@integrity-h.test")
                .orElseGet(() -> userJpaRepository.save(
                        UserEntity.builder()
                                .email(emailPrefix + "@integrity-h.test")
                                .name("IntegrityH_" + emailPrefix)
                                .build()));
    }

    // ── Test 1: Match stores both directional scores ────────────────────────────

    @Test
    @DisplayName("createMatch stores both matchScore (A→B) and matchScoreB (B→A)")
    void createMatch_storesBothDirectionalScores() {
        UserEntity userA = txTemplate.execute(s -> createUser("h1-a-" + System.nanoTime()));
        UserEntity userB = txTemplate.execute(s -> createUser("h1-b-" + System.nanoTime()));

        Double swiperScore = 82.5;
        Double reverseScore = 71.3;

        Match match = txTemplate.execute(s ->
                matchService.createMatch(userA, userB, swiperScore, reverseScore, MatchSource.MUTUAL_SWIPE));

        Match fromDb = matchRepository.findById(match.getId()).orElseThrow();

        assertNotNull(fromDb.getMatchScore(), "matchScore (A→B) must not be null");
        assertNotNull(fromDb.getMatchScoreB(), "matchScoreB (B→A) must not be null");

        // Determine which direction was stored based on ID ordering
        boolean aIsFirst = userA.getId().compareTo(userB.getId()) < 0;
        if (aIsFirst) {
            // userA is the swiper and sorts first → matchScore = swiperScore, matchScoreB = reverseScore
            assertEquals(swiperScore, fromDb.getMatchScore(), 0.001);
            assertEquals(reverseScore, fromDb.getMatchScoreB(), 0.001);
        } else {
            // userA sorted after userB → swapped: matchScore = reverseScore, matchScoreB = swiperScore
            assertEquals(reverseScore, fromDb.getMatchScore(), 0.001);
            assertEquals(swiperScore, fromDb.getMatchScoreB(), 0.001);
        }

        // Scores may differ (directional scoring)
        assertNotEquals(fromDb.getMatchScore(), fromDb.getMatchScoreB(),
                "Directional scores should differ when different values are provided");

        // Cleanup
        txTemplate.executeWithoutResult(s -> {
            matchRepository.deleteById(match.getId());
            userJpaRepository.deleteById(userA.getId());
            userJpaRepository.deleteById(userB.getId());
        });
    }

    // ── Test 2: MatchDtoMapper returns correct directional score ─────────────────

    @Test
    @DisplayName("MatchDtoMapper returns matchScore for userA and matchScoreB for userB")
    void matchDtoMapper_returnsCorrectDirectionalScore() {
        UserEntity userA = txTemplate.execute(s -> createUser("h2-a-" + System.nanoTime()));
        UserEntity userB = txTemplate.execute(s -> createUser("h2-b-" + System.nanoTime()));

        Double swiperScore = 90.0;
        Double reverseScore = 65.0;

        Match match = txTemplate.execute(s ->
                matchService.createMatch(userA, userB, swiperScore, reverseScore, MatchSource.MUTUAL_SWIPE));

        // Run DTO mapping inside a transaction so lazy userA/userB proxies can be loaded
        txTemplate.executeWithoutResult(s -> {
            Match fromDb = matchRepository.findById(match.getId()).orElseThrow();

            // Map for userA → should get matchScore (A→B direction)
            MatchResponseDto dtoForA = matchDtoMapper.toDto(fromDb, fromDb.getUserA().getId());
            assertEquals(fromDb.getMatchScore(), dtoForA.getMatchScore(), 0.001,
                    "UserA should see the A→B directional score");

            // Map for userB → should get matchScoreB (B→A direction)
            MatchResponseDto dtoForB = matchDtoMapper.toDto(fromDb, fromDb.getUserB().getId());
            assertEquals(fromDb.getMatchScoreB(), dtoForB.getMatchScore(), 0.001,
                    "UserB should see the B→A directional score");

            // The two DTOs should show different scores
            assertNotEquals(dtoForA.getMatchScore(), dtoForB.getMatchScore(),
                    "Each user must see their own directional score");
        });

        // Cleanup
        txTemplate.executeWithoutResult(s -> {
            matchRepository.deleteById(match.getId());
            userJpaRepository.deleteById(userA.getId());
            userJpaRepository.deleteById(userB.getId());
        });
    }

    // ── Test 3: Native INSERT includes match_score_b (structural) ────────────────

    @Test
    @DisplayName("insertMatchIfAbsent native query includes match_score_b column")
    void insertMatchIfAbsent_includesMatchScoreBColumn() throws Exception {
        Method method = MatchRepository.class.getMethod(
                "insertMatchIfAbsent",
                String.class,      // id
                String.class,      // userAId
                String.class,      // userBId
                Double.class,      // matchScoreA
                Double.class,      // matchScoreB
                String.class,      // matchSource
                LocalDateTime.class, // matchedAt
                LocalDateTime.class, // createdAt
                LocalDateTime.class  // updatedAt
        );

        assertNotNull(method, "insertMatchIfAbsent must exist with matchScoreB parameter");

        // Verify the native query text contains match_score_b
        Query queryAnnotation = method.getAnnotation(Query.class);
        assertNotNull(queryAnnotation, "Method must have @Query annotation");
        assertTrue(queryAnnotation.nativeQuery(), "Must be a native query");
        assertTrue(queryAnnotation.value().contains("match_score_b"),
                "Native INSERT must include match_score_b column");

        // Verify @Modifying is present
        assertNotNull(method.getAnnotation(Modifying.class),
                "insertMatchIfAbsent must have @Modifying annotation");
    }

    // ── Test 4: Candidate query includes randomization (structural) ──────────────

    @Test
    @DisplayName("findCandidateUsers JPQL query includes ORDER BY RANDOM randomization")
    void findCandidateUsers_includesRandomOrdering() throws Exception {
        // Find the findCandidateUsers method on UserJpaRepository
        Method[] methods = UserJpaRepository.class.getMethods();
        Method candidateMethod = null;
        for (Method m : methods) {
            if ("findCandidateUsers".equals(m.getName())) {
                candidateMethod = m;
                break;
            }
        }
        assertNotNull(candidateMethod, "findCandidateUsers must exist on UserJpaRepository");

        Query queryAnnotation = candidateMethod.getAnnotation(Query.class);
        assertNotNull(queryAnnotation, "findCandidateUsers must have @Query annotation");

        String queryValue = queryAnnotation.value().toLowerCase();
        assertTrue(queryValue.contains("random"),
                "findCandidateUsers query must include RANDOM for randomized candidate selection. " +
                "Got: " + queryAnnotation.value());
    }

    // ── Test 5: Backward compatibility — null matchScoreB handled gracefully ─────

    @Test
    @DisplayName("MatchDtoMapper handles null matchScoreB gracefully (pre-Batch H matches)")
    void matchDtoMapper_handlesNullMatchScoreB_gracefully() {
        UserEntity userA = txTemplate.execute(s -> createUser("h5-a-" + System.nanoTime()));
        UserEntity userB = txTemplate.execute(s -> createUser("h5-b-" + System.nanoTime()));

        // Simulate a pre-Batch H match by building a Match with null matchScoreB.
        // In production, existing matches created before Batch H will have this column as null.
        txTemplate.executeWithoutResult(s -> {
            // Re-fetch managed entities
            UserEntity managedA = userJpaRepository.findById(userA.getId()).orElseThrow();
            UserEntity managedB = userJpaRepository.findById(userB.getId()).orElseThrow();

            // Ensure correct ordering (userA.id < userB.id)
            UserEntity orderedA = managedA.getId().compareTo(managedB.getId()) < 0 ? managedA : managedB;
            UserEntity orderedB = managedA.getId().compareTo(managedB.getId()) < 0 ? managedB : managedA;

            Match preBatchH = Match.builder()
                    .userA(orderedA)
                    .userB(orderedB)
                    .matchScore(77.0)
                    .matchScoreB(null)  // Simulates pre-Batch H match
                    .build();

            // matchScoreB is null
            assertNull(preBatchH.getMatchScoreB(),
                    "Pre-Batch H matches should have null matchScoreB");

            // Map for userA → should get matchScore directly
            MatchResponseDto dtoForA = matchDtoMapper.toDto(preBatchH, orderedA.getId());
            assertNotNull(dtoForA.getMatchScore(), "UserA DTO score must not be null");
            assertEquals(77.0, dtoForA.getMatchScore(), 0.001);

            // Map for userB → should fall back to matchScore when matchScoreB is null
            MatchResponseDto dtoForB = matchDtoMapper.toDto(preBatchH, orderedB.getId());
            assertNotNull(dtoForB.getMatchScore(),
                    "UserB DTO score must not be null even when matchScoreB is null (fallback)");
            assertEquals(77.0, dtoForB.getMatchScore(), 0.001,
                    "When matchScoreB is null, mapper should fall back to matchScore");
        });

        // Cleanup
        txTemplate.executeWithoutResult(s -> {
            userJpaRepository.deleteById(userA.getId());
            userJpaRepository.deleteById(userB.getId());
        });
    }
}
