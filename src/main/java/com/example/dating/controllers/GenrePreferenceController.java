package com.example.dating.controllers;

import com.example.dating.exceptions.UserNotFoundException;
import com.example.dating.models.user.domain.User;
import com.example.dating.models.matching.dao.UserGenrePreference;
import com.example.dating.mappers.UserMapper;
import com.example.dating.models.preferences.dto.AddGenrePreferenceRequestDto;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.SpotifyTokenService;
import com.example.dating.services.matching.GenreExtractionService;
import com.example.dating.services.matching.SpotifyGenreSyncService;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API endpoints for managing user genre preferences
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "Genre Preferences", description = "Music genre preference CRUD and Spotify sync")
@RestController
@RequestMapping("/api/v1/preferences/genres")
@RequiredArgsConstructor
@Slf4j
public class GenrePreferenceController {

    private final SpotifyGenreSyncService spotifyGenreSyncService;
    private final GenreExtractionService genreExtractionService;
    private final UserJpaRepository userJpaRepository;
    private final UserMapper userMapper;
    private final SpotifyTokenService spotifyTokenService;

    /**
     * Get current user's genre preferences
     * GET /api/v1/preferences/genres
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getMyGenrePreferences(
            Authentication authentication,
            @RequestParam(defaultValue = "20") int limit
    ) {
        User user = getCurrentUser(authentication);
        List<UserGenrePreference> preferences = genreExtractionService.getTopGenres(user, limit);

        Map<String, Object> response = new HashMap<>();
        response.put("total", preferences.size());
        response.put("preferences", preferences.stream().map(this::toDto).toList());

        return ResponseEntity.ok(response);
    }

    /**
     * Sync genre preferences from Spotify
     * POST /api/v1/preferences/genres/sync
     */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncFromSpotify(
            Authentication authentication,
            @RequestParam(defaultValue = "false") boolean quick
    ) throws JsonProcessingException {
        User user = getCurrentUser(authentication);

        if (user.getSpotifyAccessToken() == null || user.getSpotifyAccessToken().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "No Spotify account connected"));
        }

        String accessToken = spotifyTokenService.getValidSpotifyToken(user);
        int genreCount;
        if (quick) {
            genreCount = spotifyGenreSyncService.quickSyncUserGenrePreferences(user, accessToken);
        } else {
            genreCount = spotifyGenreSyncService.syncUserGenrePreferences(user, accessToken);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Genre preferences synced successfully");
        response.put("genreCount", genreCount);

        return ResponseEntity.ok(response);
    }

    /**
     * Add a manual genre preference
     * POST /api/v1/preferences/genres
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> addManualPreference(
            Authentication authentication,
            @Valid @RequestBody AddGenrePreferenceRequestDto request
    ) {
        User user = getCurrentUser(authentication);

        Double weight = request.getWeight() != null ? request.getWeight() : 1.0;
        UserGenrePreference preference = genreExtractionService.addManualPreference(
                user,
                request.getGenreName(),
                weight
        );

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of(
                        "success", true,
                        "message", "Genre preference added",
                        "preference", toDto(preference)
                ));
    }

    /**
     * Clear all Spotify-derived preferences
     * DELETE /api/v1/preferences/genres/spotify
     *
     * Declared before /{genreName} to make the literal-path priority explicit,
     * though Spring MVC already resolves exact paths before path-variable patterns.
     */
    @DeleteMapping("/spotify")
    public ResponseEntity<Map<String, Object>> clearSpotifyPreferences(Authentication authentication) {
        User user = getCurrentUser(authentication);
        genreExtractionService.clearSpotifyPreferences(user);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Spotify preferences cleared"
        ));
    }

    /**
     * Remove a genre preference
     * DELETE /api/v1/preferences/genres/{genreName}
     */
    @DeleteMapping("/{genreName}")
    public ResponseEntity<Map<String, Object>> removePreference(
            Authentication authentication,
            @PathVariable String genreName
    ) {
        User user = getCurrentUser(authentication);
        genreExtractionService.removePreference(user, genreName);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Genre preference removed"
        ));
    }

    /**
     * Get current authenticated user
     */
    private User getCurrentUser(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        String userId = jwt.getClaimAsString("userId");
        return userJpaRepository.findById(userId)
                .map(userMapper::toDomain)
                .orElseThrow(() -> new UserNotFoundException("User not found"));
    }

    /**
     * Convert UserGenrePreference to DTO
     */
    private Map<String, Object> toDto(UserGenrePreference pref) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("genreName", pref.getGenre().getName());
        dto.put("genreDisplayName", pref.getGenre().getDisplayName());
        dto.put("weight", pref.getWeight());
        dto.put("confidence", pref.getConfidence());
        dto.put("source", pref.getSource());
        dto.put("rank", pref.getRank());
        dto.put("updatedAt", pref.getUpdatedAt());
        dto.put("createdAt", pref.getCreatedAt());
        return dto;
    }
}
