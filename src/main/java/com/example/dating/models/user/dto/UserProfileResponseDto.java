package com.example.dating.models.user.dto;

import com.example.dating.enums.user.*;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Public-facing profile DTO returned by GET /api/v1/users/{id}/profile.
 *
 * Privacy-gated fields (e.g. age) are set to null when the target user's
 * privacy settings prohibit their disclosure. All other fields are populated
 * when the profile is public or when the requester is the profile owner.
 */
@Data
@Builder
public class UserProfileResponseDto {

    // Core identity
    private String userId;
    private String name;
    private Integer age;          // null when showAge = false and requester is not owner
    private Gender gender;
    private String locationCity;
    private String locationCountry;

    // Photos (sorted by displayOrder ascending)
    private List<String> photos;

    // Personality
    private String bio;
    private String interests;
    private MBTI mbti;
    private String lookingForText;
    private String favoriteQuote;
    private String conversationStarters;

    // Lifestyle
    private EducationLevel education;
    private String occupation;
    private SmokingHabits smokingHabits;
    private DrinkingHabits drinkingHabits;
    private ExerciseFrequency exerciseFrequency;
    private KidsPreference wantsKids;
    private Religion religion;
}
