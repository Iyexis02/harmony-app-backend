package com.example.dating.models.spotify.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for Spotify Client Credentials token request.
 * Used for app-level authentication (no user context).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientCredentialsTokenResponse {

    @JsonProperty("access_token")
    private String accessToken;

    @JsonProperty("token_type")
    private String tokenType;

    @JsonProperty("expires_in")
    private Long expiresIn;
}
