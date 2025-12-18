package com.example.dating.controllers;

import com.example.dating.exceptions.*;
import com.example.dating.models.auth.*;
import com.example.dating.models.user.common.dto.UserDtoRequest;
import com.example.dating.models.user.common.dto.UserDtoResponse;
import com.example.dating.services.AuthService;
import com.example.dating.services.JwtService;
import com.example.dating.services.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static com.example.dating.constants.AppConstants.BASE_API_ROUTE;

@Slf4j
@RestController()
@RequestMapping(BASE_API_ROUTE + "/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final AuthService authService;
    private final JwtService jwtService;

    // Existing Spotify login endpoint
    @PostMapping("/spotify-login")
    public ResponseEntity<UserDtoResponse> handleSpotifyLogin(@RequestBody UserDtoRequest userDto) {
        try {
            UserDtoResponse user = userService.findOrCreateUser(userDto);
            return ResponseEntity.ok(user);
        } catch (IllegalArgumentException | OptimisticLockingFailureException | DataIntegrityViolationException e) {
            log.error("Invalid user data: ", e);
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Error during login: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // NEW: Email/password registration
    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequestDto request) {
        try {
            AuthResponseDto response = authService.register(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (EmailAlreadyExistsException e) {
            log.warn("Registration failed - email exists: {}", request.getEmail());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Registration error: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Registration failed"));
        }
    }

    // NEW: Email/password login
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequestDto request) {
        try {
            AuthResponseDto response = authService.login(request);
            return ResponseEntity.ok(response);
        } catch (InvalidCredentialsException e) {
            log.warn("Login failed for email: {}", request.getEmail());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", e.getMessage()));
        } catch (EmailNotVerifiedException e) {
            log.warn("Login attempt with unverified email: {}", request.getEmail());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Login error: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Login failed"));
        }
    }

    // NEW: Email verification
    @PostMapping("/verify-email")
    public ResponseEntity<?> verifyEmail(@Valid @RequestBody EmailVerificationRequestDto request) {
        try {
            authService.verifyEmail(request.getToken());
            return ResponseEntity.ok(Map.of("message", "Email verified successfully"));
        } catch (InvalidTokenException e) {
            log.warn("Email verification failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Email verification error: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Verification failed"));
        }
    }

    // NEW: Resend verification email
    @PostMapping("/resend-verification")
    public ResponseEntity<?> resendVerification(@Valid @RequestBody ForgotPasswordRequestDto request) {
        try {
            authService.resendVerificationEmail(request.getEmail());
            return ResponseEntity.ok(Map.of("message", "Verification email sent"));
        } catch (UserNotFoundException e) {
            // Don't reveal if user exists
            return ResponseEntity.ok(Map.of("message", "If the email exists, verification email will be sent"));
        } catch (Exception e) {
            log.error("Resend verification error: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to send verification email"));
        }
    }

    // NEW: Forgot password
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@Valid @RequestBody ForgotPasswordRequestDto request) {
        try {
            authService.forgotPassword(request.getEmail());
            // Always return success (don't reveal if email exists)
            return ResponseEntity.ok(Map.of("message", "If the email exists, a password reset link will be sent"));
        } catch (Exception e) {
            log.error("Forgot password error: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to process request"));
        }
    }

    // NEW: Reset password
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@Valid @RequestBody ResetPasswordRequestDto request) {
        try {
            authService.resetPassword(request);
            return ResponseEntity.ok(Map.of("message", "Password reset successfully"));
        } catch (InvalidTokenException e) {
            log.warn("Password reset failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Password reset error: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Password reset failed"));
        }
    }

    // NEW: Connect Spotify (for email users)
    @PostMapping("/connect-spotify")
    public ResponseEntity<?> connectSpotify(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody ConnectSpotifyRequestDto request) {
        try {
            String jwt = authHeader.replace("Bearer ", "");
            String userId = jwtService.getUserIdFromToken(jwt);
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Invalid token"));
            }

            AuthResponseDto response = authService.connectSpotify(userId, request);
            return ResponseEntity.ok(response);
        } catch (UserNotFoundException e) {
            log.warn("Connect Spotify failed - user not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "User not found"));
        } catch (SpotifyAlreadyConnectedException e) {
            log.warn("Spotify already connected");
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            log.warn("Connect Spotify failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Connect Spotify error: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to connect Spotify"));
        }
    }
}
