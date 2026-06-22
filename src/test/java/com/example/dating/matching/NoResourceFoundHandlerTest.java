package com.example.dating.matching;

import com.example.dating.exceptions.GlobalExceptionHandler;
import com.example.dating.models.common.ErrorResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard: unmapped routes must return 404 with the standard envelope, not 500.
 *
 * <p>Spring Boot 3.x throws {@link NoResourceFoundException} when no controller or static
 * resource matches. The {@code @ExceptionHandler(Exception.class)} catch-all previously
 * intercepted it and returned {@code 500 INTERNAL_ERROR} — which is how the (then unmapped)
 * {@code DELETE /api/v1/matching/swipe/{id}} surfaced as a 500. The dedicated handler now maps
 * it to {@code 404 NOT_FOUND}.
 */
class NoResourceFoundHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("NoResourceFoundException → 404 NOT_FOUND with standard envelope (not 500)")
    void noResourceFound_returns404() {
        NoResourceFoundException ex =
                new NoResourceFoundException(HttpMethod.DELETE, "/api/v1/matching/swipe/abc");

        ResponseEntity<ErrorResponse> response = handler.handleNoResourceFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().message()).isEqualTo("The requested resource was not found");
    }
}
