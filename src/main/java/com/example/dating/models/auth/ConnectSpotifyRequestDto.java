package com.example.dating.models.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectSpotifyRequestDto {

    @NotBlank(message = "Spotify ID is required")
    @Size(max = 255, message = "Spotify ID too long")
    private String spotifyId;

    @NotBlank(message = "Spotify access token is required")
    @Size(max = 2048, message = "Spotify access token too long")
    private String spotifyAccessToken;

    @NotBlank(message = "Spotify refresh token is required")
    @Size(max = 2048, message = "Spotify refresh token too long")
    private String spotifyRefreshToken;

    @NotNull(message = "Token expiration timestamp is required")
    private Long spotifyTokenExpiresAt;
}
