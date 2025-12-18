package com.example.dating.models.onboarding.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PrivacySettingsResponseDto {
    private Boolean isProfilePublic;
    private Boolean showAge;
    private Boolean showDistance;
    private Boolean showLastActive;
    private Boolean discoverable;
    private Boolean showLikedByYou;
    private Boolean showSpotifyProfile;
    private Boolean showMusicStats;
    private Boolean incognitoMode;
    private Boolean readReceipts;
}
