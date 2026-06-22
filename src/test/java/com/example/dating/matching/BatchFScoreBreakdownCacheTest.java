package com.example.dating.matching;

import com.example.dating.mappers.UserMapper;
import com.example.dating.models.matching.dao.UserMatchScore;
import com.example.dating.models.matching.dto.MatchBreakdown;
import com.example.dating.models.matching.dto.MatchScore;
import com.example.dating.models.matching.dto.SharedGenre;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.repositories.UserBehavioralProfileRepository;
import com.example.dating.repositories.UserMatchScoreRepository;
import com.example.dating.services.matching.BehavioralScoreCalculator;
import com.example.dating.services.matching.InterestsScoreCalculator;
import com.example.dating.services.matching.LifestyleScoreCalculator;
import com.example.dating.services.matching.MatchScoreCalculator;
import com.example.dating.services.matching.MatchScoringService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Batch F — Preserve Match Breakdown and Insights in Score Cache.
 *
 * <p>Verifies:
 * <ol>
 *   <li>{@code persistScoreCache} serializes breakdown to JSON and passes it to {@code upsertScore}.</li>
 *   <li>{@code persistScoreCache} passes null JSON when the score has no breakdown or insights.</li>
 *   <li>On a cache hit, {@code calculateScore} restores breakdown and insights from JSON
 *       (non-null, matching original values).</li>
 *   <li>Legacy cache rows with null JSON do not throw — breakdown and insights are null on cache hit.</li>
 *   <li>Concurrent cache hits: 20 threads all get a non-null breakdown from the same cached row
 *       (ObjectMapper is thread-safe).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class BatchFScoreBreakdownCacheTest {

    @Mock
    private MatchScoreCalculator matchScoreCalculator;
    @Mock
    private LifestyleScoreCalculator lifestyleScoreCalculator;
    @Mock
    private InterestsScoreCalculator interestsScoreCalculator;
    @Mock
    private UserMatchScoreRepository userMatchScoreRepository;
    @Mock
    private UserMapper userMapper;
    @Mock
    private UserBehavioralProfileRepository behavioralProfileRepository;
    @Mock
    private BehavioralScoreCalculator behavioralScoreCalculator;
    @Spy
    private ObjectMapper objectMapper;

    private MatchScoringService service;

    @BeforeEach
    void setUp() {
        service = new MatchScoringService(
                matchScoreCalculator,
                lifestyleScoreCalculator,
                interestsScoreCalculator,
                userMatchScoreRepository,
                userMapper,
                behavioralProfileRepository,
                objectMapper);
        // Inject the @Lazy @Autowired field via reflection
        try {
            var field = MatchScoringService.class.getDeclaredField("behavioralScoreCalculator");
            field.setAccessible(true);
            field.set(service, behavioralScoreCalculator);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // -----------------------------------------------------------------------
    // 1. persistScoreCache serializes breakdown and insights JSON
    // -----------------------------------------------------------------------

    @Test
    void persistScoreCache_serializesBreakdownAndInsightsToJson() throws Exception {
        doNothing().when(userMatchScoreRepository).upsertScore(
                anyString(), anyString(), anyString(),
                any(double.class), any(double.class), any(double.class),
                any(double.class), any(double.class), any(double.class),
                anyString(), any(LocalDateTime.class),
                any(), any());

        MatchBreakdown breakdown = MatchBreakdown.builder()
                .sharedGenres(List.of(SharedGenre.builder()
                        .genre("rock").genreDisplayName("Rock")
                        .userWeight(0.8).otherWeight(0.7).overlap(0.7).similarity(0.875)
                        .build()))
                .sharedGenreCount(1)
                .totalUniqueGenres(5)
                .genreOverlapScore(70.0)
                .build();
        List<String> insights = List.of("Great lifestyle compatibility", "Shared interests: hiking");

        MatchScore score = MatchScore.builder()
                .overallScore(75.0).musicScore(80.0).lifestyleScore(70.0)
                .interestsScore(65.0).locationScore(90.0).behavioralScore(60.0)
                .breakdown(breakdown).insights(insights)
                .build();

        UserEntity userA = mockUser("a1");
        UserEntity userB = mockUser("b1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> breakdownJsonCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> insightsJsonCaptor  = ArgumentCaptor.forClass(String.class);

        service.persistScoreCache(userA, userB, score);

        verify(userMatchScoreRepository).upsertScore(
                anyString(), anyString(), anyString(),
                any(double.class), any(double.class), any(double.class),
                any(double.class), any(double.class), any(double.class),
                anyString(), any(LocalDateTime.class),
                breakdownJsonCaptor.capture(), insightsJsonCaptor.capture());

        String capturedBreakdown = breakdownJsonCaptor.getValue();
        String capturedInsights  = insightsJsonCaptor.getValue();

        assertThat(capturedBreakdown).isNotNull().contains("rock").contains("Rock");
        assertThat(capturedInsights).isNotNull().contains("Great lifestyle compatibility");

        // Verify round-trip: the JSON is valid and reconstructable
        MatchBreakdown deserialized = objectMapper.readValue(capturedBreakdown, MatchBreakdown.class);
        assertThat(deserialized.getSharedGenres()).hasSize(1);
        assertThat(deserialized.getSharedGenres().get(0).getGenreDisplayName()).isEqualTo("Rock");
    }

    // -----------------------------------------------------------------------
    // 2. persistScoreCache passes null JSON when score has no breakdown/insights
    // -----------------------------------------------------------------------

    @Test
    void persistScoreCache_passesNullJsonWhenBreakdownAbsent() {
        doNothing().when(userMatchScoreRepository).upsertScore(
                anyString(), anyString(), anyString(),
                any(double.class), any(double.class), any(double.class),
                any(double.class), any(double.class), any(double.class),
                anyString(), any(LocalDateTime.class),
                any(), any());

        MatchScore score = MatchScore.builder()
                .overallScore(50.0).musicScore(50.0).lifestyleScore(50.0)
                .interestsScore(50.0).locationScore(50.0).behavioralScore(50.0)
                // no breakdown, no insights
                .build();

        UserEntity userA = mockUser("a2");
        UserEntity userB = mockUser("b2");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> breakdownCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> insightsCaptor  = ArgumentCaptor.forClass(String.class);

        service.persistScoreCache(userA, userB, score);

        verify(userMatchScoreRepository).upsertScore(
                anyString(), anyString(), anyString(),
                any(double.class), any(double.class), any(double.class),
                any(double.class), any(double.class), any(double.class),
                anyString(), any(LocalDateTime.class),
                breakdownCaptor.capture(), insightsCaptor.capture());

        assertThat(breakdownCaptor.getValue()).isNull();
        assertThat(insightsCaptor.getValue()).isNull();
    }

    // -----------------------------------------------------------------------
    // 3. Cache hit restores breakdown and insights from JSON
    // -----------------------------------------------------------------------

    @Test
    void calculateScore_cacheHit_restoresBreakdownAndInsightsFromJson() throws Exception {
        MatchBreakdown breakdown = MatchBreakdown.builder()
                .sharedGenres(List.of(SharedGenre.builder()
                        .genre("indie").genreDisplayName("Indie")
                        .userWeight(0.9).otherWeight(0.8).overlap(0.8).similarity(0.889)
                        .build()))
                .sharedGenreCount(1)
                .genreOverlapScore(80.0)
                .sharedInterests(List.of("hiking", "cooking"))
                .build();
        List<String> insights = List.of("You both love indie rock", "Shared interests: hiking, cooking");

        String breakdownJson = objectMapper.writeValueAsString(breakdown);
        String insightsJson  = objectMapper.writeValueAsString(insights);

        UserMatchScore cachedScore = buildCachedScore(breakdownJson, insightsJson);

        UserEntity userA = mockUser("a3");
        UserEntity userB = mockUser("b3");
        when(userA.getUpdatedAt()).thenReturn(LocalDateTime.now().minusHours(1));
        when(userB.getUpdatedAt()).thenReturn(LocalDateTime.now().minusHours(1));
        when(userMatchScoreRepository.findByUserIdAndMatchedUserId("a3", "b3"))
                .thenReturn(Optional.of(cachedScore));

        MatchScore result = service.calculateScore(userA, userB);

        assertThat(result.getBreakdown()).isNotNull();
        assertThat(result.getBreakdown().getSharedGenres()).hasSize(1);
        assertThat(result.getBreakdown().getSharedGenres().get(0).getGenreDisplayName()).isEqualTo("Indie");
        assertThat(result.getBreakdown().getSharedInterests()).containsExactly("hiking", "cooking");

        assertThat(result.getInsights()).isNotNull().hasSize(2);
        assertThat(result.getInsights().get(0)).isEqualTo("You both love indie rock");
    }

    // -----------------------------------------------------------------------
    // 4. Legacy rows (null JSON) produce null breakdown — no NPE
    // -----------------------------------------------------------------------

    @Test
    void calculateScore_cacheHit_nullJsonProducesNullBreakdownWithoutException() {
        // Simulate a pre-Batch F cache row with no JSON columns
        UserMatchScore legacyScore = buildCachedScore(null, null);

        UserEntity userA = mockUser("a4");
        UserEntity userB = mockUser("b4");
        when(userA.getUpdatedAt()).thenReturn(LocalDateTime.now().minusHours(1));
        when(userB.getUpdatedAt()).thenReturn(LocalDateTime.now().minusHours(1));
        when(userMatchScoreRepository.findByUserIdAndMatchedUserId("a4", "b4"))
                .thenReturn(Optional.of(legacyScore));

        MatchScore result = service.calculateScore(userA, userB);

        // No exception; both fields are null (legacy fallback)
        assertThat(result.getBreakdown()).isNull();
        assertThat(result.getInsights()).isNull();
        // Numeric fields still populated from cache
        assertThat(result.getOverallScore()).isEqualTo(75.0);
    }

    // -----------------------------------------------------------------------
    // 5. Concurrent cache hits — all 20 threads get non-null breakdown
    // -----------------------------------------------------------------------

    @Test
    void calculateScore_concurrent_allCacheHitsGetNonNullBreakdown()
            throws Exception {

        MatchBreakdown breakdown = MatchBreakdown.builder()
                .sharedGenres(List.of(SharedGenre.builder()
                        .genre("jazz").genreDisplayName("Jazz")
                        .userWeight(0.7).otherWeight(0.6).overlap(0.6).similarity(0.857)
                        .build()))
                .sharedGenreCount(1)
                .genreOverlapScore(60.0)
                .build();
        List<String> insights = List.of("Great music taste overlap");

        String breakdownJson = objectMapper.writeValueAsString(breakdown);
        String insightsJson  = objectMapper.writeValueAsString(insights);

        UserMatchScore cachedScore = buildCachedScore(breakdownJson, insightsJson);

        UserEntity userA = mockUser("a5");
        UserEntity userB = mockUser("b5");
        when(userA.getUpdatedAt()).thenReturn(LocalDateTime.now().minusHours(1));
        when(userB.getUpdatedAt()).thenReturn(LocalDateTime.now().minusHours(1));
        when(userMatchScoreRepository.findByUserIdAndMatchedUserId("a5", "b5"))
                .thenReturn(Optional.of(cachedScore));

        int threads = 20;
        CountDownLatch start         = new CountDownLatch(1);
        CountDownLatch done          = new CountDownLatch(threads);
        AtomicInteger nullBreakdowns = new AtomicInteger(0);
        AtomicInteger nullInsights   = new AtomicInteger(0);
        AtomicInteger errors         = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    MatchScore result = service.calculateScore(userA, userB);
                    if (result.getBreakdown() == null) nullBreakdowns.incrementAndGet();
                    if (result.getInsights()  == null) nullInsights.incrementAndGet();
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
        assertThat(done.await(10, TimeUnit.SECONDS))
                .as("All threads should finish within 10 s")
                .isTrue();

        assertThat(errors.get()).as("No thread should throw").isZero();
        assertThat(nullBreakdowns.get()).as("No thread should get null breakdown").isZero();
        assertThat(nullInsights.get()).as("No thread should get null insights").isZero();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private UserEntity mockUser(String id) {
        UserEntity user = mock(UserEntity.class);
        when(user.getId()).thenReturn(id);
        return user;
    }

    private UserMatchScore buildCachedScore(String breakdownJson, String insightsJson) {
        return UserMatchScore.builder()
                .id("score-id")
                .overallScore(75.0)
                .musicScore(80.0)
                .lifestyleScore(70.0)
                .interestsScore(65.0)
                .locationScore(90.0)
                .behavioralScore(60.0)
                .algorithmVersion("v2.0")
                .computedAt(LocalDateTime.now())  // fresh — after minusHours(1) updatedAt
                .breakdownJson(breakdownJson)
                .insightsJson(insightsJson)
                .build();
    }
}
