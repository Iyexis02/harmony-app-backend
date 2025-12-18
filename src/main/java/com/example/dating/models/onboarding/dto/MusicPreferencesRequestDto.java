package com.example.dating.models.onboarding.dto;

import com.example.dating.enums.user.ConcertFrequency;
import com.example.dating.enums.user.MusicImportance;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class MusicPreferencesRequestDto {

    @NotNull(message = "Favorite genres are required")
    private List<String> favoriteGenres;

    @NotNull(message = "Concert frequency is required")
    private ConcertFrequency concertFrequency;

    @NotNull(message = "Music importance is required")
    private MusicImportance musicImportance;

    private List<String> favoriteDecades;

    @NotNull(message = "Open to new genres preference is required")
    private Boolean openToNewGenres;

    private List<String> listeningTimes;

    @Min(value = 0, message = "Hours per day must be at least 0")
    @Max(value = 24, message = "Hours per day cannot exceed 24")
    private Integer hoursPerDay;
}
