package com.example.dating.models.spotify.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for Spotify search endpoint.
 * Contains paginated results for artists, tracks, or both.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpotifySearchResponse {

    @JsonProperty("artists")
    private ArtistSearchResult artists;

    @JsonProperty("tracks")
    private TrackSearchResult tracks;
}
