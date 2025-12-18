package com.example.dating.models.user.common.dto;

import com.example.dating.enums.user.Gender;
import com.example.dating.enums.user.SexualOrientation;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;

@Builder
public record UserDtoResponse(
        String id,
        String name,
        String email,
        String imageUrl,
        BigDecimal latitude,
        BigDecimal longitude,
        String city,
        String country,
        String registrationStage,
        Gender gender,
        LocalDate dateOfBirth,
        SexualOrientation sexualOrientation
) {

}