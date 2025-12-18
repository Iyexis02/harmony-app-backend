package com.example.dating.models.user.artists.dto;

import lombok.Builder;

import java.util.List;

@Builder
public record SpotifyArtistDto (Integer total, List<SimplifiedArtistDto> artists) {
}
