package com.example.dating.mappers;

import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.domain.User;

import com.example.dating.models.user.common.dto.UserDtoRequest;
import com.example.dating.models.user.common.dto.UserDtoResponse;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    // Entity <-> Domain
    public User toDomain(UserEntity entity) {
        if (entity == null) return null;

        // Get primary photo URL if available
        String primaryPhotoUrl = null;
        if (entity.getPhotos() != null && !entity.getPhotos().isEmpty()) {
            primaryPhotoUrl = entity.getPhotos().stream()
                    .filter(photo -> photo.getIsPrimary() != null && photo.getIsPrimary())
                    .findFirst()
                    .map(photo -> photo.getImageUrl())
                    .orElse(entity.getPhotos().get(0).getImageUrl());
        }

        return User.builder()
                .id(entity.getId())
                .email(entity.getEmail())
                // Authentication
                .authProvider(entity.getAuthProvider())
                .passwordHash(entity.getPasswordHash())
                .emailVerified(entity.getEmailVerified())
                .emailVerificationToken(entity.getEmailVerificationToken())
                .emailVerificationExpires(entity.getEmailVerificationExpires())
                .passwordResetToken(entity.getPasswordResetToken())
                .passwordResetExpires(entity.getPasswordResetExpires())
                // Spotify
                .spotifyId(entity.getSpotifyId())
                .spotifyAccessToken(entity.getSpotifyAccessToken())
                .spotifyRefreshToken(entity.getSpotifyRefreshToken())
                .spotifyTokenExpires(entity.getSpotifyTokenExpires())
                .imageUrl(primaryPhotoUrl)
                .registrationStage(entity.getRegistrationStage())
                .createdAt(entity.getCreatedAt())
                .name(entity.getName())
                .locationLon(entity.getLocationLon())
                .locationLat(entity.getLocationLat())
                .locationCity(entity.getLocationCity())
                .locationCountry(entity.getLocationCountry())
                .sexualOrientation(entity.getSexualOrientation())
                .gender(entity.getGender())
                .dateOfBirth(entity.getDateOfBirth())
                .userEntity(entity)
                .build();
    }

    public UserEntity toEntity(User domain) {
        if (domain == null) return null;

        // CRITICAL FIX: If domain already has a reference to an existing entity,
        // UPDATE that entity instead of creating a new one.
        // This prevents orphanRemoval from deleting related entities (photos, privacy, etc.)
        if (domain.getUserEntity() != null) {
            UserEntity existingEntity = domain.getUserEntity();

            // Update all fields on the EXISTING entity
            existingEntity.setEmail(domain.getEmail());
            // Authentication
            existingEntity.setAuthProvider(domain.getAuthProvider());
            existingEntity.setPasswordHash(domain.getPasswordHash());
            existingEntity.setEmailVerified(domain.getEmailVerified());
            existingEntity.setEmailVerificationToken(domain.getEmailVerificationToken());
            existingEntity.setEmailVerificationExpires(domain.getEmailVerificationExpires());
            existingEntity.setPasswordResetToken(domain.getPasswordResetToken());
            existingEntity.setPasswordResetExpires(domain.getPasswordResetExpires());
            // Spotify
            existingEntity.setSpotifyId(domain.getSpotifyId());
            existingEntity.setSpotifyAccessToken(domain.getSpotifyAccessToken());
            existingEntity.setSpotifyRefreshToken(domain.getSpotifyRefreshToken());
            existingEntity.setSpotifyTokenExpires(domain.getSpotifyTokenExpires());
            // User fields
            existingEntity.setRegistrationStage(domain.getRegistrationStage());
            existingEntity.setName(domain.getName());
            existingEntity.setLocationCity(domain.getLocationCity());
            existingEntity.setLocationCountry(domain.getLocationCountry());
            existingEntity.setLocationLat(domain.getLocationLat());
            existingEntity.setLocationLon(domain.getLocationLon());
            existingEntity.setSexualOrientation(domain.getSexualOrientation());
            existingEntity.setGender(domain.getGender());
            existingEntity.setDateOfBirth(domain.getDateOfBirth());

            // Return the updated EXISTING entity (preserves all relationships)
            return existingEntity;
        }

        // If no existing entity, create a new one (initial user creation)
        return UserEntity.builder()
                .id(domain.getId())
                .email(domain.getEmail())
                // Authentication
                .authProvider(domain.getAuthProvider())
                .passwordHash(domain.getPasswordHash())
                .emailVerified(domain.getEmailVerified())
                .emailVerificationToken(domain.getEmailVerificationToken())
                .emailVerificationExpires(domain.getEmailVerificationExpires())
                .passwordResetToken(domain.getPasswordResetToken())
                .passwordResetExpires(domain.getPasswordResetExpires())
                // Spotify
                .spotifyId(domain.getSpotifyId())
                .spotifyAccessToken(domain.getSpotifyAccessToken())
                .spotifyRefreshToken(domain.getSpotifyRefreshToken())
                .spotifyTokenExpires(domain.getSpotifyTokenExpires())
                .registrationStage(domain.getRegistrationStage())
                .name(domain.getName())
                .locationCity(domain.getLocationCity())
                .locationCountry(domain.getLocationCountry())
                .locationLat(domain.getLocationLat())
                .locationLon(domain.getLocationLon())
                .sexualOrientation(domain.getSexualOrientation())
                .gender(domain.getGender())
                .dateOfBirth(domain.getDateOfBirth())
                .build();
    }

    // DTO <-> Domain (optional, can be done in service)
    public User toDomain(UserDtoRequest dto) {
        if (dto == null) return null;

        return User.builder()
                .spotifyId(dto.spotifyId())
                .email(dto.email())
                .name(dto.name())
                .imageUrl(dto.imageUrl())
                .build();
    }


    public UserDtoResponse toDtoResponse(User user) {
        return UserDtoResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .imageUrl(user.getImageUrl())
                .registrationStage(String.valueOf(user.getRegistrationStage()))
                .city(user.getLocationCity())
                .country(user.getLocationCountry())
                .latitude(user.getLocationLat())
                .longitude(user.getLocationLon())
                .sexualOrientation(user.getSexualOrientation())
                .gender(user.getGender())
                .dateOfBirth(user.getDateOfBirth())
                .build();
    }

    public UserDtoResponse toDtoRequest(User user) {
        return UserDtoResponse.builder()
                .email(user.getEmail())
                .name(user.getName())
                .imageUrl(user.getImageUrl())
                .build();
    }
}