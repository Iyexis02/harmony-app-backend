package com.example.dating.models.onboarding.dto;

import com.example.dating.enums.user.*;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class LifestyleRequestDto {

    private EducationLevel education;

    private String occupation;

    private String company;

    @NotNull(message = "Relationship status is required")
    private RelationshipStatus relationshipStatus;

    private KidsPreference wantsKids;

    private SmokingHabits smokingHabits;

    private DrinkingHabits drinkingHabits;

    private ExerciseFrequency exerciseFrequency;

    private String religion;

    private String politicalViews;
}
