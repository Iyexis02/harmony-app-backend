package com.example.dating.models.onboarding.dto;

import com.example.dating.enums.user.Gender;
import com.example.dating.enums.user.RegistrationStage;
import com.example.dating.enums.user.SexualOrientation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CompleteProfileResponseDto {
    // Basic info
    private String id;
    private String email;
    private String name;
    private LocalDate dateOfBirth;
    private Integer age;
    private Gender gender;
    private SexualOrientation sexualOrientation;
    private RegistrationStage registrationStage;

    // Location
    private String locationCity;
    private String locationCountry;
    private BigDecimal latitude;
    private BigDecimal longitude;

    // Photos
    private List<PhotoResponseDto> photos;
    private String primaryPhotoUrl;

    // Music preferences
    private MusicPreferencesResponseDto musicPreferences;

    // Lifestyle
    private LifestyleResponseDto lifestyle;

    // Personality
    private PersonalityResponseDto personality;

    // Dating preferences
    private DatingPreferencesResponseDto datingPreferences;

    // Privacy settings
    private PrivacySettingsResponseDto privacySettings;

    // Progress
    private OnboardingProgressDto progress;
}
