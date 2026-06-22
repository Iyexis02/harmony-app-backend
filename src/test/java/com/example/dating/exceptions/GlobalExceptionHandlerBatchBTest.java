package com.example.dating.exceptions;

import com.example.dating.models.common.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Batch B — GlobalExceptionHandler gap coverage.
 *
 * Uses standalone MockMvc: no Spring context, no database.
 * Each test targets exactly one handler added or fixed in Batch B.
 *
 * Updated in Batch D: response body shape changed from Map to ErrorResponse,
 * so all $.error assertions updated to $.message and direct handler call updated to ErrorResponse.
 */
class GlobalExceptionHandlerBatchBTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new BatchBTestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ── 1. IllegalStateException → 500 generic (post-migration) ─────────────
    // After the BadRequestException migration, IllegalStateException is reserved
    // for invariant violations and startup guards — never user input. The handler
    // returns a generic 500 and does NOT echo the exception message (which would
    // leak internal invariant text).

    @Test
    @DisplayName("IllegalStateException returns 500 with generic message (no message echo)")
    void illegalState_returns500Generic() throws Exception {
        mockMvc.perform(get("/batchb/illegal-state"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Internal server error"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Spotify"))));
    }

    // ── 2. MethodArgumentNotValidException with class-level ObjectError ──────

    @Test
    @DisplayName("Class-level validation ObjectError does not throw ClassCastException")
    void validationException_withClassLevelObjectError_doesNotClassCast() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(new Object(), "testRequest");
        // ObjectError (not FieldError) — simulates a class-level @Valid constraint
        bindingResult.addError(new ObjectError("testRequest", "Class-level constraint violated"));

        MethodArgumentNotValidException ex =
                new MethodArgumentNotValidException(null, bindingResult);

        // Must not throw ClassCastException
        ResponseEntity<ErrorResponse> response = handler.handleValidationExceptions(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        Map<String, String> fields = response.getBody().fields();
        // Key is objectName ("testRequest"), not a crash
        assertThat(fields).containsKey("testRequest");
        assertThat(fields.get("testRequest")).isEqualTo("Class-level constraint violated");
    }

    @Test
    @DisplayName("Field-level FieldError still uses field name (regression guard)")
    void validationException_withFieldError_usesFieldName() throws Exception {
        mockMvc.perform(post("/batchb/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fields.name").exists());
    }

    // ── 3. IllegalArgumentException → 400 generic (post-migration) ──────────
    // Legitimate user-facing input errors now use BadRequestException (echoed).
    // IllegalArgumentException is the programming-error sentinel — handler returns
    // 400 generic and intentionally does NOT echo the message.

    @Test
    @DisplayName("IllegalArgumentException returns 400 with generic message (no message echo)")
    void illegalArgument_returns400Generic() throws Exception {
        mockMvc.perform(get("/batchb/illegal-argument"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid request"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("swipe"))));
    }

    @Test
    @DisplayName("BadRequestException returns 400 with echoed message (legitimate user-facing path)")
    void badRequestException_returns400WithMessage() throws Exception {
        mockMvc.perform(get("/batchb/bad-request"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Cannot calculate score with yourself"));
    }

    // ── 4. HttpMessageNotReadableException → 400, safe message ──────────────

    @Test
    @DisplayName("Malformed JSON body returns 400 with fixed safe message")
    void malformedJson_returns400WithSafeMessage() throws Exception {
        mockMvc.perform(post("/batchb/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{bad json}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body"));
    }

    @Test
    @DisplayName("Malformed JSON response does not leak Jackson internals")
    void malformedJson_doesNotLeakInternalDetails() throws Exception {
        String body = mockMvc.perform(post("/batchb/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{bad json"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("JsonParseException");
        assertThat(body).doesNotContain("com.fasterxml");
        assertThat(body).doesNotContain("MismatchedInputException");
    }

    @Test
    @DisplayName("Malformed JSON does not return 500")
    void malformedJson_doesNotReturn500() throws Exception {
        mockMvc.perform(post("/batchb/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-json-at-all"))
                .andExpect(status().is4xxClientError());
    }

    // ── 5. MissingServletRequestParameterException → 400 with param name ────

    @Test
    @DisplayName("Missing required @RequestParam returns 400 with parameter name")
    void missingRequiredParam_returns400WithParamName() throws Exception {
        mockMvc.perform(get("/batchb/with-param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Missing required parameter: query"));
    }

    @Test
    @DisplayName("Missing required @RequestParam does not return 500")
    void missingRequiredParam_doesNotReturn500() throws Exception {
        mockMvc.perform(get("/batchb/with-param"))
                .andExpect(status().is4xxClientError());
    }

    // ── Minimal controller that triggers each exception type ─────────────────

    @RestController
    static class BatchBTestController {

        @GetMapping("/batchb/illegal-state")
        String illegalState() {
            throw new IllegalStateException("User has not connected Spotify");
        }

        @GetMapping("/batchb/illegal-argument")
        String illegalArgument() {
            throw new IllegalArgumentException("Invalid swipe action");
        }

        @GetMapping("/batchb/bad-request")
        String badRequest() {
            throw new BadRequestException("Cannot calculate score with yourself");
        }

        @PostMapping("/batchb/body")
        String body(@RequestBody @Valid BodyDto dto) {
            return "ok";
        }

        @GetMapping("/batchb/with-param")
        String withParam(@RequestParam String query) {
            return query;
        }

        record BodyDto(@NotBlank String name) {}
    }
}
