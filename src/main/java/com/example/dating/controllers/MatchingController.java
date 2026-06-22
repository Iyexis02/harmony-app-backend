package com.example.dating.controllers;

import com.example.dating.exceptions.BadRequestException;
import com.example.dating.exceptions.UnauthorizedMatchAccessException;
import com.example.dating.exceptions.UserNotFoundException;
import com.example.dating.mappers.MatchDtoMapper;
import com.example.dating.models.matching.dao.Match;
import com.example.dating.models.matching.dto.AnalyticsResponseDto;
import com.example.dating.models.matching.dto.MatchPageResponseDto;
import com.example.dating.models.matching.dto.MatchResponseDto;
import com.example.dating.models.matching.dto.MatchScore;
import com.example.dating.models.matching.dto.PotentialMatchPage;
import com.example.dating.models.matching.dto.SwipeRequestDto;
import com.example.dating.models.matching.dto.SwipeResult;
import com.example.dating.mappers.UserMapper;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.repositories.UserSwipeRepository;
import com.example.dating.services.matching.MatchRecommendationService;
import com.example.dating.services.matching.MatchService;
import com.example.dating.services.matching.SwipeService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Production REST API for matching functionality.
 * Requires JWT authentication.
 * Business logic lives in services and mappers — this class handles HTTP concerns only.
 */
@Tag(name = "Matching", description = "Potential match recommendations, swipe (like/pass/super-like/block), match management, and analytics")
@Slf4j
@RestController
@RequestMapping("/api/v1/matching")
@RequiredArgsConstructor
public class MatchingController {

    private final MatchRecommendationService recommendationService;
    private final SwipeService swipeService;
    private final MatchService matchService;
    private final UserJpaRepository userJpaRepository;
    private final UserMapper userMapper;
    private final MatchDtoMapper matchDtoMapper;
    private final UserSwipeRepository swipeRepository;

    /**
     * GET /api/v1/matching/score/{otherUserId}
     * Calculate music compatibility score with another user.
     */
    @GetMapping("/score/{otherUserId}")
    public ResponseEntity<MatchScore> getMatchScore(
            @PathVariable String otherUserId,
            Authentication authentication) {

        User currentUser = getCurrentUser(authentication);

        // IDOR: prevent self-scoring
        if (currentUser.getId().equals(otherUserId)) {
            throw new BadRequestException("Cannot calculate score with yourself");
        }

        // IDOR: prevent score probing across a block relationship (either direction).
        // Single SELECT EXISTS — replaces a pair of 10k-row list loads + List.contains.
        if (swipeRepository.existsBlockBetween(currentUser.getId(), otherUserId)) {
            throw new UnauthorizedMatchAccessException("Access denied");
        }

        MatchScore matchScore = recommendationService.getMatchScore(currentUser, otherUserId);
        return ResponseEntity.ok(matchScore);
    }

