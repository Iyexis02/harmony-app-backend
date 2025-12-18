package com.example.dating.services;

import com.example.dating.models.spotify.app.dto.ArtistSearchResult;
import com.example.dating.models.spotify.app.dto.TrackSearchResult;
import com.example.dating.models.user.domain.SpotifyArtist;

import java.util.List;

/**
 * Service for app-level Spotify operations using Client Credentials flow.
 * This service does not require user authentication and can be used to:
 * - Search for artists and tracks
 * - Get artist/track details
 * - Browse available genres
 * - Get recommendations (without user context)
 */
public interface SpotifyAppService {

    /**
     * Search for artists using Spotify search API.
     *
     * @param query Search query string
     * @param limit Number of results to return (max 50)
     * @param offset Offset for pagination
     * @return Paginated artist search results
     */
    ArtistSearchResult searchArtists(String query, Integer limit, Integer offset);

    /**
     * Search for tracks using Spotify search API.
     *
     * @param query Search query string
     * @param limit Number of results to return (max 50)
     * @param offset Offset for pagination
     * @return Paginated track search results
     */
    TrackSearchResult searchTracks(String query, Integer limit, Integer offset);

    /**
     * Get available genre seeds for recommendations.
     *
     * @return List of available genre strings
     */
    List<String> getAvailableGenres();

    /**
     * Get detailed artist information by Spotify ID.
     *
     * @param artistId Spotify artist ID
     * @return Artist details
     */
    SpotifyArtist getArtistById(String artistId);
}
