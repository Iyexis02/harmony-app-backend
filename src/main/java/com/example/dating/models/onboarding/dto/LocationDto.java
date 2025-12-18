package com.example.dating.models.onboarding.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.Optional;

@Builder
public record LocationDto(Optional<BigDecimal> latitude, Optional<BigDecimal> longitude, String locationCity, String locationCountry) {
}
