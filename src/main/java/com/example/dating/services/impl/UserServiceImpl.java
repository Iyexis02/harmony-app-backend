package com.example.dating.services.impl;

import com.example.dating.models.user.domain.User;
import com.example.dating.enums.user.AuthProvider;
import com.example.dating.enums.user.RegistrationStage;
import com.example.dating.mappers.UserMapper;
import com.example.dating.models.auth.SpotifyTokenResponse;
import com.example.dating.models.user.common.dto.UserDtoRequest;
import com.example.dating.models.user.common.dto.UserDtoResponse;
import com.example.dating.postgres.UserRepository;
import com.example.dating.services.EncryptionService;
import com.example.dating.services.JwtService;
import com.example.dating.services.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final EncryptionService encryptionService;
    private final UserMapper userMapper;
    private final JwtService jwtService;

    @Override
    @Transactional
    public UserDtoResponse findOrCreateUser(UserDtoRequest userDto) throws IllegalArgumentException, OptimisticLockingFailureException {
        // Try to find existing user
        Optional<User> existingUser = userRepository.findBySpotifyId(userDto.spotifyId());

        User user;

        if (existingUser.isPresent()) {
            user = existingUser.get();
            user.setEmail(userDto.email());
            user.setName(userDto.name());
            user.setSpotifyAccessToken(encryptionService.encrypt(userDto.spotifyAccessToken()));
            user.setSpotifyRefreshToken(encryptionService.encrypt(userDto.spotifyRefreshToken()));
            user.setSpotifyTokenExpires(Instant.ofEpochSecond(userDto.spotifyTokenExpiresAt()));
        } else {
            // Check if email already exists (could be email user trying to login with Spotify)
            Optional<User> emailUser = userRepository.findByEmail(userDto.email());
            if (emailUser.isPresent() && emailUser.get().getAuthProvider() == AuthProvider.EMAIL) {
                throw new IllegalArgumentException("Email already registered. Please login with email/password or connect Spotify from your profile.");
            }

            user = User.builder()
                    .spotifyId(userDto.spotifyId())
                    .email(userDto.email())
                    .name(userDto.name())
                    .createdAt(LocalDateTime.now())
                    .imageUrl(userDto.imageUrl())
                    .registrationStage(RegistrationStage.STARTED)
                    .authProvider(AuthProvider.SPOTIFY) // Set auth provider
                    .emailVerified(true) // Spotify emails are pre-verified
                    .spotifyAccessToken(encryptionService.encrypt(userDto.spotifyAccessToken()))
                    .spotifyRefreshToken(encryptionService.encrypt(userDto.spotifyRefreshToken()))
                    .spotifyTokenExpires(Instant.ofEpochSecond(userDto.spotifyTokenExpiresAt()))
                    .build();
        }


        user = userRepository.save(user);
        return userMapper.toDtoResponse(user);
    }

    @Override
    @Transactional(readOnly = true)
    public UserDtoResponse userExists(String spotifyId) {
        return userRepository.findBySpotifyId(spotifyId).map(userMapper::toDtoResponse).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public UserDtoResponse getUserBySpotifyId(String spotifyId) {
        return userRepository.findBySpotifyId(spotifyId)
                .map(userMapper::toDtoResponse)
                .orElse(null);
    }

    @Transactional
    public String getValidSpotifyToken(User user) {
        // Check if user has connected Spotify
        if (user.getSpotifyId() == null) {
            throw new IllegalStateException("User has not connected Spotify");
        }

        // Check if token is expired or about to expire (within 5 minutes)
        if (isTokenExpiredOrExpiring(user)) {
            log.info("Spotify token expired for user: {}, refreshing...", user.getSpotifyId());
            return refreshAndUpdateUserToken(user);
        }

        // Token is still valid, decrypt and return
        return encryptionService.decrypt(user.getSpotifyAccessToken());
    }

    /**
     * Check if token is expired or expiring soon
     */
    private boolean isTokenExpiredOrExpiring(User user) {
        if (user.getSpotifyTokenExpires() == null) {
            return true; // If no expiry set, consider it expired
        }

        // Check if expired or expiring within 5 minutes
        Instant expiryThreshold = Instant.now().plusSeconds(300); // 5 minutes buffer
        return user.getSpotifyTokenExpires().isBefore(expiryThreshold);
    }

    /**
     * Refresh Spotify token and update user in database
     */
    @Transactional
    public String refreshAndUpdateUserToken(User user) {
        try {
            // Decrypt refresh token
            String refreshToken = encryptionService.decrypt(user.getSpotifyRefreshToken());

            // Call Spotify to refresh
            SpotifyTokenResponse tokenResponse = jwtService.refreshToken(refreshToken);

            // Update user with new tokens
            user.setSpotifyAccessToken(encryptionService.encrypt(tokenResponse.getAccess_token()));
            user.setSpotifyTokenExpires(Instant.now().plusSeconds(Long.parseLong(tokenResponse.getExpires_in())));

            // Update refresh token only if Spotify returned a new one
            if (tokenResponse.getRefresh_token() != null &&
                    !tokenResponse.getRefresh_token().equals(refreshToken)) {
                user.setSpotifyRefreshToken(encryptionService.encrypt(tokenResponse.getRefresh_token()));
            }

            // Save to database
            userRepository.save(user);

            log.info("Successfully refreshed and updated Spotify token for user: {}", user.getSpotifyId());

            return tokenResponse.getAccess_token();

        } catch (Exception e) {
            log.error("Failed to refresh token for user {}: {}", user.getSpotifyId(), e.getMessage());
            throw new RuntimeException("Failed to refresh Spotify token", e);
        }
    }
}