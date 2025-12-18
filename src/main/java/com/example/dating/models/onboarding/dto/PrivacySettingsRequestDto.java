package com.example.dating.models.onboarding.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PrivacySettingsRequestDto {

    @NotNull(message = "Profile public setting is required")
    private Boolean isProfilePublic;

    @NotNull(message = "Show age setting is required")
    private Boolean showAge;

    @NotNull(message = "Show distance setting is required")
    private Boolean showDistance;

    @NotNull(message = "Show last active setting is required")
    private Boolean showLastActive;

    @NotNull(message = "Discoverable setting is required")
    private Boolean discoverable;

    private Boolean showLikedByYou;

    @NotNull(message = "Show Spotify profile setting is required")
    private Boolean showSpotifyProfile;

    @NotNull(message = "Show music stats setting is required")
    private Boolean showMusicStats;

    private Boolean incognitoMode;

    @NotNull(message = "Read receipts setting is required")
    private Boolean readReceipts;
}
