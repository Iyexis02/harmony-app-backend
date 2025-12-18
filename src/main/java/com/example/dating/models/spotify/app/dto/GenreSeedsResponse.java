package com.example.dating.models.spotify.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO for Spotify available genre seeds endpoint.
 * Returns a list of available genres for recommendations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenreSeedsResponse {

    @JsonProperty("genres")
    private List<String> genres;
}
