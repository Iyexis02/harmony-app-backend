package com.example.dating.services;

import com.example.dating.models.user.artists.dto.SpotifyArtistDto;
import com.example.dating.models.user.common.dto.SpotifyUserProfile;
import com.example.dating.models.user.tracks.dto.SpotifyTrackDto;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.List;

public interface SpotifyService {
     SpotifyUserProfile getCurrentUserProfile(String userId);
     SpotifyArtistDto getTopArtists(String spotifyToken, Integer limit, String time_range, Integer offset) throws JsonProcessingException;
     SpotifyTrackDto getTopTracks(String spotifyToken, Integer limit, String time_range, Integer offset) throws JsonProcessingException;
     List<String> getGenresFromTopArtists(String spotifyToken, Integer limit, String timeRange) throws JsonProcessingException;
}
