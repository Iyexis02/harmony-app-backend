package com.example.dating.matching;

import com.example.dating.controllers.MatchingController;
import com.example.dating.mappers.MatchDtoMapper;
import com.example.dating.models.matching.dao.Match;
import com.example.dating.models.matching.dto.MatchScore;
import com.example.dating.mappers.UserMapper;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.repositories.UserSwipeRepository;
import com.example.dating.services.matching.MatchRecommendationService;
import com.example.dating.services.matching.MatchService;
import com.example.dating.services.matching.SwipeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Batch D — IDOR Protection + Pagination Bounds
 *
 * <p>Verifies all three changes in MatchingController without a Spring context or database:
 * <ol>
 *   <li>Self-score: GET /score/{selfId} returns 400 and never reaches the service.</li>
 *   <li>Block-check: GET /score/{blockedUserId} returns 403 (in either block direction).</li>
 *   <li>clampLimit: values outside [1, 100] are clamped before passing to services.</li>
 *   <li>clampOffset: negative offsets are floored to 0.</li>
 *   <li>Status guard: GET /matches?status= values other than "active"/"all" return 400.</li>
 *   <li>Concurrent: 20 threads hitting clamp + self-check simultaneously — all correct.</li>
 * </ol>
 *
 * Uses Mockito (bundled with spring-boot-starter-test). No database required.
 */
class BatchDIdorProtectionTest {

    private MatchRecommendationService recommendationService;
    private SwipeService swipeService;
    private MatchService matchService;
    private UserJpaRepository userJpaRepository;
    private UserMapper userMapper;
    private MatchDtoMapper matchDtoMapper;
    private UserSwipeRepository swipeRepository;
    private MatchingController controller;

    private User currentUser;
    private String currentUserId;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        recommendationService = mock(MatchRecommendationService.class);
        swipeService          = mock(SwipeService.class);
        matchService          = mock(MatchService.class);
        userJpaRepository     = mock(UserJpaRepository.class);
        userMapper            = mock(UserMapper.class);
        matchDtoMapper        = mock(MatchDtoMapper.class);
        swipeRepository       = mock(UserSwipeRepository.class);

        // Lombok @RequiredArgsConstructor generates the constructor in field-declaration order.
        controller = new MatchingController(
                recommendationService, swipeService, matchService,
                userJpaRepository, userMapper, matchDtoMapper, swipeRepository);

        currentUserId = UUID.randomUUID().toString();
        currentUser   = mock(User.class);
        when(currentUser.getId()).thenReturn(currentUserId);

        Jwt jwt = mock(Jwt.class);
        when(jwt.getClaimAsString("userId")).thenReturn(currentUserId);
        authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(jwt);

