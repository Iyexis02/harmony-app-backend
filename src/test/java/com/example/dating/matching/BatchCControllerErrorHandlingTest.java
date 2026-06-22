package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.enums.user.AuthProvider;
import com.example.dating.mappers.UserMapper;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.JwtService;
import com.example.dating.services.matching.SpotifyGenreSyncService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Batch C — Unify Controller Error Handling Pattern
 *
 * Verifies that:
 *   1. AccountLockedException propagates to GlobalExceptionHandler → 429 with body (not swallowed as 500)
 *   2. Spotify sync exception does not leak e.getMessage() in the response body
 *   3. Self-score returns 400 with an error body (not an empty 400)
 *   4. Invalid matches status param returns 400 with an error body (not an empty 400)
 *   5. Deleted user JWT → UserController returns 404 with body (not empty 404)
 */
@SpringBootTest(classes = DatingApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BatchCControllerErrorHandlingTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * Replace the real SpotifyGenreSyncService so test 2 can throw a controlled exception
     * without making actual HTTP calls to Spotify.
     */
    @MockitoBean
    private SpotifyGenreSyncService spotifyGenreSyncService;

    /** IDs of users created during each test; deleted in @AfterEach. */
    private final List<String> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        createdUserIds.forEach(id -> {
            try {
                userJpaRepository.deleteById(id);
            } catch (Exception ignored) {
                // already deleted by the test itself (e.g. test 5)
            }
        });
        createdUserIds.clear();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1 — AccountLockedException must reach GlobalExceptionHandler → 429
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Batch C – login with locked account returns 429 with error body (not 500)")
    void login_lockedAccount_returns429WithErrorBody() throws Exception {
        User lockedUser = saveUser(User.builder()
                .passwordHash(passwordEncoder.encode("Test123!"))
                .lockedUntil(LocalDateTime.now().plusMinutes(15))
                .failedLoginAttempts(5));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + lockedUser.getEmail() + "\",\"password\":\"wrong\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("Account temporarily locked. Try again later."));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2 — Spotify sync exception must not leak e.getMessage() in response
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Batch C – Spotify sync exception returns generic 500 without internal message")
    void syncSpotify_serviceException_returnsGenericMessageWithNoLeak() throws Exception {
        User user = saveUser(User.builder()
                .spotifyAccessToken("encrypted-access-token-placeholder"));

        when(spotifyGenreSyncService.syncUserGenrePreferences(any(), any()))
                .thenThrow(new RuntimeException("INTERNAL_SECRET: spotify_token=abc123&client_id=xyz"));

        String token = jwtService.generateToken(user);

        mockMvc.perform(post("/api/v1/preferences/genres/sync")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("An unexpected error occurred"))
                .andExpect(content().string(not(containsString("INTERNAL_SECRET"))))
                .andExpect(content().string(not(containsString("spotify_token"))));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3 — Self-score must return 400 WITH a body (not empty 400)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Batch C – self-score returns 400 with error body (not empty 400)")
    void getMatchScore_selfScore_returns400WithBody() throws Exception {
        User user = saveUser(User.builder());
        String token = jwtService.generateToken(user);

        mockMvc.perform(get("/api/v1/matching/score/" + user.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Cannot calculate score with yourself"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4 — Invalid matches status must return 400 WITH a body (not empty 400)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Batch C – invalid matches status returns 400 with error body (not empty 400)")
    void getMatches_invalidStatus_returns400WithBody() throws Exception {
        User user = saveUser(User.builder());
        String token = jwtService.generateToken(user);

        mockMvc.perform(get("/api/v1/matching/matches?status=invalid")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid status parameter. Allowed values: active, all"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 5 — Deleted user JWT → UserController returns 404 WITH a body (not empty 404)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Batch C – deleted user JWT returns 404 with error body from UserController (not empty 404)")
    void getUserProfile_deletedUserJwt_returns404WithBody() throws Exception {
        User user = saveUser(User.builder());
        String token = jwtService.generateToken(user);

        // Delete the user; the JWT remains structurally valid so JwtDecoder
        // still accepts it (it only rejects tokens whose version mismatches,
        // and skips the version check when the user row is absent).
        userJpaRepository.deleteById(user.getId());

        mockMvc.perform(get("/api/v1/user")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("User not found"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Saves a user with test-safe defaults applied on top of any caller-supplied fields.
     * The returned User has its DB-assigned id populated and is registered for @AfterEach cleanup.
     */
    private User saveUser(User.UserBuilder builder) {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        User user = builder
                .email("batchc-" + tag + "@test.invalid")
                .authProvider(AuthProvider.EMAIL)
                .emailVerified(true)
                .tokenVersion(0)
                .build();
        UserEntity savedEntity = userJpaRepository.save(userMapper.toEntity(user));
        User saved = userMapper.toDomain(savedEntity);
        createdUserIds.add(saved.getId());
        return saved;
    }
}
