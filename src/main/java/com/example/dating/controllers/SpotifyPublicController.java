package com.example.dating.controllers;

import com.example.dating.models.spotify.app.dto.ArtistSearchResult;
import com.example.dating.models.spotify.app.dto.TrackSearchResult;
import com.example.dating.models.user.domain.SpotifyArtist;
import com.example.dating.services.SpotifyAppService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.example.dating.constants.AppConstants.BASE_API_ROUTE;

/**
 * Public controller for Spotify search and browse operations.
 * These endpoints do NOT require user authentication and can be used by:
 * - Unauthenticated users during onboarding
 * - Users who registered with email/password (no Spotify account)
 * - Anyone searching for artists/tracks/genres
 *
 * Uses app-level Spotify authentication (Client Credentials flow).
 */
@Tag(name = "Spotify (Public)", description = "Public artist and track search — no authentication required")
@Slf4j
@RequiredArgsConstructor
@RequestMapping(BASE_API_ROUTE + "/spotify")
@RestController
public class SpotifyPublicController {

    private final SpotifyAppService spotifyAppService;

    /**
     * Search for artists by name or keyword.
     * Public endpoint - no authentication required.
     *
     * @param query Search query (e.g., "Coldplay", "rock bands")
     * @param limit Number of results (default: 20, max: 50)
     * @param offset Pagination offset (default: 0)
     * @return Paginated artist search results
     */
    @GetMapping("/search/artists")
    public ResponseEntity<ArtistSearchResult> searchArtists(
            @RequestParam String query,
            @RequestParam(required = false, defaultValue = "20") Integer limit,
            @RequestParam(required = false, defaultValue = "0") Integer offset
    ) {
        try {
            log.info("Public artist search request - query: {}, limit: {}, offset: {}", query, limit, offset);

            if (query == null || query.trim().isEmpty()) {
                log.warn("Empty search query provided");
                return ResponseEntity.badRequest().build();
            }

            if (limit != null && (limit < 1 || limit > 50)) {
                log.warn("Invalid limit provided: {}. Must be between 1 and 50", limit);
                return ResponseEntity.badRequest().build();
            }

            ArtistSearchResult result = spotifyAppService.searchArtists(query, limit, offset);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Error searching artists with query: {}", query, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Search for tracks by name, artist, or keyword.
     * Public endpoint - no authentication required.
     *
     * @param query Search query (e.g., "Bohemian Rhapsody", "happy songs")
     * @param limit Number of results (default: 20, max: 50)
     * @param offset Pagination offset (default: 0)
     * @return Paginated track search results
     */
    @GetMapping("/search/tracks")
    public ResponseEntity<TrackSearchResult> searchTracks(
            @RequestParam String query,
            @RequestParam(required = false, defaultValue = "20") Integer limit,
            @RequestParam(required = false, defaultValue = "0") Integer offset
    ) {
        try {
            log.info("Public track search request - query: {}, limit: {}, offset: {}", query, limit, offset);

            if (query == null || query.trim().isEmpty()) {
                log.warn("Empty search query provided");
                return ResponseEntity.badRequest().build();
            }

            if (limit != null && (limit < 1 || limit > 50)) {
                log.warn("Invalid limit provided: {}. Must be between 1 and 50", limit);
                return ResponseEntity.badRequest().build();
            }

            TrackSearchResult result = spotifyAppService.searchTracks(query, limit, offset);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Error searching tracks with query: {}", query, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get list of available genre seeds from Spotify.
     * Public endpoint - no authentication required.
     * Useful for genre selection during user onboarding.
     *
     * @return List of available genre strings
     */
    @GetMapping("/genres")
    public ResponseEntity<List<String>> getAvailableGenres() {
        try {
            log.info("Public request for available genres");

            List<String> genres = spotifyAppService.getAvailableGenres();
            return ResponseEntity.ok(genres);

        } catch (Exception e) {
            log.error("Error fetching available genres", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get detailed artist information by Spotify ID.
     * Public endpoint - no authentication required.
     *
     * @param artistId Spotify artist ID
     * @return Artist details
     */
    @GetMapping("/artists/{artistId}")
    public ResponseEntity<SpotifyArtist> getArtistById(@PathVariable String artistId) {
        try {
            log.info("Public request for artist details - ID: {}", artistId);

            if (artistId == null || artistId.trim().isEmpty()) {
                log.warn("Empty artist ID provided");
                return ResponseEntity.badRequest().build();
            }

            SpotifyArtist artist = spotifyAppService.getArtistById(artistId);
            return ResponseEntity.ok(artist);

        } catch (RuntimeException e) {
            if (e.getMessage().contains("not found")) {
                log.warn("Artist not found: {}", artistId);
                return ResponseEntity.notFound().build();
            }
            log.error("Error fetching artist with ID: {}", artistId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } catch (Exception e) {
            log.error("Unexpected error fetching artist with ID: {}", artistId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
