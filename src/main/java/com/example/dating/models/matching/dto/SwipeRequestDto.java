package com.example.dating.models.matching.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SwipeRequestDto {

    @NotBlank(message = "Swiped user ID is required")
    private String swipedUserId;

    @NotBlank(message = "Action is required")
    @Pattern(regexp = "^(like|pass|super_like|block)$",
             message = "Action must be like, pass, super_like, or block")
    private String action;

    /**
     * @deprecated The server now computes the match score from the scoring algorithm.
     * This field is accepted for backwards compatibility but is silently ignored.
     * Clients may continue to send it without error.
     */
    @Deprecated
    private Double matchScore;

    @Size(max = 50, message = "Platform must not exceed 50 characters")
    private String platform;
}
