package com.example.dating.models.onboarding.dto;

import com.example.dating.enums.user.ConcertFrequency;
import com.example.dating.enums.user.MusicImportance;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class MusicPreferencesResponseDto {
    private List<String> favoriteGenres;
    private ConcertFrequency concertFrequency;
    private MusicImportance musicImportance;
    private List<String> favoriteDecades;
    private Boolean openToNewGenres;
    private List<String> listeningTimes;
    private Integer hoursPerDay;
}
