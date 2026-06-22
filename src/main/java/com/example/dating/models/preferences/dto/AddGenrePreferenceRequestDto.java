package com.example.dating.models.preferences.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AddGenrePreferenceRequestDto {

    @NotBlank(message = "Genre name is required")
    private String genreName;

    @DecimalMin(value = "0.0", message = "Weight must be at least 0.0")
    @DecimalMax(value = "1.0", message = "Weight cannot exceed 1.0")
    private Double weight = 1.0;
}
