package com.example.dating.services.impl;

import com.example.dating.enums.user.AuthProvider;
import com.example.dating.enums.user.RegistrationStage;
import com.example.dating.exceptions.*;
import com.example.dating.mappers.UserMapper;
import com.example.dating.models.auth.*;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.common.dto.UserDtoRequest;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.security.EmailVerificationFilter;
import com.example.dating.services.AuthService;
import com.example.dating.services.EmailService;
import com.example.dating.services.EncryptionService;
import com.example.dating.services.JwtService;
import com.example.dating.services.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserJpaRepository userJpaRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final EmailService emailService;
    private final EncryptionService encryptionService;
    private final UserService userService;
    private final EmailVerificationFilter emailVerificationFilter;

    @Value("${app.verification.token-expiry-hours:24}")
    private int verificationTokenExpiryHours;

    @Value("${app.password-reset.token-expiry-hours:1}")
    private int passwordResetTokenExpiryHours;

    @Override
    @Transactional
    public AuthResponseDto register(RegisterRequestDto request) {
        // Check if email already exists
        Optional<User> existingUser = userJpaRepository.findByEmail(request.getEmail())
                .map(userMapper::toDomain);
        if (existingUser.isPresent()) {
            throw new EmailAlreadyExistsException("Email already registered");
        }

        // Create verification token — plaintext sent in email, only hash stored in DB.
        String verificationToken = UUID.randomUUID().toString();

        // Create user
        User user = User.builder()
                .email(request.getEmail())
                .name(request.getName())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .authProvider(AuthProvider.EMAIL)
                .emailVerified(false)
                .emailVerificationTokenHash(sha256(verificationToken))
                .emailVerificationExpires(LocalDateTime.now().plusHours(verificationTokenExpiryHours))
                .registrationStage(RegistrationStage.STARTED)
                .tokenVersion(0)
                .createdAt(LocalDateTime.now())
                .build();

        try {
            UserEntity savedEntity = userJpaRepository.save(userMapper.toEntity(user));
            user = userMapper.toDomain(savedEntity);
        } catch (DataIntegrityViolationException ex) {
            throw new EmailAlreadyExistsException("Email already registered");
        }
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
    @Transactional
    public AuthResponseDto login(LoginRequestDto request) {
        User user = userJpaRepository.findByEmail(request.getEmail())
                .map(userMapper::toDomain)
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));

        // Verify it's an email user. Generic message prevents confirming account existence
        // or auth-provider type (Batch G — information disclosure hardening).
        if (user.getAuthProvider() != AuthProvider.EMAIL) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        // Batch E: Reject immediately if the account is within its lockout window.
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now())) {
            throw new AccountLockedException("Account temporarily locked. Try again later.");
        }

        // Check password.
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            // Batch E: Increment the failure counter; lock the account on the 5th failure.
            int attempts = (user.getFailedLoginAttempts() != null ? user.getFailedLoginAttempts() : 0) + 1;
            user.setFailedLoginAttempts(attempts);
            if (attempts >= 5) {
                user.setLockedUntil(LocalDateTime.now().plusMinutes(15));
                log.warn("Account {} locked after {} consecutive failed login attempts",
                        user.getEmail(), attempts);
            }
            userJpaRepository.save(userMapper.toEntity(user));
            throw new InvalidCredentialsException("Invalid email or password");
        }

        // Batch E: Successful login — reset the lockout state.
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userJpaRepository.save(userMapper.toEntity(user));

        log.info("User logged in: {}", user.getEmail());
        String token = jwtService.generateToken(user);
        return buildAuthResponse(user, token);
    }

    @Override
    @Transactional
    public AuthResponseDto spotifyLogin(UserDtoRequest request) {
        // Delegate user find-or-create to UserService (handles token encryption, etc.)
        userService.findOrCreateUser(request);

        // Re-fetch the full domain user so we can generate a proper JWT with tokenVersion
        User user = userJpaRepository.findBySpotifyId(request.spotifyId())
                .map(userMapper::toDomain)
                .orElseThrow(() -> new UserNotFoundException("User not found after Spotify login"));

        log.info("Spotify user logged in: {}", user.getEmail());
        String token = jwtService.generateToken(user);
        return buildAuthResponse(user, token);
    }

    @Override
    @Transactional
    public void verifyEmail(String token) {
        User user = userJpaRepository.findByEmailVerificationTokenHash(sha256(token))
                .map(userMapper::toDomain)
                .orElseThrow(() -> new InvalidTokenException("Invalid verification token"));

        // Check if token expired
        if (user.getEmailVerificationExpires().isBefore(LocalDateTime.now())) {
            throw new InvalidTokenException("Verification token has expired");
        }

        // Verify email
        user.setEmailVerified(true);
        user.setEmailVerificationToken(null);
        user.setEmailVerificationTokenHash(null);
        user.setEmailVerificationExpires(null);

        userJpaRepository.save(userMapper.toEntity(user));
        // Evict the cached emailVerified=false entry so the user's next request is
        // immediately allowed through EmailVerificationFilter without waiting for the TTL.
        emailVerificationFilter.evictEmailVerified(user.getId());
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
        Optional<User> userOpt = userJpaRepository.findByEmail(email)
                .map(userMapper::toDomain);

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

        // Generate reset token — plaintext sent in email, only hash stored in DB.
        String resetToken = UUID.randomUUID().toString();
        user.setPasswordResetToken(null);
        user.setPasswordResetTokenHash(sha256(resetToken));
        user.setPasswordResetExpires(LocalDateTime.now().plusHours(passwordResetTokenExpiryHours));

        userJpaRepository.save(userMapper.toEntity(user));
        log.info("Password reset token generated for: {}", email);

        // Send reset email — failure is non-fatal; token is persisted so user can retry.
        try {
            emailService.sendPasswordResetEmail(user.getEmail(), user.getName(), resetToken);
        } catch (Exception e) {
            log.error("Failed to send password reset email to {}: {}", user.getEmail(), e.getMessage());
        }
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordRequestDto request) {
        User user = userJpaRepository.findByPasswordResetTokenHash(sha256(request.getToken()))
                .map(userMapper::toDomain)
                .orElseThrow(() -> new InvalidTokenException("Invalid reset token"));

        // Check if token expired
        if (user.getPasswordResetExpires().isBefore(LocalDateTime.now())) {
            throw new InvalidTokenException("Reset token has expired");
        }

        // Reset password and invalidate all outstanding JWTs by bumping tokenVersion.
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setPasswordResetToken(null);
        user.setPasswordResetTokenHash(null);
        user.setPasswordResetExpires(null);
        user.setTokenVersion(user.getTokenVersion() != null ? user.getTokenVersion() + 1 : 1);

        userJpaRepository.save(userMapper.toEntity(user));
        log.info("Password reset successful for user: {}", user.getEmail());
    }

    @Override
    @Transactional
    public AuthResponseDto connectSpotify(String userId, ConnectSpotifyRequestDto request) {
        User user = userJpaRepository.findById(userId)
                .map(userMapper::toDomain)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        // Verify user is email-based
        if (user.getAuthProvider() != AuthProvider.EMAIL) {
            throw new BadRequestException("Only email users can connect Spotify");
        }

        // Check if Spotify already connected
        if (user.getSpotifyId() != null) {
            throw new SpotifyAlreadyConnectedException("Spotify already connected");
        }

        // Check if this Spotify account is already used by another user.
        // Generic message — never echo whether the *other* user's Spotify ID is in use,
        // which would let an attacker probe whether a given Spotify account is registered.
        Optional<User> existingSpotifyUser = userJpaRepository.findBySpotifyId(request.getSpotifyId())
                .map(userMapper::toDomain);
        if (existingSpotifyUser.isPresent()) {
            throw new SpotifyAlreadyConnectedException("Cannot connect this Spotify account");
        }

        // Connect Spotify
        user.setSpotifyId(request.getSpotifyId());
        user.setSpotifyAccessToken(encryptionService.encrypt(request.getSpotifyAccessToken()));
        user.setSpotifyRefreshToken(encryptionService.encrypt(request.getSpotifyRefreshToken()));
        user.setSpotifyTokenExpires(Instant.ofEpochSecond(request.getSpotifyTokenExpiresAt()));

        try {
            UserEntity savedEntity = userJpaRepository.save(userMapper.toEntity(user));
            user = userMapper.toDomain(savedEntity);
        } catch (DataIntegrityViolationException ex) {
            throw new SpotifyAlreadyConnectedException("Cannot connect this Spotify account");
        }
        log.info("Spotify connected for user: {}", user.getEmail());

        String token = jwtService.generateToken(user);
        return buildAuthResponse(user, token);
    }

    @Override
    @Transactional
    public void resendVerificationEmail(String email) {
        User user = userJpaRepository.findByEmail(email)
                .map(userMapper::toDomain)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        if (user.getEmailVerified()) {
            // Return silently — throwing here would confirm the email exists AND is verified
            // (Batch G — information disclosure hardening).
            return;
        }

        // Generate new token — plaintext sent in email, only hash stored in DB.
        String verificationToken = UUID.randomUUID().toString();
        user.setEmailVerificationToken(null);
        user.setEmailVerificationTokenHash(sha256(verificationToken));
        user.setEmailVerificationExpires(LocalDateTime.now().plusHours(verificationTokenExpiryHours));

        userJpaRepository.save(userMapper.toEntity(user));

        // Send email
        emailService.sendVerificationEmail(user.getEmail(), user.getName(), verificationToken);
        log.info("Verification email resent to: {}", email);
    }

    /**
     * Returns the hex-encoded SHA-256 digest of the input string.
     * Used to hash tokens before storing or comparing — plaintext tokens
     * are only kept in memory and sent to the user via email, never persisted.
     */
    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
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