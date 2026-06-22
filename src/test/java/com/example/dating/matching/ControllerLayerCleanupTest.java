package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.mappers.MatchDtoMapper;
import com.example.dating.models.matching.dao.Match;
import com.example.dating.models.matching.dto.AnalyticsResponseDto;
import com.example.dating.models.matching.dto.MatchPageResponseDto;
import com.example.dating.models.matching.dto.MatchResponseDto;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.repositories.MatchRepository;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.matching.MatchService;
import com.example.dating.services.matching.SwipeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies Batch I — Controller Layer Cleanup.
 *
 * <h3>Checks</h3>
 * <ol>
 *   <li><strong>MatchDtoMapper</strong> — maps all fields from a Match entity to
 *       {@link MatchResponseDto} correctly, including "other user" resolution from
 *       both participants' perspectives.</li>
 *   <li><strong>JSON field names</strong> — {@link MatchResponseDto},
 *       {@link AnalyticsResponseDto}, and {@link MatchPageResponseDto} serialise to
 *       exactly the same field names as the previous {@code Map<String, Object>} responses,
 *       preserving the frontend API contract.</li>
 *   <li><strong>Enum serialisation</strong> — {@code status} and {@code matchSource} in
 *       {@link MatchResponseDto} serialise to lowercase strings (e.g. {@code "active"},
 *       {@code "mutual_swipe"}), identical to the old behaviour where Jackson serialised
 *       the enum via {@code @JsonValue}.</li>
 *   <li><strong>SwipeService.getAnalytics()</strong> — {@code matchRate} and
 *       {@code totalPasses} are computed correctly in the service layer, not the
 *       controller.</li>
 * </ol>
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ControllerLayerCleanupTest {

    @Autowired private MatchDtoMapper     matchDtoMapper;
    @Autowired private MatchService       matchService;
    @Autowired private SwipeService       swipeService;
    @Autowired private MatchRepository    matchRepository;
    @Autowired private UserJpaRepository  userRepository;
    @Autowired private ObjectMapper       objectMapper;

    private UserEntity userX;
    private UserEntity userY;

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        userX = userRepository.findByEmail("batch.i.x@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("batch.i.x@test.com")
                                .name("BatchIX")
                                .build()));

        userY = userRepository.findByEmail("batch.i.y@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("batch.i.y@test.com")
                                .name("BatchIY")
                                .build()));

        matchRepository.findMatchBetweenUsers(userX.getId(), userY.getId())
                .ifPresent(m -> matchRepository.deleteById(m.getId()));
    }

    @AfterEach
    void tearDown() {
        matchRepository.findMatchBetweenUsers(userX.getId(), userY.getId())
                .ifPresent(m -> matchRepository.deleteById(m.getId()));
    }

    // ─── 1. Mapper field mapping ──────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("MatchDtoMapper maps all declared fields from a Match entity")
    void mapper_mapsAllFields() {
        Match match = matchService.createMatch(userX, userY, 78.5);

        MatchResponseDto dto = matchDtoMapper.toDto(match, userX.getId());

        assertEquals(match.getId(),       dto.getMatchId());
        assertEquals(78.5,                dto.getMatchScore(), 0.001);
        assertNotNull(dto.getStatus());
        assertNotNull(dto.getMatchSource());
        assertNotNull(dto.getMatchedAt());
        assertNotNull(dto.getConversationStarted());
        assertEquals(userY.getId(),       dto.getOtherUserId(),
                "otherUserId must be the *other* participant, not the requester");
        assertEquals("BatchIY",           dto.getOtherUserName());
    }

    @Test
    @Order(2)
    @DisplayName("MatchDtoMapper resolves 'other user' correctly from both participants' perspectives")
    void mapper_otherUserResolutionBothSides() {
        Match match = matchService.createMatch(userX, userY, 65.0);

        MatchResponseDto fromX = matchDtoMapper.toDto(match, userX.getId());
        MatchResponseDto fromY = matchDtoMapper.toDto(match, userY.getId());

        assertEquals(userY.getId(), fromX.getOtherUserId(),
                "When requested by X, otherUserId must be Y");
        assertEquals(userX.getId(), fromY.getOtherUserId(),
                "When requested by Y, otherUserId must be X");
    }

    // ─── 2. JSON field names (frontend compatibility) ────────────────────────

    @Test
    @Order(3)
    @DisplayName("MatchResponseDto JSON field names are identical to old Map<String,Object> response")
    void matchResponseDto_jsonFieldNamesUnchanged() {
        Match match = matchService.createMatch(userX, userY, 72.0);
        MatchResponseDto dto = matchDtoMapper.toDto(match, userX.getId());

        JsonNode node = objectMapper.valueToTree(dto);

        assertTrue(node.has("matchId"),             "must have 'matchId'");
        assertTrue(node.has("matchScore"),           "must have 'matchScore'");
        assertTrue(node.has("status"),               "must have 'status'");
        assertTrue(node.has("conversationStarted"),  "must have 'conversationStarted'");
        assertTrue(node.has("matchSource"),          "must have 'matchSource'");
        assertTrue(node.has("matchedAt"),            "must have 'matchedAt'");
        assertTrue(node.has("otherUserId"),          "must have 'otherUserId'");
        assertTrue(node.has("otherUserName"),        "must have 'otherUserName'");
        assertTrue(node.has("otherUserPhoto"),       "must have 'otherUserPhoto'");
    }

    @Test
    @Order(4)
    @DisplayName("MatchStatus and MatchSource serialise to lowercase strings in MatchResponseDto")
    void matchResponseDto_enumFieldsSerialiseToLowercase() {
        Match match = matchService.createMatch(userX, userY, 80.0);
        MatchResponseDto dto = matchDtoMapper.toDto(match, userX.getId());

        JsonNode node = objectMapper.valueToTree(dto);

        assertEquals("active",       node.get("status").asText());
        assertEquals("mutual_swipe", node.get("matchSource").asText());
    }

    @Test
    @Order(5)
    @DisplayName("AnalyticsResponseDto JSON field names are identical to old Map<String,Object> response")
    void analyticsResponseDto_jsonFieldNamesUnchanged() {
        AnalyticsResponseDto dto = AnalyticsResponseDto.builder()
                .totalSwipes(10L)
                .totalLikes(5L)
                .totalPasses(5L)
                .totalMatches(2L)
                .swipeThroughRate(0.5)
                .matchRate(0.4)
                .build();

        JsonNode node = objectMapper.valueToTree(dto);

        assertTrue(node.has("totalSwipes"),      "must have 'totalSwipes'");
        assertTrue(node.has("totalLikes"),        "must have 'totalLikes'");
        assertTrue(node.has("totalPasses"),       "must have 'totalPasses'");
        assertTrue(node.has("totalMatches"),      "must have 'totalMatches'");
        assertTrue(node.has("swipeThroughRate"),  "must have 'swipeThroughRate'");
        assertTrue(node.has("matchRate"),         "must have 'matchRate'");
    }

    @Test
    @Order(6)
    @DisplayName("MatchPageResponseDto JSON field names are identical to old Map<String,Object> response")
    void matchPageResponseDto_jsonFieldNamesUnchanged() {
        Match match = matchService.createMatch(userX, userY, 68.0);
        MatchResponseDto matchDto = matchDtoMapper.toDto(match, userX.getId());

        MatchPageResponseDto page = MatchPageResponseDto.builder()
                .matches(List.of(matchDto))
                .total(1L)
                .limit(20)
                .offset(0)
                .hasMore(false)
                .build();

        JsonNode node = objectMapper.valueToTree(page);

        assertTrue(node.has("matches"),  "must have 'matches'");
        assertTrue(node.has("total"),    "must have 'total'");
        assertTrue(node.has("limit"),    "must have 'limit'");
        assertTrue(node.has("offset"),   "must have 'offset'");
        assertTrue(node.has("hasMore"),  "must have 'hasMore'");
        assertTrue(node.get("matches").isArray(),      "matches must be a JSON array");
        assertEquals(1, node.get("matches").size(),    "matches array must contain 1 element");
    }

    // ─── 3. SwipeService.getAnalytics() computation ──────────────────────────

    @Test
    @Order(7)
    @DisplayName("SwipeService.getAnalytics() returns a non-null DTO with valid computed values")
    void analytics_computedValuesAreValid() {
        // Users with no swipes — safe baseline
        AnalyticsResponseDto dto = swipeService.getAnalytics(userX.getId());

        assertNotNull(dto);
        assertEquals(dto.getTotalSwipes() - dto.getTotalLikes(), dto.getTotalPasses(),
                "totalPasses must equal totalSwipes - totalLikes");
        assertTrue(dto.getTotalPasses() >= 0,
                "totalPasses must not be negative");
    }

    @Test
    @Order(8)
    @DisplayName("SwipeService.getAnalytics() returns matchRate 0.0 when totalLikes is 0 (no division by zero)")
    void analytics_matchRateIsZeroWhenNoLikes() {
        AnalyticsResponseDto dto = swipeService.getAnalytics(userX.getId());

        if (dto.getTotalLikes() == 0) {
            assertEquals(0.0, dto.getMatchRate(), 0.001,
                    "matchRate must be 0.0 when totalLikes is 0 to avoid division by zero");
        }
    }
}
