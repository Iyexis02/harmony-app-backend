package com.example.dating.models.onboarding.dto;

import com.example.dating.enums.user.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class LifestyleResponseDto {
    private EducationLevel education;
    private String occupation;
    private String company;
    private RelationshipStatus relationshipStatus;
    private KidsPreference wantsKids;
    private SmokingHabits smokingHabits;
    private DrinkingHabits drinkingHabits;
    private ExerciseFrequency exerciseFrequency;
    private String religion;
    private String politicalViews;
}
