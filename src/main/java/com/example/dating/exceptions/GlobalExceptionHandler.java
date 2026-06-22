package com.example.dating.exceptions;

import com.example.dating.models.common.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = (error instanceof FieldError fe) ? fe.getField() : error.getObjectName();
            String errorMessage = error.getDefaultMessage();
            fields.put(fieldName, errorMessage);
        });
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.ofValidation("Validation failed", fields));
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(BadRequestException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(ErrorCode.INVALID_ARGUMENT, ex.getMessage()));
    }

    /**
     * Generic 400 for programming-error sentinel throws. After the migration to
     * {@link BadRequestException} for legitimate user-facing input errors, this
     * handler should rarely fire from request input — the message is logged
     * server-side and intentionally NOT echoed to the client.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("IllegalArgumentException reached the handler — likely a missed migration to BadRequestException", ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(ErrorCode.INVALID_ARGUMENT, "Invalid request"));
    }

    /**
     * 500 for invariant violations. After the migration these come exclusively
     * from internal guards (entity invariants, startup paths) — never from user
     * input. Returning the message would leak internals, so it is logged with
     * full stack server-side and replaced with a generic client message.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex) {
        log.error("IllegalStateException — server-side invariant violated", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR, "Internal server error"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableMessage(HttpMessageNotReadableException ex) {
        log.warn("Malformed request body: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(ErrorCode.INVALID_ARGUMENT, "Malformed request body"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(ErrorCode.INVALID_ARGUMENT, "Missing required parameter: " + ex.getParameterName()));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLocking(OptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(ErrorCode.CONCURRENT_MODIFICATION, "Concurrent modification detected, please retry"));
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleEmailAlreadyExists(EmailAlreadyExistsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(ErrorCode.EMAIL_EXISTS, ex.getMessage()));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of(ErrorCode.UNAUTHORIZED, ex.getMessage()));
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidToken(InvalidTokenException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(ErrorCode.INVALID_TOKEN, ex.getMessage()));
    }

    @ExceptionHandler(EmailNotVerifiedException.class)
    public ResponseEntity<ErrorResponse> handleEmailNotVerified(EmailNotVerifiedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(ErrorCode.FORBIDDEN, ex.getMessage()));
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<ErrorResponse> handleAccountLocked(AccountLockedException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ErrorResponse.of(ErrorCode.ACCOUNT_LOCKED, ex.getMessage()));
    }

    @ExceptionHandler(DuplicateSwipeException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateSwipe(DuplicateSwipeException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(ErrorCode.DUPLICATE_SWIPE, ex.getMessage()));
    }

    @ExceptionHandler(SpotifyAlreadyConnectedException.class)
    public ResponseEntity<ErrorResponse> handleSpotifyAlreadyConnected(SpotifyAlreadyConnectedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(ErrorCode.CONFLICT, ex.getMessage()));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(ErrorCode.NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(MatchNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleMatchNotFound(MatchNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(ErrorCode.NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(SwipeNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleSwipeNotFound(SwipeNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(ErrorCode.NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(SwipeUndoNotAllowedException.class)
    public ResponseEntity<ErrorResponse> handleSwipeUndoNotAllowed(SwipeUndoNotAllowedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(ErrorCode.CONFLICT, ex.getMessage()));
    }

    /**
     * Unmapped routes. Spring Boot 3.x throws {@link NoResourceFoundException} (404 semantics)
     * when no controller and no static resource matches the request. Without this handler the
     * broad {@code @ExceptionHandler(Exception.class)} below would intercept it and return a
     * misleading 500 INTERNAL_ERROR for what is really a 404 — which is exactly how the
     * (previously unmapped) DELETE /matching/swipe/{id} call surfaced as a 500.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex) {
        log.warn("No handler for {} {}", ex.getHttpMethod(), ex.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(ErrorCode.NOT_FOUND, "The requested resource was not found"));
    }

    @ExceptionHandler(UnauthorizedMatchAccessException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorizedMatchAccess(UnauthorizedMatchAccessException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(ErrorCode.FORBIDDEN, ex.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(ErrorCode.CONFLICT, "A conflict occurred. The resource may already exist."));
    }

    @ExceptionHandler(SpotifyTokenRevokedException.class)
    public ResponseEntity<ErrorResponse> handleSpotifyTokenRevoked(SpotifyTokenRevokedException ex) {
        log.warn("Spotify token revoked: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of(ErrorCode.SPOTIFY_TOKEN_EXPIRED, "Spotify connection expired. Please reconnect."));
    }

    @ExceptionHandler(SpotifyApiException.class)
    public ResponseEntity<ErrorResponse> handleSpotifyApi(SpotifyApiException ex) {
        log.error("Spotify API error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ErrorResponse.of(ErrorCode.SPOTIFY_UNAVAILABLE, "Spotify service temporarily unavailable"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        log.error("Unexpected error: ", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred"));
    }
}
