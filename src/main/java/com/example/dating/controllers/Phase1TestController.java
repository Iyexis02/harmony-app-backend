package com.example.dating.controllers;

import com.example.dating.enums.matching.GenrePreferenceSource;
import com.example.dating.enums.matching.MatchSource;
import com.example.dating.enums.matching.MatchStatus;
import com.example.dating.models.matching.dao.CanonicalGenre;
import com.example.dating.models.matching.dao.Match;
import com.example.dating.models.matching.dao.UserGenrePreference;
import com.example.dating.models.matching.dao.UserSwipe;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.repositories.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * TEST CONTROLLER - Phase 1 Manual Testing
 *
 * This controller provides API endpoints to manually test Phase 1 functionality.
 * Use these endpoints to understand how the matching system components work.
 *
 * ⚠️ WARNING: This is for TESTING ONLY. Remove before production!
 *
 * Base URL: http://localhost:8080/api/test/phase1
 */
@Profile("dev")
@RestController
@RequestMapping("/api/test/phase1")
@RequiredArgsConstructor
public class Phase1TestController {

    private final CanonicalGenreRepository genreRepository;
    private final UserGenrePreferenceRepository genrePreferenceRepository;
    private final UserMatchScoreRepository matchScoreRepository;
    private final MatchRepository matchRepository;
    private final UserSwipeRepository swipeRepository;
    private final UserJpaRepository userRepository;

    // ============================================================
    // GENRE ENDPOINTS
    // ============================================================

