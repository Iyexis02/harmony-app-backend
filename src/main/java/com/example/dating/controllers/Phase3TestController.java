package com.example.dating.controllers;

import com.example.dating.models.matching.dao.Match;
import com.example.dating.models.matching.dto.MatchScore;
import com.example.dating.models.matching.dto.PotentialMatch;
import com.example.dating.models.matching.dto.PotentialMatchPage;
import com.example.dating.models.matching.dto.SwipeResult;
import com.example.dating.mappers.UserMapper;
import com.example.dating.models.user.domain.User;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.repositories.UserSwipeRepository;
import com.example.dating.services.matching.MatchRecommendationService;
import com.example.dating.services.matching.MatchService;
import com.example.dating.services.matching.SwipeService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TEST CONTROLLER - Phase 3 Manual Testing
 *
 * This controller provides API endpoints to manually test Phase 3 functionality.
 * No authentication required - for development/testing only.
 *
 * ⚠️ WARNING: Remove or secure before production!
 *
 * Base URL: http://localhost:8080/api/test/phase3
 */
@Profile("dev")
@RestController
@RequestMapping("/api/test/phase3")
@RequiredArgsConstructor
public class Phase3TestController {

    private final MatchRecommendationService recommendationService;
    private final SwipeService swipeService;
    private final MatchService matchService;
    private final UserJpaRepository userJpaRepository;
    private final UserMapper userMapper;
    private final UserSwipeRepository swipeRepository;

    /**
     * GET /api/test/phase3/score?userId1={id1}&userId2={id2}
     * Calculate match score between two users
     */
    @GetMapping("/score")
    public ResponseEntity<MatchScore> getMatchScore(
            @RequestParam String userId1,
            @RequestParam String userId2) {

        User user1 = getUserById(userId1);
        MatchScore matchScore = recommendationService.getMatchScore(user1, userId2);

        return ResponseEntity.ok(matchScore);
    }

    /**
     * GET /api/test/phase3/potential?userId={id}&limit=10&minScore=50
     * Get potential matches for a user
     */
    @GetMapping("/potential")
    public ResponseEntity<Map<String, Object>> getPotentialMatches(
            @RequestParam String userId,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") double minScore,
            @RequestParam(defaultValue = "true") boolean excludeSwiped) {

        User user = getUserById(userId);

        PotentialMatchPage page = recommendationService.findPotentialMatches(
                user, limit, offset, minScore, excludeSwiped);

        Map<String, Object> response = new HashMap<>();
        response.put("userId", userId);
        response.put("matches", page.getMatches());
        response.put("total", page.getTotal());
        response.put("offset", page.getOffset());
        response.put("minScore", minScore);
        response.put("hasMore", page.isHasMore());

        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/test/phase3/swipe?swiperId={id}&swipedId={id}&action=like&score=85.5
     * Record a swipe action
     */
    @PostMapping("/swipe")
    public ResponseEntity<SwipeResult> recordSwipe(
            @RequestParam String swiperId,
            @RequestParam String swipedId,
            @RequestParam String action,
            @RequestParam(required = false) Double score) {

        User swiper = getUserById(swiperId);

        // Calculate score if not provided
        if (score == null) {
            MatchScore matchScore = recommendationService.getMatchScore(swiper, swipedId);
            score = matchScore.getOverallScore();
        }

        SwipeResult result = swipeService.recordSwipe(swiper, swipedId, action, score, "test");

        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/test/phase3/matches?userId={id}
     * Get all matches for a user
     */
    @GetMapping("/matches")
    public ResponseEntity<Map<String, Object>> getMatches(
            @RequestParam String userId) {

        List<Match> matches = matchService.getActiveMatches(userId, 100, 0).getContent();

        Map<String, Object> response = new HashMap<>();
        response.put("userId", userId);
        response.put("matches", matches);
        response.put("total", matches.size());

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/test/phase3/analytics?userId={id}
     * Get analytics for a user
     */
    @GetMapping("/analytics")
    public ResponseEntity<Map<String, Object>> getAnalytics(
            @RequestParam String userId) {

        long totalSwipes = swipeService.getSwipeCount(userId);
        long totalLikes = swipeService.getLikeCount(userId);
        long totalMatches = matchService.getActiveMatchCount(userId);

        Double swipeThroughRate = swipeService.getSwipeThroughRate(userId);
        Double matchRate = totalLikes > 0 ? (double) totalMatches / totalLikes : 0.0;

        Map<String, Object> analytics = new HashMap<>();
        analytics.put("userId", userId);
        analytics.put("totalSwipes", totalSwipes);
        analytics.put("totalLikes", totalLikes);
        analytics.put("totalPasses", totalSwipes - totalLikes);
        analytics.put("totalMatches", totalMatches);
        analytics.put("swipeThroughRate", swipeThroughRate);
        analytics.put("matchRate", matchRate);

        return ResponseEntity.ok(analytics);
    }

    /**
     * POST /api/test/phase3/create-match?userId1={id1}&userId2={id2}&score=85.5
     * Manually create a match (for testing)
     */
    @PostMapping("/create-match")
    public ResponseEntity<Map<String, Object>> createMatch(
            @RequestParam String userId1,
            @RequestParam String userId2,
            @RequestParam(defaultValue = "75.0") double score) {

        // This bypasses the swipe system - for testing only
        // In production, matches are created automatically via SwipeService

        // Get UserEntity objects directly (not domain User)
        UserEntity userEntity1 = userJpaRepository.findById(userId1)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId1));
        UserEntity userEntity2 = userJpaRepository.findById(userId2)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId2));

        Match match = matchService.createMatch(userEntity1, userEntity2, score);

        Map<String, Object> response = new HashMap<>();
        response.put("matchId", match.getId());
        response.put("userAId", match.getUserA().getId());
        response.put("userBId", match.getUserB().getId());
        response.put("matchScore", match.getMatchScore());
        response.put("status", match.getStatus());
        response.put("matchedAt", match.getMatchedAt());

        return ResponseEntity.ok(response);
    }

    /**
     * DELETE /api/test/phase3/clear-swipes?userId={id}
     * Clear all swipes for a user (for testing)
     */
    @DeleteMapping("/clear-swipes")
    public ResponseEntity<Map<String, String>> clearSwipes(
            @RequestParam String userId) {

        swipeRepository.deleteBySwiperId(userId);

        Map<String, String> response = new HashMap<>();
        response.put("message", "Swipes cleared successfully");
        response.put("userId", userId);

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/test/phase3/stats
     * Get overall Phase 3 statistics
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        // Basic stats about the system
        Map<String, Object> stats = new HashMap<>();
        stats.put("message", "Phase 3 is operational");
        stats.put("features", List.of(
                "Match score calculation",
                "Potential match recommendations",
                "Swipe actions",
                "Mutual match detection",
                "Match analytics"
        ));

        return ResponseEntity.ok(stats);
    }

    /**
     * Helper: Get user by ID
     */
    private User getUserById(String userId) {
        return userJpaRepository.findById(userId)
                .map(userMapper::toDomain)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));
    }
}
