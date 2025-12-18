package com.example.dating.models.user.tracks.dto;

import com.example.dating.models.user.artists.dto.SimplifiedArtistDto;
import lombok.Builder;

import java.util.List;

@Builder
public record SpotifyTrackDto (Integer total, List<SimplifiedTrackDto> tracks) {
}
