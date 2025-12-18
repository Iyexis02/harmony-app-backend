package com.example.dating.models.spotify.app.dto;

import com.example.dating.models.user.domain.SpotifyArtist;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Paginated artist search results from Spotify API.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArtistSearchResult {

    @JsonProperty("href")
    private String href;

    @JsonProperty("items")
    private List<SpotifyArtist> items;

    @JsonProperty("limit")
    private Integer limit;

    @JsonProperty("next")
    private String next;

    @JsonProperty("offset")
    private Integer offset;

    @JsonProperty("previous")
    private String previous;

    @JsonProperty("total")
    private Integer total;
}
