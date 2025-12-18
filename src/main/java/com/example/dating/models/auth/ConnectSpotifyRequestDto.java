package com.example.dating.models.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectSpotifyRequestDto {
    private String spotifyId;
    private String spotifyAccessToken;
    private String spotifyRefreshToken;
    private Long spotifyTokenExpiresAt;
}
