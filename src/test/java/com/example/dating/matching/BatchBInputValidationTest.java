package com.example.dating.matching;

import com.example.dating.models.auth.ConnectSpotifyRequestDto;
import com.example.dating.models.matching.dto.SwipeRequestDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Batch B — Input Validation Hardening
 *
 * Verifies that constraint annotations on ConnectSpotifyRequestDto and SwipeRequestDto
 * are wired correctly and that the validator is thread-safe under concurrent load.
 *
 * Does not require a Spring context or database — uses the Jakarta Bean Validation
 * reference implementation (Hibernate Validator) directly.
 */
class BatchBInputValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDownValidator() {
        factory.close();
    }

    // -------------------------------------------------------------------------
    // ConnectSpotifyRequestDto — null fields
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("ConnectSpotifyRequestDto: all-null body produces four violations")
    void connectSpotify_allNull_producesViolations() {
        ConnectSpotifyRequestDto dto = new ConnectSpotifyRequestDto(null, null, null, null);

        Set<ConstraintViolation<ConnectSpotifyRequestDto>> violations = validator.validate(dto);

        Set<String> fields = violatedFields(violations);
        assertTrue(fields.contains("spotifyId"),             "spotifyId must be @NotBlank");
        assertTrue(fields.contains("spotifyAccessToken"),    "spotifyAccessToken must be @NotBlank");
        assertTrue(fields.contains("spotifyRefreshToken"),   "spotifyRefreshToken must be @NotBlank");
        assertTrue(fields.contains("spotifyTokenExpiresAt"), "spotifyTokenExpiresAt must be @NotNull");
    }

    @Test
    @DisplayName("ConnectSpotifyRequestDto: blank strings produce violations")
    void connectSpotify_blankStrings_producesViolations() {
        ConnectSpotifyRequestDto dto = new ConnectSpotifyRequestDto("  ", "  ", "  ", 1L);

        Set<ConstraintViolation<ConnectSpotifyRequestDto>> violations = validator.validate(dto);

        Set<String> fields = violatedFields(violations);
        assertTrue(fields.contains("spotifyId"),           "blank spotifyId must fail @NotBlank");
        assertTrue(fields.contains("spotifyAccessToken"),  "blank spotifyAccessToken must fail @NotBlank");
        assertTrue(fields.contains("spotifyRefreshToken"), "blank spotifyRefreshToken must fail @NotBlank");
    }

    @Test
    @DisplayName("ConnectSpotifyRequestDto: oversized spotifyId (256 chars) produces violation")
    void connectSpotify_oversizedSpotifyId_producesViolation() {
        String oversized = "x".repeat(256); // limit is 255
        ConnectSpotifyRequestDto dto = new ConnectSpotifyRequestDto(
                oversized, "accessToken", "refreshToken", 1L);

        Set<ConstraintViolation<ConnectSpotifyRequestDto>> violations = validator.validate(dto);

        Set<String> fields = violatedFields(violations);
        assertTrue(fields.contains("spotifyId"), "256-char spotifyId must fail @Size(max=255)");
    }

    @Test
    @DisplayName("ConnectSpotifyRequestDto: oversized access token (2049 chars) produces violation")
    void connectSpotify_oversizedAccessToken_producesViolation() {
        String oversized = "a".repeat(2049); // limit is 2048
        ConnectSpotifyRequestDto dto = new ConnectSpotifyRequestDto(
                "spotify123", oversized, "refreshToken", 1L);

        Set<ConstraintViolation<ConnectSpotifyRequestDto>> violations = validator.validate(dto);

        Set<String> fields = violatedFields(violations);
        assertTrue(fields.contains("spotifyAccessToken"),
                "2049-char access token must fail @Size(max=2048)");
    }

    @Test
    @DisplayName("ConnectSpotifyRequestDto: oversized refresh token (2049 chars) produces violation")
    void connectSpotify_oversizedRefreshToken_producesViolation() {
        String oversized = "r".repeat(2049);
        ConnectSpotifyRequestDto dto = new ConnectSpotifyRequestDto(
                "spotify123", "accessToken", oversized, 1L);

        Set<ConstraintViolation<ConnectSpotifyRequestDto>> violations = validator.validate(dto);

        Set<String> fields = violatedFields(violations);
        assertTrue(fields.contains("spotifyRefreshToken"),
                "2049-char refresh token must fail @Size(max=2048)");
    }

    @Test
    @DisplayName("ConnectSpotifyRequestDto: valid payload passes all constraints")
    void connectSpotify_validPayload_noViolations() {
        ConnectSpotifyRequestDto dto = new ConnectSpotifyRequestDto(
                "spotify123", "accessToken", "refreshToken", System.currentTimeMillis() / 1000);

        Set<ConstraintViolation<ConnectSpotifyRequestDto>> violations = validator.validate(dto);

        assertTrue(violations.isEmpty(), "Valid payload must produce no violations");
    }

    // -------------------------------------------------------------------------
    // SwipeRequestDto — action
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("SwipeRequestDto: null action produces @NotBlank violation")
    void swipe_nullAction_producesViolation() {
        SwipeRequestDto dto = swipeDto(null, null);

        Set<ConstraintViolation<SwipeRequestDto>> violations = validator.validate(dto);

        Set<String> fields = violatedFields(violations);
        assertTrue(fields.contains("action"), "null action must fail @NotBlank");
    }

    @Test
    @DisplayName("SwipeRequestDto: invalid action string produces @Pattern violation")
    void swipe_invalidAction_producesViolation() {
        for (String bad : List.of("invalid", "LIKE", "Like", "dislike", "swipe", " like", "like ")) {
            SwipeRequestDto dto = swipeDto(bad, null);

            Set<ConstraintViolation<SwipeRequestDto>> violations = validator.validate(dto);

            Set<String> fields = violatedFields(violations);
            assertTrue(fields.contains("action"),
                    "Action '" + bad + "' must fail @Pattern");
        }
    }

    @Test
    @DisplayName("SwipeRequestDto: all four valid actions pass @Pattern")
    void swipe_validActions_noViolations() {
        for (String action : List.of("like", "pass", "super_like", "block")) {
            SwipeRequestDto dto = swipeDto(action, null);

            Set<ConstraintViolation<SwipeRequestDto>> violations = validator.validate(dto);

            Set<String> fields = violatedFields(violations);
            assertFalse(fields.contains("action"),
                    "Action '" + action + "' must pass @Pattern");
        }
    }

    // -------------------------------------------------------------------------
    // SwipeRequestDto — platform
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("SwipeRequestDto: platform at exactly 50 chars passes")
    void swipe_platform50chars_passes() {
        SwipeRequestDto dto = swipeDto("like", "x".repeat(50));

        Set<ConstraintViolation<SwipeRequestDto>> violations = validator.validate(dto);

        assertFalse(violatedFields(violations).contains("platform"),
                "50-char platform must pass @Size(max=50)");
    }

    @Test
    @DisplayName("SwipeRequestDto: platform at 51 chars produces @Size violation")
    void swipe_platform51chars_producesViolation() {
        SwipeRequestDto dto = swipeDto("like", "x".repeat(51));

        Set<ConstraintViolation<SwipeRequestDto>> violations = validator.validate(dto);

        assertTrue(violatedFields(violations).contains("platform"),
                "51-char platform must fail @Size(max=50)");
    }

    @Test
    @DisplayName("SwipeRequestDto: null platform is allowed (optional field)")
    void swipe_nullPlatform_passes() {
        SwipeRequestDto dto = swipeDto("like", null);

        Set<ConstraintViolation<SwipeRequestDto>> violations = validator.validate(dto);

        assertFalse(violatedFields(violations).contains("platform"),
                "null platform must pass (field is optional)");
    }

    // -------------------------------------------------------------------------
    // Concurrent validation — thread-safety of the shared Validator instance
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Concurrent: 20 threads simultaneously validating malicious payloads — all reject")
    void concurrent_invalidPayloads_allRejected() throws Exception {
        int threads = 20;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger rejectCount = new AtomicInteger(0);
        List<Throwable> errors = new ArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int threadIndex = i;
            futures.add(pool.submit(() -> {
                try {
                    startGate.await(); // wait for all threads to be ready

                    // Alternate between the two DTOs so both code paths are hit concurrently
                    if (threadIndex % 2 == 0) {
                        // Null ConnectSpotifyRequestDto — must produce >= 4 violations
                        ConnectSpotifyRequestDto dto =
                                new ConnectSpotifyRequestDto(null, null, null, null);
                        Set<ConstraintViolation<ConnectSpotifyRequestDto>> v = validator.validate(dto);
                        if (v.size() >= 4) rejectCount.incrementAndGet();

                    } else {
                        // Invalid action SwipeRequestDto — must produce a violation
                        SwipeRequestDto dto = swipeDto("inject_me", "x".repeat(100));
                        Set<ConstraintViolation<SwipeRequestDto>> v = validator.validate(dto);
                        boolean actionViolated = v.stream().anyMatch(cv ->
                                "action".equals(cv.getPropertyPath().toString()));
                        boolean platformViolated = v.stream().anyMatch(cv ->
                                "platform".equals(cv.getPropertyPath().toString()));
                        if (actionViolated && platformViolated) rejectCount.incrementAndGet();
                    }
                } catch (Throwable t) {
                    synchronized (errors) { errors.add(t); }
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        startGate.countDown(); // release all threads simultaneously
        doneLatch.await();
        pool.shutdown();

        assertTrue(errors.isEmpty(),
                "No thread should throw an unexpected exception: " + errors);
        assertEquals(threads, rejectCount.get(),
                "Every thread must have detected a constraint violation");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static SwipeRequestDto swipeDto(String action, String platform) {
        SwipeRequestDto dto = new SwipeRequestDto();
        dto.setSwipedUserId("some-user-id");
        dto.setAction(action);
        dto.setPlatform(platform);
        return dto;
    }

    private static <T> Set<String> violatedFields(Set<ConstraintViolation<T>> violations) {
        return violations.stream()
                .map(cv -> cv.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }
}
