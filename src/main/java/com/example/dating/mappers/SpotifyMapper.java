package com.example.dating.mappers;

import com.example.dating.models.user.artists.dto.SimplifiedArtistDto;
import com.example.dating.models.user.domain.SpotifyArtist;
import com.example.dating.models.user.tracks.dao.SpotifyTrack;
import com.example.dating.models.user.tracks.dto.SimplifiedTrackDto;
import org.springframework.stereotype.Component;

@Component
public class SpotifyMapper {

    /* Domain -> DTO */
    public SimplifiedArtistDto toSpotifyArtistDto(SpotifyArtist artist) {
        return SimplifiedArtistDto.builder()
                .id(artist.getId())
                .genres(artist.getGenres())
                .images(artist.getImages())
                .name(artist.getName())
                .build();
    }

    /* Domain -> DTO */
    public SimplifiedTrackDto toSpotifyTrackDto(SpotifyTrack track) {
        return SimplifiedTrackDto.builder()
                .id(track.getId())
                .artists(track.getArtists())
                .duration_ms(track.getDuration_ms())
                .name(track.getName())
                .build();
    }
}
