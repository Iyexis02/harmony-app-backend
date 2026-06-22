package com.example.dating.exceptions;

import com.example.dating.models.common.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Batch D — Standardize Error Response Format.
 *
 * Verifies:
 * 1. Every @ExceptionHandler returns ErrorResponse with code, message, timestamp.
 * 2. Only validation errors carry a non-null fields map.
 * 3. HTTP status codes are identical to pre-Batch D values.
 * 4. The four handlers that all returned 409 CONFLICT now carry distinct codes.
 * 5. Concurrent requests each receive a structurally valid, independent ErrorResponse.
 *
 * Uses standalone MockMvc — no Spring context, no database required.
 */
class BatchDErrorResponseTest {

    private MockMvc mockMvc;
    // findAndRegisterModules registers JavaTimeModule so Instant round-trips correctly
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new BatchDTestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ── 1. Structural: every error carries code, message, timestamp ──────────

    @Test
    @DisplayName("Every error response contains code, message, and timestamp")
    void everyErrorResponse_hasRequiredFields() throws Exception {
        mockMvc.perform(get("/batchd/illegal-argument"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_ARGUMENT))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("Non-validation errors have null fields (no spurious fields map)")
    void nonValidationErrors_fieldsIsNull() throws Exception {
        String body = mockMvc.perform(get("/batchd/illegal-argument"))
                .andReturn().getResponse().getContentAsString();
        ErrorResponse response = objectMapper.readValue(body, ErrorResponse.class);
        assertThat(response.fields()).isNull();
    }

    // ── 2. Validation errors carry VALIDATION_ERROR code + fields map ────────

    @Test
    @DisplayName("Validation error has VALIDATION_ERROR code and fields map")
    void validationError_hasCodeAndFieldsMap() throws Exception {
        mockMvc.perform(post("/batchd/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fields.name").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    // ── 3. HTTP status codes unchanged ───────────────────────────────────────

    @Test
    @DisplayName("OptimisticLockingFailureException still returns 409")
    void optimisticLocking_returns409() throws Exception {
        mockMvc.perform(get("/batchd/optimistic-lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.CONCURRENT_MODIFICATION));
    }

    @Test
    @DisplayName("SpotifyTokenRevokedException still returns 401")
    void spotifyTokenRevoked_returns401() throws Exception {
        mockMvc.perform(get("/batchd/spotify-token-revoked"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.SPOTIFY_TOKEN_EXPIRED));
    }

    @Test
    @DisplayName("SpotifyApiException still returns 502")
    void spotifyApi_returns502() throws Exception {
        mockMvc.perform(get("/batchd/spotify-api"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(ErrorCode.SPOTIFY_UNAVAILABLE));
    }

    @Test
    @DisplayName("UserNotFoundException still returns 404")
    void userNotFound_returns404() throws Exception {
        mockMvc.perform(get("/batchd/user-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("AccountLockedException still returns 429")
    void accountLocked_returns429() throws Exception {
        mockMvc.perform(get("/batchd/account-locked"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCOUNT_LOCKED));
    }

    @Test
    @DisplayName("UnauthorizedMatchAccessException still returns 403")
    void unauthorizedMatchAccess_returns403() throws Exception {
        mockMvc.perform(get("/batchd/unauthorized-match"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("Generic Exception still returns 500")
    void genericException_returns500() throws Exception {
        mockMvc.perform(get("/batchd/generic"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(ErrorCode.INTERNAL_ERROR));
    }

    // ── 4. The previously-indistinguishable 409 responses now have distinct codes

    @Test
    @DisplayName("Four 409-returning handlers each have a distinct code")
    void fourConflicts_haveDistinctCodes() throws Exception {
        String optimisticBody = mockMvc.perform(get("/batchd/optimistic-lock"))
                .andReturn().getResponse().getContentAsString();
        String duplicateSwipeBody = mockMvc.perform(get("/batchd/duplicate-swipe"))
                .andReturn().getResponse().getContentAsString();
        String emailExistsBody = mockMvc.perform(get("/batchd/email-exists"))
                .andReturn().getResponse().getContentAsString();
        String dataIntegrityBody = mockMvc.perform(get("/batchd/data-integrity"))
                .andReturn().getResponse().getContentAsString();

        ErrorResponse r1 = objectMapper.readValue(optimisticBody, ErrorResponse.class);
        ErrorResponse r2 = objectMapper.readValue(duplicateSwipeBody, ErrorResponse.class);
        ErrorResponse r3 = objectMapper.readValue(emailExistsBody, ErrorResponse.class);
        ErrorResponse r4 = objectMapper.readValue(dataIntegrityBody, ErrorResponse.class);

        assertThat(r1.code()).isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);
        assertThat(r2.code()).isEqualTo(ErrorCode.DUPLICATE_SWIPE);
        assertThat(r3.code()).isEqualTo(ErrorCode.EMAIL_EXISTS);
        assertThat(r4.code()).isEqualTo(ErrorCode.CONFLICT);

        // All four are distinct — a Set of size 4 proves no two are equal
        assertThat(Set.of(r1.code(), r2.code(), r3.code(), r4.code())).hasSize(4);
    }

    // ── 5. Concurrent: 20 threads send simultaneous error requests ───────────

    @Test
    @DisplayName("Concurrent error responses are each structurally valid and independent")
    void concurrent_allResponsesAreStructurallyValid() throws Exception {
        int threadCount = 20;
        String[] paths = {
                "/batchd/illegal-argument",
                "/batchd/illegal-state",
                "/batchd/optimistic-lock",
                "/batchd/duplicate-swipe",
                "/batchd/email-exists",
                "/batchd/spotify-token-revoked",
                "/batchd/spotify-api",
                "/batchd/user-not-found",
                "/batchd/account-locked",
                "/batchd/unauthorized-match",
        };

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<String>> futures = new ArrayList<>();
        AtomicInteger unexpectedErrors = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            final String path = paths[i % paths.length];
            futures.add(pool.submit(() -> {
                try {
                    return mockMvc.perform(get(path))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
                } catch (Exception e) {
                    unexpectedErrors.incrementAndGet();
                    return null;
                }
            }));
        }

        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(unexpectedErrors.get()).isZero();

        for (Future<String> future : futures) {
            String body = future.get();
            assertThat(body).isNotNull();
            ErrorResponse response = objectMapper.readValue(body, ErrorResponse.class);
            // Every response independently has all required fields
            assertThat(response.code()).isNotBlank();
            assertThat(response.message()).isNotBlank();
            assertThat(response.timestamp()).isNotNull();
        }
    }

    // ── Minimal controller that triggers each exception type ─────────────────

    @RestController
    static class BatchDTestController {

        @GetMapping("/batchd/illegal-argument")
        String illegalArgument() { throw new IllegalArgumentException("bad arg"); }

        @GetMapping("/batchd/illegal-state")
        String illegalState() { throw new IllegalStateException("bad state"); }

        @GetMapping("/batchd/optimistic-lock")
        String optimisticLock() { throw new OptimisticLockingFailureException("stale"); }

        @GetMapping("/batchd/duplicate-swipe")
        String duplicateSwipe() { throw new DuplicateSwipeException("user-a", "user-b"); }

        @GetMapping("/batchd/email-exists")
        String emailExists() { throw new EmailAlreadyExistsException("email taken"); }

        @GetMapping("/batchd/spotify-token-revoked")
        String spotifyTokenRevoked() { throw new SpotifyTokenRevokedException("revoked"); }

        @GetMapping("/batchd/spotify-api")
        String spotifyApi() { throw new SpotifyApiException("down"); }

        @GetMapping("/batchd/user-not-found")
        String userNotFound() { throw new UserNotFoundException("not found"); }

        @GetMapping("/batchd/account-locked")
        String accountLocked() { throw new AccountLockedException("locked"); }

        @GetMapping("/batchd/unauthorized-match")
        String unauthorizedMatch() { throw new UnauthorizedMatchAccessException("forbidden"); }

        @GetMapping("/batchd/data-integrity")
        String dataIntegrity() { throw new DataIntegrityViolationException("conflict"); }

        @GetMapping("/batchd/generic")
        String generic() { throw new RuntimeException("unexpected"); }

        @PostMapping("/batchd/body")
        String body(@RequestBody @Valid BodyDto dto) { return "ok"; }

        record BodyDto(@NotBlank String name) {}
    }
}
