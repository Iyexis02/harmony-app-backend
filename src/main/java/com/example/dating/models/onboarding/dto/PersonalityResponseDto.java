package com.example.dating.models.onboarding.dto;

import com.example.dating.enums.user.MBTI;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PersonalityResponseDto {
    private String bio;
    private List<String> interests;
    private MBTI mbti;
    private String lookingForText;
    private String favoriteQuote;
    private String conversationStarters;
}