    /**
     * GET /api/v1/matching/potential?limit=20&offset=0&minScore=50&excludeSwiped=true
     * Get paginated match recommendations for the current user.
     */
    @GetMapping("/potential")
    public ResponseEntity<PotentialMatchPage> getPotentialMatches(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "0") double minScore,
            @RequestParam(defaultValue = "true") boolean excludeSwiped,
            Authentication authentication) {

        User currentUser = getCurrentUser(authentication);
        PotentialMatchPage page = recommendationService.findPotentialMatches(
                currentUser, clampLimit(limit), clampOffset(offset), minScore, excludeSwiped);
        return ResponseEntity.ok(page);
    }

    /**
     * POST /api/v1/matching/swipe
     * Body: { "swipedUserId": "uuid", "action": "like|pass", "matchScore": 85.5, "platform": "web|ios|android" }
     */
    @PostMapping("/swipe")
    public ResponseEntity<SwipeResult> recordSwipe(
            @Valid @RequestBody SwipeRequestDto request,
            Authentication authentication) {

        User currentUser = getCurrentUser(authentication);
        String platform = request.getPlatform() != null ? request.getPlatform() : "web";
        SwipeResult result = swipeService.recordSwipe(
                currentUser, request.getSwipedUserId(), request.getAction(), request.getMatchScore(), platform);
        return ResponseEntity.ok(result);
    }

    /**
     * DELETE /api/v1/matching/swipe/{swipedUserId}
     * Undo the current user's most recent swipe on {swipedUserId} so the candidate can resurface.
     * <ul>
     *   <li>200 — swipe removed</li>
     *   <li>404 NOT_FOUND — no swipe to undo</li>
     *   <li>409 CONFLICT — the swipe already resulted in a match (unmatch first)</li>
     * </ul>
     * SwipeNotFoundException (404) and SwipeUndoNotAllowedException (409) propagate to GlobalExceptionHandler.
     */
    @DeleteMapping("/swipe/{swipedUserId}")
    public ResponseEntity<Map<String, String>> undoSwipe(
            @PathVariable String swipedUserId,
            Authentication authentication) {

        User currentUser = getCurrentUser(authentication);
        swipeService.undoSwipe(currentUser, swipedUserId);

        // Invalidate AFTER undoSwipe's transaction has committed. The candidate was excluded at
        // SQL draw-time, so dynamic filtering alone cannot re-add them — a fresh draw is required
        // for prompt resurfacing. Doing this post-commit avoids a concurrent /potential rebuild
        // re-caching the stale exclusion.
        recommendationService.invalidateCandidateCache(currentUser.getId());

        return ResponseEntity.ok(Map.of(
                "message", "Swipe undone",
                "swipedUserId", swipedUserId
        ));
    }

    /**
     * GET /api/v1/matching/matches?status=active&limit=20&offset=0
     * Get paginated matches for the current user.
     */
    @GetMapping("/matches")
    public ResponseEntity<MatchPageResponseDto> getMatches(
            @RequestParam(defaultValue = "active") String status,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset,
            Authentication authentication) {

        if (!"active".equalsIgnoreCase(status) && !"all".equalsIgnoreCase(status)) {
            throw new BadRequestException("Invalid status parameter. Allowed values: active, all");
        }

        User currentUser = getCurrentUser(authentication);
        int safeLimit  = clampLimit(limit);
        int safeOffset = clampOffset(offset);

        Page<Match> page = "active".equalsIgnoreCase(status)
                ? matchService.getActiveMatches(currentUser.getId(), safeLimit, safeOffset)
                : matchService.getAllMatches(currentUser.getId(), safeLimit, safeOffset);

        List<MatchResponseDto> matchDtos = page.getContent().stream()
                .map(match -> matchDtoMapper.toDto(match, currentUser.getId()))
                .toList();

        return ResponseEntity.ok(MatchPageResponseDto.builder()
                .matches(matchDtos)
                .total(page.getTotalElements())
                .limit(safeLimit)
                .offset(safeOffset)
                .hasMore((long) safeOffset + safeLimit < page.getTotalElements())
                .build());
    }

    /**
     * GET /api/v1/matching/analytics
     */
    @GetMapping("/analytics")
    public ResponseEntity<AnalyticsResponseDto> getAnalytics(Authentication authentication) {
        User currentUser = getCurrentUser(authentication);
        return ResponseEntity.ok(swipeService.getAnalytics(currentUser.getId()));
    }

    /**
     * DELETE /api/v1/matching/matches/{matchId}
     * Unmatch — only the participants of the match may do this.
     * MatchNotFoundException (404) and UnauthorizedMatchAccessException (403) propagate to GlobalExceptionHandler.
     */
    @DeleteMapping("/matches/{matchId}")
    public ResponseEntity<Map<String, String>> unmatch(
            @PathVariable String matchId,
            Authentication authentication) {

        User currentUser = getCurrentUser(authentication);
        matchService.unmatch(matchId, currentUser.getId());

        return ResponseEntity.ok(Map.of(
                "message", "Successfully unmatched",
                "matchId", matchId
        ));
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private static int clampLimit(int limit)   { return Math.max(1, Math.min(limit, 100)); }
    private static int clampOffset(int offset) { return Math.max(0, offset); }

    private User getCurrentUser(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        String userId = jwt.getClaimAsString("userId");
        return userJpaRepository.findById(userId)
                .map(userMapper::toDomain)
                .orElseThrow(() -> new UserNotFoundException("User not found"));
    }
}