        UserEntity mockEntity = mock(UserEntity.class);
        when(userJpaRepository.findById(currentUserId)).thenReturn(Optional.of(mockEntity));
        when(userMapper.toDomain(mockEntity)).thenReturn(currentUser);
    }

    // -------------------------------------------------------------------------
    // clampLimit — via reflection (method is private static)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("clampLimit: value <= 0 is raised to 1")
    void clampLimit_zeroOrNegative_raisedTo1() throws Exception {
        assertEquals(1, clampLimit(0));
        assertEquals(1, clampLimit(-1));
        assertEquals(1, clampLimit(Integer.MIN_VALUE));
    }

    @Test
    @DisplayName("clampLimit: value > 100 is capped at 100")
    void clampLimit_aboveMax_cappedAt100() throws Exception {
        assertEquals(100, clampLimit(101));
        assertEquals(100, clampLimit(999_999));
        assertEquals(100, clampLimit(Integer.MAX_VALUE));
    }

    @Test
    @DisplayName("clampLimit: values in [1, 100] pass through unchanged")
    void clampLimit_inRange_unchanged() throws Exception {
        assertEquals(1,   clampLimit(1));
        assertEquals(20,  clampLimit(20));
        assertEquals(100, clampLimit(100));
    }

    // -------------------------------------------------------------------------
    // clampOffset — via reflection
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("clampOffset: negative values are floored to 0")
    void clampOffset_negative_raisedTo0() throws Exception {
        assertEquals(0, clampOffset(-1));
        assertEquals(0, clampOffset(-100));
        assertEquals(0, clampOffset(Integer.MIN_VALUE));
    }

    @Test
    @DisplayName("clampOffset: zero and positive values pass through unchanged")
    void clampOffset_zeroAndPositive_unchanged() throws Exception {
        assertEquals(0,   clampOffset(0));
        assertEquals(1,   clampOffset(1));
        assertEquals(500, clampOffset(500));
    }

    // -------------------------------------------------------------------------
    // getMatchScore — self-check
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getMatchScore: using own ID returns 400 and never calls the service")
    void getMatchScore_selfId_returns400AndSkipsService() {
        ResponseEntity<MatchScore> response = controller.getMatchScore(currentUserId, authentication);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(recommendationService);
    }

    // -------------------------------------------------------------------------
    // getMatchScore — block-check (both directions)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getMatchScore: current user blocked target → 403, service not called")
    void getMatchScore_currentUserBlockedTarget_returns403() {
        String otherId = UUID.randomUUID().toString();
        when(swipeRepository.findBlockedUserIds(eq(currentUserId), any(Pageable.class))).thenReturn(List.of(otherId));
        when(swipeRepository.findBlockedByUserIds(eq(currentUserId), any(Pageable.class))).thenReturn(List.of());

        ResponseEntity<MatchScore> response = controller.getMatchScore(otherId, authentication);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verifyNoInteractions(recommendationService);
    }

    @Test
    @DisplayName("getMatchScore: target blocked current user → 403, service not called")
    void getMatchScore_targetBlockedCurrentUser_returns403() {
        String otherId = UUID.randomUUID().toString();
        when(swipeRepository.findBlockedUserIds(eq(currentUserId), any(Pageable.class))).thenReturn(List.of());
        when(swipeRepository.findBlockedByUserIds(eq(currentUserId), any(Pageable.class))).thenReturn(List.of(otherId));

        ResponseEntity<MatchScore> response = controller.getMatchScore(otherId, authentication);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verifyNoInteractions(recommendationService);
    }

    @Test
    @DisplayName("getMatchScore: no block relationship → 200 and service is called")
    void getMatchScore_noBlock_proceedsToService() {
        String otherId = UUID.randomUUID().toString();
        when(swipeRepository.findBlockedUserIds(eq(currentUserId), any(Pageable.class))).thenReturn(List.of());
        when(swipeRepository.findBlockedByUserIds(eq(currentUserId), any(Pageable.class))).thenReturn(List.of());
        MatchScore score = mock(MatchScore.class);
        when(recommendationService.getMatchScore(currentUser, otherId)).thenReturn(score);

        ResponseEntity<MatchScore> response = controller.getMatchScore(otherId, authentication);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(score, response.getBody());
        verify(recommendationService).getMatchScore(currentUser, otherId);
    }

    // -------------------------------------------------------------------------
    // getMatches — status validation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getMatches: invalid status values return 400 before touching any service")
    void getMatches_invalidStatus_returns400() {
        for (String bad : List.of("hacked", "deleted", "pending", "HACKED", "x")) {
            ResponseEntity<?> response = controller.getMatches(bad, 20, 0, authentication);
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                    "Status '" + bad + "' must be rejected with 400");
        }
        verifyNoInteractions(matchService);
    }

    @Test
    @DisplayName("getMatches: 'active' and 'all' (case-insensitive) reach the service")
    void getMatches_validStatuses_proceedToService() {
        when(matchService.getActiveMatches(eq(currentUserId), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of()));
        when(matchService.getAllMatches(eq(currentUserId), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of()));

        for (String valid : List.of("active", "all", "ACTIVE", "ALL", "Active", "All")) {
            ResponseEntity<?> response = controller.getMatches(valid, 20, 0, authentication);
            assertNotEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                    "Status '" + valid + "' must not be rejected");
        }
    }

    // -------------------------------------------------------------------------
    // Concurrent: 20 threads simultaneously exercising clamp + self-check
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Concurrent: 20 threads calling clampLimit/clampOffset and self-check — all correct")
    void concurrent_clampAndSelfCheck_allCorrect() throws Exception {
        int threads = 20;
        CountDownLatch startGate  = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(threads);
        AtomicInteger  correct    = new AtomicInteger(0);
        List<Throwable> errors    = new ArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await(); // synchronised start

                    // clampLimit: oversized value must cap at 100
                    if (clampLimit(999_999) != 100) return;

                    // clampLimit: value 1 must pass through
                    if (clampLimit(1) != 1) return;

                    // clampOffset: negative must floor to 0
                    if (clampOffset(-999) != 0) return;

                    // clampOffset: positive must pass through
                    if (clampOffset(50) != 50) return;

                    // self-score check must return 400 (no DB/service call needed)
                    ResponseEntity<MatchScore> selfResp =
                            controller.getMatchScore(currentUserId, authentication);
                    if (selfResp.getStatusCode() != HttpStatus.BAD_REQUEST) return;

                    correct.incrementAndGet();
                } catch (Throwable t) {
                    synchronized (errors) { errors.add(t); }
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown();
        doneLatch.await();
        pool.shutdown();

        assertTrue(errors.isEmpty(), "No thread should throw an unexpected exception: " + errors);
        assertEquals(threads, correct.get(),
                "Every thread must produce the correct result under concurrent load");
    }

    // -------------------------------------------------------------------------
    // Helpers — invoke private static helpers via reflection
    // -------------------------------------------------------------------------

    private static int clampLimit(int value) throws Exception {
        Method m = MatchingController.class.getDeclaredMethod("clampLimit", int.class);
        m.setAccessible(true);
        return (int) m.invoke(null, value);
    }

    private static int clampOffset(int value) throws Exception {
        Method m = MatchingController.class.getDeclaredMethod("clampOffset", int.class);
        m.setAccessible(true);
        return (int) m.invoke(null, value);
    }
}
