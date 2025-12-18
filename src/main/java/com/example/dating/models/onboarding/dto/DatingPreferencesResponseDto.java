package com.example.dating.models.onboarding.dto;

import com.example.dating.enums.user.Gender;
import com.example.dating.enums.user.RelationshipGoal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class DatingPreferencesResponseDto {
    private Integer minAge;
    private Integer maxAge;
    private Integer maxDistanceKm;
    private List<Gender> interestedInGenders;
    private RelationshipGoal relationshipGoal;
    private List<String> dealBreakers;
    private String showMe;
    private Integer musicMatchImportance;
}
