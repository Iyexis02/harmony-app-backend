package com.example.dating.models.onboarding.dto;

import com.example.dating.enums.user.RegistrationStage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class OnboardingProgressDto {
    private RegistrationStage currentStage;
    private Integer completionPercentage;
    private Map<String, Boolean> stepsCompleted;
    private String nextStep;
}
