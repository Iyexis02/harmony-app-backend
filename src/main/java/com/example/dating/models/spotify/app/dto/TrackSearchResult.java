package com.example.dating.models.spotify.app.dto;

import com.example.dating.models.user.tracks.dao.SpotifyTrack;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Paginated track search results from Spotify API.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrackSearchResult {

    @JsonProperty("href")
    private String href;

    @JsonProperty("items")
    private List<SpotifyTrack> items;

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
