package com.example.dating.mappers;

import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.domain.User;
import com.example.dating.models.user.dto.UserProfileResponseDto;
import com.example.dating.models.user.lifestyle.dao.UserLifestyle;
import com.example.dating.models.user.personality.dao.UserPersonality;
import com.example.dating.models.user.photos.dao.UserPhoto;
import com.example.dating.models.user.privacy.dao.UserPrivacySettings;

import com.example.dating.models.user.common.dto.UserDtoRequest;
import com.example.dating.models.user.common.dto.UserDtoResponse;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

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
                .emailVerificationTokenHash(entity.getEmailVerificationTokenHash())
                .passwordResetTokenHash(entity.getPasswordResetTokenHash())
                .tokenVersion(entity.getTokenVersion())
                // Batch E: lockout
                .failedLoginAttempts(entity.getFailedLoginAttempts())
                .lockedUntil(entity.getLockedUntil())
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
            existingEntity.setEmailVerificationTokenHash(domain.getEmailVerificationTokenHash());
            existingEntity.setPasswordResetTokenHash(domain.getPasswordResetTokenHash());
            existingEntity.setTokenVersion(domain.getTokenVersion());
            // Batch E: lockout
            existingEntity.setFailedLoginAttempts(
                    domain.getFailedLoginAttempts() != null ? domain.getFailedLoginAttempts() : 0);
            existingEntity.setLockedUntil(domain.getLockedUntil());
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
                .emailVerificationTokenHash(domain.getEmailVerificationTokenHash())
                .passwordResetTokenHash(domain.getPasswordResetTokenHash())
                .tokenVersion(domain.getTokenVersion() != null ? domain.getTokenVersion() : 0)
                // Batch E: lockout
                .failedLoginAttempts(domain.getFailedLoginAttempts() != null ? domain.getFailedLoginAttempts() : 0)
                .lockedUntil(domain.getLockedUntil())
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

    /**
     * Maps a UserEntity to the public profile DTO, applying field-level privacy gating.
     *
     * @param entity  the target user's entity (with lazy associations available via open-in-view)
     * @param isOwner true when the requester is the profile owner — bypasses all privacy flags
     */
    public UserProfileResponseDto toUserProfileResponse(UserEntity entity, boolean isOwner) {
        UserPrivacySettings privacy = entity.getPrivacySettings();
        boolean showAge = isOwner || privacy == null || Boolean.TRUE.equals(privacy.getShowAge());

        // Photos sorted by displayOrder ascending
        List<String> photoUrls = List.of();
        if (entity.getPhotos() != null && !entity.getPhotos().isEmpty()) {
            photoUrls = entity.getPhotos().stream()
                    .sorted(Comparator.comparingInt(p -> p.getDisplayOrder() != null ? p.getDisplayOrder() : 99))
                    .map(UserPhoto::getImageUrl)
                    .toList();
        }

        UserProfileResponseDto.UserProfileResponseDtoBuilder builder = UserProfileResponseDto.builder()
                .userId(entity.getId())
                .name(entity.getName())
                .age(showAge ? entity.getAge() : null)
                .gender(entity.getGender())
                .locationCity(entity.getLocationCity())
                .locationCountry(entity.getLocationCountry())
                .photos(photoUrls);

        UserPersonality personality = entity.getPersonality();
        if (personality != null) {
            builder.bio(personality.getBio())
                   .interests(personality.getInterests())
                   .mbti(personality.getMbti())
                   .lookingForText(personality.getLookingForText())
                   .favoriteQuote(personality.getFavoriteQuote())
                   .conversationStarters(personality.getConversationStarters());
        }

        UserLifestyle lifestyle = entity.getLifestyle();
        if (lifestyle != null) {
            builder.education(lifestyle.getEducation())
                   .occupation(lifestyle.getOccupation())
                   .smokingHabits(lifestyle.getSmokingHabits())
                   .drinkingHabits(lifestyle.getDrinkingHabits())
                   .exerciseFrequency(lifestyle.getExerciseFrequency())
                   .wantsKids(lifestyle.getWantsKids())
                   .religion(lifestyle.getReligion());
        }

        return builder.build();
    }

    public UserDtoResponse toDtoRequest(User user) {
        return UserDtoResponse.builder()
                .email(user.getEmail())
                .name(user.getName())
                .imageUrl(user.getImageUrl())
                .build();
    }
}