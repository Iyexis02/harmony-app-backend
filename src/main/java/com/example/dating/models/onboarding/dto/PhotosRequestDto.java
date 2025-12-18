package com.example.dating.models.onboarding.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
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
public class PhotosRequestDto {

    @NotEmpty(message = "At least one photo is required")
    @Size(min = 1, max = 6, message = "You must upload between 1 and 6 photos")
    @Valid
    private List<PhotoUploadRequestDto> photos;
}
