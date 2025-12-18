package com.example.dating.models.onboarding.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PhotoUploadRequestDto {

    @NotBlank(message = "Image URL is required")
    private String imageUrl;

    @NotNull(message = "Display order is required")
    @Min(value = 0, message = "Display order must be at least 0")
    private Integer displayOrder;

    @NotNull(message = "Primary photo setting is required")
    private Boolean isPrimary;

    @Size(max = 255, message = "Caption cannot exceed 255 characters")
    private String caption;
}
