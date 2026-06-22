package com.example.dating.services.impl;

import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.domain.User;
import com.example.dating.enums.user.AuthProvider;
import com.example.dating.enums.user.RegistrationStage;
import com.example.dating.exceptions.EmailAlreadyExistsException;
import com.example.dating.exceptions.UserNotFoundException;
import com.example.dating.mappers.UserMapper;
import com.example.dating.models.user.common.dto.UserDtoRequest;
import com.example.dating.models.user.common.dto.UserDtoResponse;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.EncryptionService;
import com.example.dating.services.UserService;
import org.springframework.security.crypto.password.PasswordEncoder;
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

    private final UserJpaRepository userJpaRepository;
    private final EncryptionService encryptionService;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public UserDtoResponse findOrCreateUser(UserDtoRequest userDto) throws EmailAlreadyExistsException, OptimisticLockingFailureException {
        // Try to find existing user
        Optional<User> existingUser = userJpaRepository.findBySpotifyId(userDto.spotifyId())
                .map(userMapper::toDomain);

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
            Optional<User> emailUser = userJpaRepository.findByEmail(userDto.email())
                    .map(userMapper::toDomain);
            if (emailUser.isPresent() && emailUser.get().getAuthProvider() == AuthProvider.EMAIL) {
                // Recovery hint preserved — the legitimate user knows their own email and
                // benefits from the actionable instructions. Maps to 409 Conflict.
                throw new EmailAlreadyExistsException(
                        "Email already registered. Please login with email/password or connect Spotify from your profile.");
            }

            user = User.builder()
                    .spotifyId(userDto.spotifyId())
                    .email(userDto.email())
                    .name(userDto.name())
                    .createdAt(LocalDateTime.now())
                    .imageUrl(userDto.imageUrl())
                    .registrationStage(RegistrationStage.STARTED)
                    .authProvider(AuthProvider.SPOTIFY)
                    .emailVerified(true) // Spotify emails are pre-verified
                    .spotifyAccessToken(encryptionService.encrypt(userDto.spotifyAccessToken()))
                    .spotifyRefreshToken(encryptionService.encrypt(userDto.spotifyRefreshToken()))
                    .spotifyTokenExpires(Instant.ofEpochSecond(userDto.spotifyTokenExpiresAt()))
                    .build();
        }

        UserEntity savedEntity = userJpaRepository.save(userMapper.toEntity(user));
        user = userMapper.toDomain(savedEntity);
        return userMapper.toDtoResponse(user);
    }

    @Override
    @Transactional(readOnly = true)
    public UserDtoResponse userExists(String spotifyId) {
        return userJpaRepository.findBySpotifyId(spotifyId)
                .map(e -> userMapper.toDtoResponse(userMapper.toDomain(e)))
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public UserDtoResponse getUserBySpotifyId(String spotifyId) {
        return userJpaRepository.findBySpotifyId(spotifyId)
                .map(e -> userMapper.toDtoResponse(userMapper.toDomain(e)))
                .orElse(null);
    }
}
