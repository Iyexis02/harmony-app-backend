package com.example.dating.models.onboarding.dto;

import com.example.dating.enums.user.MBTI;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PersonalityRequestDto {

    @NotBlank(message = "Bio is required")
    @Size(max = 500, message = "Bio cannot exceed 500 characters")
    private String bio;

    private List<String> interests;

    private MBTI mbti;

    @Size(max = 500, message = "Looking for text cannot exceed 500 characters")
    private String lookingForText;

    @Size(max = 300, message = "Favorite quote cannot exceed 300 characters")
    private String favoriteQuote;

    @Size(max = 500, message = "Conversation starters cannot exceed 500 characters")
    private String conversationStarters;
}
