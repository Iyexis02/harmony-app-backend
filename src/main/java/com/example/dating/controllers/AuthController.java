package com.example.dating.controllers;

import com.example.dating.exceptions.*;
import com.example.dating.models.auth.*;
import com.example.dating.models.user.common.dto.UserDtoRequest;
import com.example.dating.services.AuthService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static com.example.dating.constants.AppConstants.BASE_API_ROUTE;

@Tag(name = "Authentication", description = "Registration, login, email verification, password reset, and Spotify account linking")
@Slf4j
@RestController()
@RequestMapping(BASE_API_ROUTE + "/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/spotify-login")
    public ResponseEntity<AuthResponseDto> handleSpotifyLogin(@Valid @RequestBody UserDtoRequest userDto) {
        AuthResponseDto response = authService.spotifyLogin(userDto);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequestDto request) {
        AuthResponseDto response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequestDto request) {
        AuthResponseDto response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/verify-email")
    public ResponseEntity<?> verifyEmail(@Valid @RequestBody EmailVerificationRequestDto request) {
        authService.verifyEmail(request.getToken());
        return ResponseEntity.ok(Map.of("message", "Email verified successfully"));
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<?> resendVerification(@Valid @RequestBody ResendVerificationRequestDto request) {
        try {
            authService.resendVerificationEmail(request.getEmail());
        } catch (UserNotFoundException e) {
            // Don't reveal whether the email exists — return the same success message either way.
        }
        return ResponseEntity.ok(Map.of("message", "If the email exists, verification email will be sent"));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@Valid @RequestBody ForgotPasswordRequestDto request) {
        authService.forgotPassword(request.getEmail());
        return ResponseEntity.ok(Map.of("message", "If the email exists, a password reset link will be sent"));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@Valid @RequestBody ResetPasswordRequestDto request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(Map.of("message", "Password reset successfully"));
    }

    @PostMapping("/connect-spotify")
    public ResponseEntity<?> connectSpotify(
            Authentication authentication,
            @Valid @RequestBody ConnectSpotifyRequestDto request) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        String userId = jwt.getClaimAsString("userId");
        AuthResponseDto response = authService.connectSpotify(userId, request);
        return ResponseEntity.ok(response);
    }
}
