package com.example.dating.models.onboarding.dto;

import com.example.dating.enums.user.Gender;
import com.example.dating.enums.user.RelationshipGoal;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class DatingPreferencesRequestDto {

    @NotNull(message = "Minimum age is required")
    @Min(value = 18, message = "Minimum age must be at least 18")
    private Integer minAge;

    @NotNull(message = "Maximum age is required")
    @Max(value = 100, message = "Maximum age cannot exceed 100")
    private Integer maxAge;

    @NotNull(message = "Maximum distance is required")
    @Min(value = 1, message = "Maximum distance must be at least 1 km")
    private Integer maxDistanceKm;

    @NotNull(message = "Interested in genders is required")
    private List<Gender> interestedInGenders;

    @NotNull(message = "Relationship goal is required")
    private RelationshipGoal relationshipGoal;

    private List<String> dealBreakers;

    private String showMe;

    @Min(value = 0, message = "Music match importance must be at least 0")
    @Max(value = 100, message = "Music match importance cannot exceed 100")
    private Integer musicMatchImportance;
}