    /**
     * GET /api/test/phase1/genres
     * List all genres or search by name
     */
    @GetMapping("/genres")
    public ResponseEntity<Map<String, Object>> getGenres(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "false") boolean primaryOnly) {

        List<CanonicalGenre> genres;

        if (search != null && !search.isEmpty()) {
            genres = genreRepository.searchByName(search);
        } else if (primaryOnly) {
            genres = genreRepository.findAllPrimaryGenres();
        } else {
            genres = genreRepository.findAll();
        }

        Map<String, Object> response = new HashMap<>();
        response.put("total", genres.size());
        response.put("genres", genres.stream().map(this::genreToMap).collect(Collectors.toList()));

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/test/phase1/genres/{genreId}
     * Get genre details including children
     */
    @GetMapping("/genres/{genreId}")
    public ResponseEntity<Map<String, Object>> getGenreDetails(@PathVariable String genreId) {
        Optional<CanonicalGenre> genre = genreRepository.findById(genreId);

        if (genre.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        CanonicalGenre g = genre.get();
        List<CanonicalGenre> children = genreRepository.findByParentGenre(g);

        Map<String, Object> response = new HashMap<>();
        response.put("genre", genreToMap(g));
        response.put("children", children.stream().map(this::genreToMap).collect(Collectors.toList()));
        response.put("childCount", children.size());

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/test/phase1/genres/hierarchy
     * Get genre hierarchy tree
     */
    @GetMapping("/genres/hierarchy")
    public ResponseEntity<Map<String, Object>> getGenreHierarchy() {
        List<CanonicalGenre> topLevel = genreRepository.findAllTopLevelGenres();

        List<Map<String, Object>> tree = topLevel.stream()
                .map(parent -> {
                    Map<String, Object> node = genreToMap(parent);
                    List<CanonicalGenre> children = genreRepository.findByParentGenre(parent);
                    node.put("children", children.stream().map(this::genreToMap).collect(Collectors.toList()));
                    return node;
                })
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("topLevelCount", topLevel.size());
        response.put("hierarchy", tree);

        return ResponseEntity.ok(response);
    }

    // ============================================================
    // USER GENRE PREFERENCE ENDPOINTS
    // ============================================================

    /**
     * GET /api/test/phase1/users/{userId}/genres
     * Get user's genre preferences
     */
    @GetMapping("/users/{userId}/genres")
    public ResponseEntity<Map<String, Object>> getUserGenres(@PathVariable String userId) {
        List<UserGenrePreference> prefs =
                genrePreferenceRepository.findByUserIdWithGenreOrderByWeightDesc(userId);

        Map<String, Object> response = new HashMap<>();
        response.put("userId", userId);
        response.put("totalGenres", prefs.size());
        response.put("preferences", prefs.stream().map(this::preferenceToMap).collect(Collectors.toList()));

        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/test/phase1/users/{userId}/genres
     * Add genre preference for user
     *
     * Body: { "genreName": "indie-rock", "weight": 0.8, "source": "manual_selection" }
     */
    @PostMapping("/users/{userId}/genres")
    public ResponseEntity<Map<String, Object>> addUserGenrePreference(
            @PathVariable String userId,
            @RequestBody Map<String, Object> request) {

        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        String genreName = (String) request.get("genreName");
        CanonicalGenre genre = genreRepository.findByName(genreName)
                .orElseThrow(() -> new RuntimeException("Genre not found: " + genreName));

        Double weight = request.get("weight") != null ?
                ((Number) request.get("weight")).doubleValue() : 1.0;
        String sourceStr = (String) request.getOrDefault("source", "manual_selection");
        GenrePreferenceSource source = GenrePreferenceSource.fromValue(sourceStr);

        // Check if preference already exists
        Optional<UserGenrePreference> existing =
                genrePreferenceRepository.findByUserAndGenre(user, genre);

        UserGenrePreference pref;
        if (existing.isPresent()) {
            pref = existing.get();
            pref.setWeight(weight);
            pref.setSource(source);
        } else {
            pref = UserGenrePreference.builder()
                    .user(user)
                    .genre(genre)
                    .weight(weight)
                    .source(source)
                    .confidence(1.0)
                    .build();
        }

        UserGenrePreference saved = genrePreferenceRepository.save(pref);

        Map<String, Object> response = new HashMap<>();
        response.put("message", existing.isPresent() ? "Updated" : "Created");
        response.put("preference", preferenceToMap(saved));

        return ResponseEntity.ok(response);
    }

    /**
     * DELETE /api/test/phase1/users/{userId}/genres/{genreId}
     * Remove genre preference
     */
    @DeleteMapping("/users/{userId}/genres/{genreId}")
    public ResponseEntity<Map<String, Object>> deleteUserGenrePreference(
            @PathVariable String userId,
            @PathVariable String genreId) {

        Optional<UserGenrePreference> pref =
                genrePreferenceRepository.findByUserIdAndGenreId(userId, genreId);

        if (pref.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        genrePreferenceRepository.delete(pref.get());

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Preference deleted successfully");

        return ResponseEntity.ok(response);
    }

    // ============================================================
    // SWIPE ENDPOINTS
    // ============================================================

    /**
     * POST /api/test/phase1/swipe
     * Record a swipe
     *
     * Body: { "swiperId": "user1-id", "swipedId": "user2-id", "action": "like", "score": 85.5 }
     */
    @PostMapping("/swipe")
    public ResponseEntity<Map<String, Object>> recordSwipe(@RequestBody Map<String, Object> request) {
        String swiperId = (String) request.get("swiperId");
        String swipedId = (String) request.get("swipedId");
        String action = (String) request.getOrDefault("action", "like");
        Double score = request.get("score") != null ?
                ((Number) request.get("score")).doubleValue() : null;

        UserEntity swiper = userRepository.findById(swiperId)
                .orElseThrow(() -> new RuntimeException("Swiper user not found"));
        UserEntity swiped = userRepository.findById(swipedId)
                .orElseThrow(() -> new RuntimeException("Swiped user not found"));

        // Check if already swiped
        if (swipeRepository.hasUserSwipedOn(swiperId, swipedId)) {
            Map<String, Object> response = new HashMap<>();
            response.put("error", "User already swiped on this profile");
            return ResponseEntity.badRequest().body(response);
        }

        // Create swipe
        UserSwipe swipe = UserSwipe.builder()
                .swiperUser(swiper)
                .swipedUser(swiped)
                .action(action)
                .matchScoreAtSwipe(score)
                .platform("web")
                .build();

        UserSwipe saved = swipeRepository.save(swipe);

        // Check for mutual match (if action is "like")
        boolean mutualMatch = false;
        Match match = null;

        if ("like".equals(action)) {
            // Check if the swiped user also liked the swiper
            Optional<UserSwipe> reverseSwipe =
                    swipeRepository.findByUserIds(swipedId, swiperId);

            if (reverseSwipe.isPresent() && "like".equals(reverseSwipe.get().getAction())) {
                // Mutual match! Create Match entity
                UserEntity userA = swiperId.compareTo(swipedId) < 0 ? swiper : swiped;
                UserEntity userB = swiperId.compareTo(swipedId) < 0 ? swiped : swiper;

                match = Match.builder()
                        .userA(userA)
                        .userB(userB)
                        .matchScore(score)
                        .status(MatchStatus.ACTIVE)
                        .conversationStarted(false)
                        .matchSource(MatchSource.MUTUAL_SWIPE)
                        .build();

                match = matchRepository.save(match);

                // Update both swipes
                saved.setResultedInMatch(true);
                saved.setMatch(match);
                swipeRepository.save(saved);

                UserSwipe reverseSwipeEntity = reverseSwipe.get();
                reverseSwipeEntity.setResultedInMatch(true);
                reverseSwipeEntity.setMatch(match);
                swipeRepository.save(reverseSwipeEntity);

                mutualMatch = true;
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("swipe", swipeToMap(saved));
        response.put("mutualMatch", mutualMatch);
        if (mutualMatch && match != null) {
            response.put("match", matchToMap(match));
        }

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/test/phase1/users/{userId}/swipes
     * Get user's swipe history
     */
    @GetMapping("/users/{userId}/swipes")
    public ResponseEntity<Map<String, Object>> getUserSwipes(@PathVariable String userId) {
        List<UserSwipe> allSwipes = new java.util.ArrayList<>(
                swipeRepository.findLikesByUserId(userId, PageRequest.of(0, 200)));
        allSwipes.addAll(swipeRepository.findPassesByUserId(userId, PageRequest.of(0, 200)));

        long likes = swipeRepository.countLikesByUserId(userId);
        long passes = swipeRepository.countPassesByUserId(userId);
        Double swipeThroughRate = swipeRepository.calculateSwipeThroughRate(userId);
        Double matchRate = swipeRepository.calculateMatchRate(userId);

        Map<String, Object> response = new HashMap<>();
        response.put("userId", userId);
        response.put("totalSwipes", allSwipes.size());
        response.put("likes", likes);
        response.put("passes", passes);
        response.put("swipeThroughRate", swipeThroughRate != null ? swipeThroughRate * 100 : null);
        response.put("matchRate", matchRate != null ? matchRate * 100 : null);
        response.put("swipes", allSwipes.stream().map(this::swipeToMap).collect(Collectors.toList()));

        return ResponseEntity.ok(response);
    }

    // ============================================================
    // MATCH ENDPOINTS
    // ============================================================

    /**
     * GET /api/test/phase1/users/{userId}/matches
     * Get user's matches
     */
    @GetMapping("/users/{userId}/matches")
    public ResponseEntity<Map<String, Object>> getUserMatches(@PathVariable String userId) {
        List<Match> matches = matchRepository.findActiveMatchesByUserId(userId, PageRequest.of(0, 100)).getContent();
        long withConversation = matchRepository.countMatchesWithConversations(userId);

        Map<String, Object> response = new HashMap<>();
        response.put("userId", userId);
        response.put("totalMatches", matches.size());
        response.put("withConversation", withConversation);
        response.put("withoutConversation", matches.size() - withConversation);
        response.put("matches", matches.stream().map(this::matchToMap).collect(Collectors.toList()));

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/test/phase1/matches/check
     * Check if two users are matched
     */
    @GetMapping("/matches/check")
    public ResponseEntity<Map<String, Object>> checkMatch(
            @RequestParam String user1Id,
            @RequestParam String user2Id) {

        boolean areMatched = matchRepository.areUsersMatched(user1Id, user2Id);
        Optional<Match> match = matchRepository.findMatchBetweenUsers(user1Id, user2Id);

        Map<String, Object> response = new HashMap<>();
        response.put("user1Id", user1Id);
        response.put("user2Id", user2Id);
        response.put("matched", areMatched);
        response.put("match", match.map(this::matchToMap).orElse(null));

        return ResponseEntity.ok(response);
    }

    // ============================================================
    // STATS ENDPOINT
    // ============================================================

    /**
     * GET /api/test/phase1/stats
     * Get overall Phase 1 statistics
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = new HashMap<>();

        // Genre stats
        long totalGenres = genreRepository.count();
        long primaryGenres = genreRepository.findAllPrimaryGenres().size();
        long topLevelGenres = genreRepository.findAllTopLevelGenres().size();

        Map<String, Object> genreStats = new HashMap<>();
        genreStats.put("total", totalGenres);
        genreStats.put("primary", primaryGenres);
        genreStats.put("topLevel", topLevelGenres);
        stats.put("genres", genreStats);

        // User stats
        long totalUsers = userRepository.count();
        long usersWithGenrePrefs = genrePreferenceRepository.count();

        Map<String, Object> userStats = new HashMap<>();
        userStats.put("totalUsers", totalUsers);
        userStats.put("usersWithGenrePreferences", usersWithGenrePrefs);
        stats.put("users", userStats);

        // Swipe stats
        long totalSwipes = swipeRepository.count();

        Map<String, Object> swipeStats = new HashMap<>();
        swipeStats.put("total", totalSwipes);
        stats.put("swipes", swipeStats);

        // Match stats
        long totalMatches = matchRepository.count();

        Map<String, Object> matchStats = new HashMap<>();
        matchStats.put("total", totalMatches);
        stats.put("matches", matchStats);

        return ResponseEntity.ok(stats);
    }

    // ============================================================
    // HELPER METHODS
    // ============================================================

    private Map<String, Object> genreToMap(CanonicalGenre genre) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", genre.getId());
        map.put("name", genre.getName());
        map.put("displayName", genre.getDisplayName());
        map.put("isPrimary", genre.getIsPrimary());
        map.put("parentGenre", genre.getParentGenre() != null ?
                genre.getParentGenre().getDisplayName() : null);
        return map;
    }

    private Map<String, Object> preferenceToMap(UserGenrePreference pref) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", pref.getId());
        map.put("genre", genreToMap(pref.getGenre()));
        map.put("weight", pref.getWeight());
        map.put("source", pref.getSource());
        map.put("confidence", pref.getConfidence());
        map.put("rank", pref.getRank());
        return map;
    }

    private Map<String, Object> swipeToMap(UserSwipe swipe) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", swipe.getId());
        map.put("action", swipe.getAction());
        map.put("swipedAt", swipe.getSwipedAt());
        map.put("matchScore", swipe.getMatchScoreAtSwipe());
        map.put("resultedInMatch", swipe.getResultedInMatch());
        return map;
    }

    private Map<String, Object> matchToMap(Match match) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", match.getId());
        map.put("matchedAt", match.getMatchedAt());
        map.put("matchScore", match.getMatchScore());
        map.put("status", match.getStatus());
        map.put("conversationStarted", match.getConversationStarted());
        map.put("matchSource", match.getMatchSource());
        return map;
    }
}
