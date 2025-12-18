package com.example.dating.models.onboarding.dto;

import com.example.dating.annotations.MinimumAge;
import com.example.dating.enums.user.Gender;
import com.example.dating.enums.user.SexualOrientation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class BasicProfileRequestDto {

    @NotBlank(message = "Name is required")
    private String name;

    @NotNull(message = "Date of birth is required")
    @Past(message = "Date of birth must be in the past")
    @MinimumAge(18)
    private LocalDate dateOfBirth;

    @NotNull(message = "Gender is required")
    private Gender gender;

    @NotNull(message = "Sexual orientation is required")
    private SexualOrientation sexualOrientation;
}
