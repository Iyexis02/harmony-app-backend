package com.example.dating.mappers;

import com.example.dating.enums.user.Gender;
import com.example.dating.enums.user.RegistrationStage;
import com.example.dating.models.onboarding.dto.*;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.dating.dao.UserDatingPreferences;
import com.example.dating.models.user.lifestyle.dao.UserLifestyle;
import com.example.dating.models.user.personality.dao.UserPersonality;
import com.example.dating.models.user.photos.dao.UserPhoto;
import com.example.dating.models.user.preferences.dao.UserMusicPreferences;
import com.example.dating.models.user.privacy.dao.UserPrivacySettings;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class OnboardingMapper {

    public MusicPreferencesResponseDto toMusicPreferencesDto(UserMusicPreferences entity) {
        if (entity == null) return null;

        return MusicPreferencesResponseDto.builder()
                .favoriteGenres(splitToList(entity.getFavoriteGenres()))
                .concertFrequency(entity.getConcertFrequency())
                .musicImportance(entity.getMusicImportance())
                .favoriteDecades(splitToList(entity.getFavoriteDecades()))
                .openToNewGenres(entity.getOpenToNewGenres())
                .listeningTimes(splitToList(entity.getListeningTimes()))
                .hoursPerDay(entity.getHoursPerDay())
                .build();
    }

    public LifestyleResponseDto toLifestyleDto(UserLifestyle entity) {
        if (entity == null) return null;

        return LifestyleResponseDto.builder()
                .education(entity.getEducation())
                .occupation(entity.getOccupation())
                .company(entity.getCompany())
                .relationshipStatus(entity.getRelationshipStatus())
                .wantsKids(entity.getWantsKids())
                .smokingHabits(entity.getSmokingHabits())
                .drinkingHabits(entity.getDrinkingHabits())
                .exerciseFrequency(entity.getExerciseFrequency())
                .religion(entity.getReligion())
                .politicalViews(entity.getPoliticalViews())
                .build();
    }

    public PersonalityResponseDto toPersonalityDto(UserPersonality entity) {
        if (entity == null) return null;

        return PersonalityResponseDto.builder()
                .bio(entity.getBio())
                .interests(splitToList(entity.getInterests()))
                .mbti(entity.getMbti())
                .lookingForText(entity.getLookingForText())
                .favoriteQuote(entity.getFavoriteQuote())
                .conversationStarters(entity.getConversationStarters())
                .build();
    }

    public DatingPreferencesResponseDto toDatingPreferencesDto(UserDatingPreferences entity) {
        if (entity == null) return null;

        List<Gender> genders = null;
        if (entity.getInterestedInGenders() != null) {
            genders = Arrays.stream(entity.getInterestedInGenders().split(","))
                    .map(String::trim)
                    .map(Gender::valueOf)
                    .collect(Collectors.toList());
        }

        return DatingPreferencesResponseDto.builder()
                .minAge(entity.getMinAge())
                .maxAge(entity.getMaxAge())
                .maxDistanceKm(entity.getMaxDistanceKm())
                .interestedInGenders(genders)
                .relationshipGoal(entity.getRelationshipGoal())
                .dealBreakers(splitToList(entity.getDealBreakers()))
                .showMe(entity.getShowMe())
                .musicMatchImportance(entity.getMusicMatchImportance())
                .build();
    }

    public PrivacySettingsResponseDto toPrivacySettingsDto(UserPrivacySettings entity) {
        if (entity == null) return null;

        return PrivacySettingsResponseDto.builder()
                .isProfilePublic(entity.getIsProfilePublic())
                .showAge(entity.getShowAge())
                .showDistance(entity.getShowDistance())
                .showLastActive(entity.getShowLastActive())
                .discoverable(entity.getDiscoverable())
                .showLikedByYou(entity.getShowLikedByYou())
                .showSpotifyProfile(entity.getShowSpotifyProfile())
                .showMusicStats(entity.getShowMusicStats())
                .incognitoMode(entity.getIncognitoMode())
                .readReceipts(entity.getReadReceipts())
                .build();
    }

    public PhotoResponseDto toPhotoDto(UserPhoto entity) {
        if (entity == null) return null;

        return PhotoResponseDto.builder()
                .id(entity.getId())
                .imageUrl(entity.getImageUrl())
                .displayOrder(entity.getDisplayOrder())
                .isPrimary(entity.getIsPrimary())
                .caption(entity.getCaption())
                .build();
    }

    public OnboardingProgressDto toProgressDto(UserEntity entity) {
        if (entity == null) return null;

        Map<String, Boolean> stepsCompleted = new LinkedHashMap<>();
        stepsCompleted.put("BASIC_PROFILE", isBasicProfileComplete(entity));
        stepsCompleted.put("LOCATION_INFO", isLocationComplete(entity));
        stepsCompleted.put("PHOTOS", isPhotosComplete(entity));
        stepsCompleted.put("MUSIC_PREFERENCES", entity.getMusicPreferences() != null);
        stepsCompleted.put("LIFESTYLE", entity.getLifestyle() != null);
        stepsCompleted.put("PERSONALITY", entity.getPersonality() != null);
        stepsCompleted.put("DATING_PREFERENCES", entity.getDatingPreferences() != null);
        stepsCompleted.put("PRIVACY_SETTINGS", entity.getPrivacySettings() != null);

        long completedCount = stepsCompleted.values().stream().filter(Boolean::booleanValue).count();
        int percentage = (int) ((completedCount * 100) / stepsCompleted.size());

        String nextStep = getNextStep(entity.getRegistrationStage());

        return OnboardingProgressDto.builder()
                .currentStage(entity.getRegistrationStage())
                .completionPercentage(percentage)
                .stepsCompleted(stepsCompleted)
                .nextStep(nextStep)
                .build();
    }

    public CompleteProfileResponseDto toCompleteProfileDto(UserEntity entity) {
        if (entity == null) return null;

        List<PhotoResponseDto> photos = entity.getPhotos() != null
                ? entity.getPhotos().stream()
                .map(this::toPhotoDto)
                .sorted(Comparator.comparing(PhotoResponseDto::getDisplayOrder))
                .collect(Collectors.toList())
                : new ArrayList<>();

        String primaryPhotoUrl = photos.stream()
                .filter(p -> p.getIsPrimary() != null && p.getIsPrimary())
                .findFirst()
                .map(PhotoResponseDto::getImageUrl)
                .orElse(photos.isEmpty() ? null : photos.get(0).getImageUrl());

        Integer age = entity.getDateOfBirth() != null
                ? LocalDate.now().getYear() - entity.getDateOfBirth().getYear()
                : null;

        return CompleteProfileResponseDto.builder()
                .id(entity.getId())
                .email(entity.getEmail())
                .name(entity.getName())
                .dateOfBirth(entity.getDateOfBirth())
                .age(age)
                .gender(entity.getGender())
                .sexualOrientation(entity.getSexualOrientation())
                .registrationStage(entity.getRegistrationStage())
                .locationCity(entity.getLocationCity())
                .locationCountry(entity.getLocationCountry())
                .latitude(entity.getLocationLat())
                .longitude(entity.getLocationLon())
                .photos(photos)
                .primaryPhotoUrl(primaryPhotoUrl)
                .musicPreferences(toMusicPreferencesDto(entity.getMusicPreferences()))
                .lifestyle(toLifestyleDto(entity.getLifestyle()))
                .personality(toPersonalityDto(entity.getPersonality()))
                .datingPreferences(toDatingPreferencesDto(entity.getDatingPreferences()))
                .privacySettings(toPrivacySettingsDto(entity.getPrivacySettings()))
                .progress(toProgressDto(entity))
                .build();
    }

    // Helper methods
    private List<String> splitToList(String value) {
        if (value == null || value.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private boolean isBasicProfileComplete(UserEntity entity) {
        return entity.getName() != null &&
                entity.getDateOfBirth() != null &&
                entity.getGender() != null &&
                entity.getSexualOrientation() != null;
    }

    private boolean isLocationComplete(UserEntity entity) {
        return entity.getLocationCity() != null &&
                entity.getLocationCountry() != null &&
                entity.getLocationLat() != null &&
                entity.getLocationLon() != null;
    }

    private boolean isPhotosComplete(UserEntity entity) {
        return entity.getPhotos() != null && !entity.getPhotos().isEmpty();
    }

    private String getNextStep(RegistrationStage currentStage) {
        if (currentStage == null || currentStage == RegistrationStage.FINISHED) {
            return null;
        }

        RegistrationStage[] stages = RegistrationStage.values();
        for (int i = 0; i < stages.length - 1; i++) {
            if (stages[i] == currentStage) {
                return stages[i + 1].name();
            }
        }
        return null;
    }
}
