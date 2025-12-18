package com.example.dating.services.impl;

import com.example.dating.enums.user.AuthProvider;
import com.example.dating.enums.user.RegistrationStage;
import com.example.dating.exceptions.*;
import com.example.dating.models.auth.*;
import com.example.dating.models.user.domain.User;
import com.example.dating.postgres.UserRepository;
import com.example.dating.services.AuthService;
import com.example.dating.services.EmailService;
import com.example.dating.services.EncryptionService;
import com.example.dating.services.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final EmailService emailService;
    private final EncryptionService encryptionService;

    @Value("${app.verification.token-expiry-hours:24}")
    private int verificationTokenExpiryHours;

    @Value("${app.password-reset.token-expiry-hours:1}")
    private int passwordResetTokenExpiryHours;

    @Override
    @Transactional
    public AuthResponseDto register(RegisterRequestDto request) {
        // Check if email already exists
        Optional<User> existingUser = userRepository.findByEmail(request.getEmail());
        if (existingUser.isPresent()) {
            throw new EmailAlreadyExistsException("Email already registered");
        }

        // Create verification token
        String verificationToken = UUID.randomUUID().toString();

        // Create user
        User user = User.builder()
                .email(request.getEmail())
                .name(request.getName())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .authProvider(AuthProvider.EMAIL)
                .emailVerified(false)
                .emailVerificationToken(verificationToken)
                .emailVerificationExpires(LocalDateTime.now().plusHours(verificationTokenExpiryHours))
                .registrationStage(RegistrationStage.STARTED)
                .createdAt(LocalDateTime.now())
                .build();

        user = userRepository.save(user);
        log.info("New user registered with email: {}", user.getEmail());

        // Send verification email
        try {
            emailService.sendVerificationEmail(user.getEmail(), user.getName(), verificationToken);
        } catch (Exception e) {
            log.error("Failed to send verification email to {}: {}", user.getEmail(), e.getMessage());
            // Don't fail registration if email fails
        }

        // Generate JWT (even though email not verified, allow login to complete profile)
        String token = jwtService.generateToken(user);

        return buildAuthResponse(user, token);
    }

    @Override
    @Transactional(readOnly = true)
    public AuthResponseDto login(LoginRequestDto request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));

        // Verify it's an email user
        if (user.getAuthProvider() != AuthProvider.EMAIL) {
            throw new InvalidCredentialsException("Please login with Spotify");
        }

        // Check password
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        // Note: We allow login even if email not verified, but frontend should restrict features
        log.info("User logged in: {}", user.getEmail());

        String token = jwtService.generateToken(user);
        return buildAuthResponse(user, token);
    }

    @Override
    @Transactional
    public void verifyEmail(String token) {
        User user = userRepository.findByEmailVerificationToken(token)
                .orElseThrow(() -> new InvalidTokenException("Invalid verification token"));

        // Check if token expired
        if (user.getEmailVerificationExpires().isBefore(LocalDateTime.now())) {
            throw new InvalidTokenException("Verification token has expired");
        }

        // Verify email
        user.setEmailVerified(true);
        user.setEmailVerificationToken(null);
        user.setEmailVerificationExpires(null);

        userRepository.save(user);
        log.info("Email verified for user: {}", user.getEmail());

        // Send welcome email
        try {
            emailService.sendWelcomeEmail(user.getEmail(), user.getName());
        } catch (Exception e) {
            log.error("Failed to send welcome email: {}", e.getMessage());
        }
    }

    @Override
    @Transactional
    public void forgotPassword(String email) {
        Optional<User> userOpt = userRepository.findByEmail(email);

        // Don't reveal if email exists (security best practice)
        if (userOpt.isEmpty()) {
            log.warn("Password reset requested for non-existent email: {}", email);
            return;
        }

        User user = userOpt.get();

        // Only allow password reset for email users
        if (user.getAuthProvider() != AuthProvider.EMAIL) {
            log.warn("Password reset requested for non-email user: {}", email);
            return;
        }

        // Generate reset token
        String resetToken = UUID.randomUUID().toString();
        user.setPasswordResetToken(resetToken);
        user.setPasswordResetExpires(LocalDateTime.now().plusHours(passwordResetTokenExpiryHours));

        userRepository.save(user);
        log.info("Password reset token generated for: {}", email);

        // Send reset email
        try {
            emailService.sendPasswordResetEmail(user.getEmail(), user.getName(), resetToken);
        } catch (Exception e) {
            log.error("Failed to send password reset email: {}", e.getMessage());
            throw new RuntimeException("Failed to send password reset email");
        }
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordRequestDto request) {
        User user = userRepository.findByPasswordResetToken(request.getToken())
                .orElseThrow(() -> new InvalidTokenException("Invalid reset token"));

        // Check if token expired
        if (user.getPasswordResetExpires().isBefore(LocalDateTime.now())) {
            throw new InvalidTokenException("Reset token has expired");
        }

        // Reset password
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setPasswordResetToken(null);
        user.setPasswordResetExpires(null);

        userRepository.save(user);
        log.info("Password reset successful for user: {}", user.getEmail());
    }

    @Override
    @Transactional
    public AuthResponseDto connectSpotify(String userId, ConnectSpotifyRequestDto request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        // Verify user is email-based
        if (user.getAuthProvider() != AuthProvider.EMAIL) {
            throw new IllegalArgumentException("Only email users can connect Spotify");
        }

        // Check if Spotify already connected
        if (user.getSpotifyId() != null) {
            throw new SpotifyAlreadyConnectedException("Spotify already connected");
        }

        // Check if this Spotify account is already used by another user
        Optional<User> existingSpotifyUser = userRepository.findBySpotifyId(request.getSpotifyId());
        if (existingSpotifyUser.isPresent()) {
            throw new IllegalArgumentException("This Spotify account is already connected to another user");
        }

        // Connect Spotify
        user.setSpotifyId(request.getSpotifyId());
        user.setSpotifyAccessToken(encryptionService.encrypt(request.getSpotifyAccessToken()));
        user.setSpotifyRefreshToken(encryptionService.encrypt(request.getSpotifyRefreshToken()));
        user.setSpotifyTokenExpires(Instant.ofEpochSecond(request.getSpotifyTokenExpiresAt()));

        user = userRepository.save(user);
        log.info("Spotify connected for user: {}", user.getEmail());

        String token = jwtService.generateToken(user);
        return buildAuthResponse(user, token);
    }

    @Override
    @Transactional
    public void resendVerificationEmail(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        if (user.getEmailVerified()) {
            throw new IllegalArgumentException("Email already verified");
        }

        // Generate new token
        String verificationToken = UUID.randomUUID().toString();
        user.setEmailVerificationToken(verificationToken);
        user.setEmailVerificationExpires(LocalDateTime.now().plusHours(verificationTokenExpiryHours));

        userRepository.save(user);

        // Send email
        emailService.sendVerificationEmail(user.getEmail(), user.getName(), verificationToken);
        log.info("Verification email resent to: {}", email);
    }

    private AuthResponseDto buildAuthResponse(User user, String token) {
        return AuthResponseDto.builder()
                .token(token)
                .userId(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .registrationStage(user.getRegistrationStage().toString())
                .emailVerified(user.getEmailVerified())
                .authProvider(user.getAuthProvider().toString())
                .build();
    }
}
