package com.example.dating.services.impl;

import com.example.dating.models.spotify.app.dto.ArtistSearchResult;
import com.example.dating.models.spotify.app.dto.ClientCredentialsTokenResponse;
import com.example.dating.models.spotify.app.dto.GenreSeedsResponse;
import com.example.dating.models.spotify.app.dto.SpotifySearchResponse;
import com.example.dating.models.spotify.app.dto.TrackSearchResult;
import com.example.dating.models.user.domain.SpotifyArtist;
import com.example.dating.services.SpotifyAppService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static com.example.dating.constants.SpotifyConstants.SPOTIFY_API_BASE_URL;

/**
 * Implementation of SpotifyAppService using Client Credentials OAuth2 flow.
 * This service authenticates as the application itself (not a user) and provides
 * access to public Spotify data without requiring user login.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpotifyAppServiceImpl implements SpotifyAppService {

    @Value("${spotify.client.id}")
    private String clientId;

    @Value("${spotify.client.secret}")
    private String clientSecret;

    private final ObjectMapper objectMapper;

    // Cache the app-level token since it's valid for 1 hour
    private String cachedAppToken;
    private Instant tokenExpiresAt;

    /**
     * Gets an app-level access token using Client Credentials flow.
     * This is a machine-to-machine authentication - NO USER INVOLVED.
     *
     * The token is cached and automatically refreshed when it expires.
     *
     * This token can access:
     * - Search endpoints
     * - Public artist/track/album data
     * - Genre lists
     * - Browse categories
     *
     * This token CANNOT access:
     * - User's personal data (/me endpoints)
     * - User's playlists, saved tracks, etc.
     */
    private String getAppAccessToken() {
        // Return cached token if still valid (with 60-second buffer)
        if (cachedAppToken != null && Instant.now().isBefore(tokenExpiresAt.minusSeconds(60))) {
            log.debug("Using cached app-level Spotify access token");
            return cachedAppToken;
        }

        log.info("Fetching new app-level Spotify access token using Client Credentials");

        try {
            // Step 1: Create Basic Auth header with client credentials
            String credentials = clientId + ":" + clientSecret;
            String base64Credentials = Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set("Authorization", "Basic " + base64Credentials);

            // Step 2: Request body with grant_type=client_credentials
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "client_credentials");

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

            // Step 3: Make the request to Spotify token endpoint
            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<ClientCredentialsTokenResponse> response = restTemplate.postForEntity(
                    "https://accounts.spotify.com/api/token",
                    request,
                    ClientCredentialsTokenResponse.class
            );

            // Step 4: Cache the token
            ClientCredentialsTokenResponse tokenResponse = response.getBody();
            if (tokenResponse == null) {
                throw new RuntimeException("Received null response from Spotify token endpoint");
            }

            cachedAppToken = tokenResponse.getAccessToken();
            tokenExpiresAt = Instant.now().plusSeconds(tokenResponse.getExpiresIn());

            log.info("Successfully obtained app-level access token, expires at: {}", tokenExpiresAt);

            return cachedAppToken;

        } catch (HttpClientErrorException e) {
            log.error("Failed to obtain app-level Spotify access token. Status: {}, Body: {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Failed to authenticate with Spotify: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error obtaining app-level Spotify access token", e);
            throw new RuntimeException("Failed to authenticate with Spotify", e);
        }
    }

    /**
     * Creates HTTP headers with Bearer token for Spotify API requests.
     */
    private HttpHeaders createAuthHeaders() {
        String appToken = getAppAccessToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(appToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Override
    public ArtistSearchResult searchArtists(String query, Integer limit, Integer offset) {
        log.info("Searching artists with query: {}, limit: {}, offset: {}", query, limit, offset);

        try {
            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Void> request = new HttpEntity<>(headers);

            // Build URL with query parameters
            UriComponentsBuilder builder = UriComponentsBuilder
                    .fromHttpUrl(SPOTIFY_API_BASE_URL + "/search")
                    .queryParam("q", query)
                    .queryParam("type", "artist")
                    .queryParam("limit", limit != null ? limit : 20)
                    .queryParam("offset", offset != null ? offset : 0);

            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<SpotifySearchResponse> response = restTemplate.exchange(
                    builder.toUriString(),
                    HttpMethod.GET,
                    request,
                    SpotifySearchResponse.class
            );

            if (response.getBody() == null || response.getBody().getArtists() == null) {
                log.warn("Received null or empty artist search results for query: {}", query);
                return ArtistSearchResult.builder()
                        .items(Collections.emptyList())
                        .total(0)
                        .limit(limit != null ? limit : 20)
                        .offset(offset != null ? offset : 0)
                        .build();
            }

            log.info("Successfully found {} artists for query: {}",
                    response.getBody().getArtists().getTotal(), query);
            return response.getBody().getArtists();

        } catch (HttpClientErrorException e) {
            log.error("Failed to search artists. Status: {}, Body: {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Failed to search Spotify artists: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error searching artists with query: {}", query, e);
            throw new RuntimeException("Failed to search Spotify artists", e);
        }
    }

    @Override
    public TrackSearchResult searchTracks(String query, Integer limit, Integer offset) {
        log.info("Searching tracks with query: {}, limit: {}, offset: {}", query, limit, offset);

        try {
            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Void> request = new HttpEntity<>(headers);

            // Build URL with query parameters
            UriComponentsBuilder builder = UriComponentsBuilder
                    .fromHttpUrl(SPOTIFY_API_BASE_URL + "/search")
                    .queryParam("q", query)
                    .queryParam("type", "track")
                    .queryParam("limit", limit != null ? limit : 20)
                    .queryParam("offset", offset != null ? offset : 0);

            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<SpotifySearchResponse> response = restTemplate.exchange(
                    builder.toUriString(),
                    HttpMethod.GET,
                    request,
                    SpotifySearchResponse.class
            );

            if (response.getBody() == null || response.getBody().getTracks() == null) {
                log.warn("Received null or empty track search results for query: {}", query);
                return TrackSearchResult.builder()
                        .items(Collections.emptyList())
                        .total(0)
                        .limit(limit != null ? limit : 20)
                        .offset(offset != null ? offset : 0)
                        .build();
            }

            log.info("Successfully found {} tracks for query: {}",
                    response.getBody().getTracks().getTotal(), query);
            return response.getBody().getTracks();

        } catch (HttpClientErrorException e) {
            log.error("Failed to search tracks. Status: {}, Body: {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Failed to search Spotify tracks: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error searching tracks with query: {}", query, e);
            throw new RuntimeException("Failed to search Spotify tracks", e);
        }
    }

    @Override
    public List<String> getAvailableGenres() {
        log.info("Fetching available genre seeds from Spotify");

        try {
            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Void> request = new HttpEntity<>(headers);

            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<GenreSeedsResponse> response = restTemplate.exchange(
                    SPOTIFY_API_BASE_URL + "/recommendations/available-genre-seeds",
                    HttpMethod.GET,
                    request,
                    GenreSeedsResponse.class
            );

            if (response.getBody() == null || response.getBody().getGenres() == null) {
                log.warn("Received null or empty genre seeds response");
                return Collections.emptyList();
            }

            log.info("Successfully fetched {} available genres", response.getBody().getGenres().size());
            return response.getBody().getGenres();

        } catch (HttpClientErrorException e) {
            log.error("Failed to fetch available genres. Status: {}, Body: {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Failed to fetch Spotify genres: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error fetching available genres", e);
            throw new RuntimeException("Failed to fetch Spotify genres", e);
        }
    }

    @Override
    public SpotifyArtist getArtistById(String artistId) {
        log.info("Fetching artist details for ID: {}", artistId);

        try {
            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Void> request = new HttpEntity<>(headers);

            String url = SPOTIFY_API_BASE_URL + "/artists/" + artistId;

            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    request,
                    String.class
            );

            // Parse JSON response manually (similar to existing SpotifyServiceImpl pattern)
            JsonNode jsonNode = objectMapper.readTree(response.getBody());

            SpotifyArtist artist = SpotifyArtist.builder()
                    .id(jsonNode.get("id").asText())
                    .name(jsonNode.get("name").asText())
                    .genres(extractGenres(jsonNode))
                    .href(jsonNode.get("href").asText())
                    .popularity(jsonNode.get("popularity").asInt())
                    .type(jsonNode.get("type").asText())
                    .uri(jsonNode.get("uri").asText())
                    .build();

            log.info("Successfully fetched artist: {} ({})", artist.getName(), artistId);
            return artist;

        } catch (HttpClientErrorException.NotFound e) {
            log.error("Artist not found with ID: {}", artistId);
            throw new RuntimeException("Artist not found: " + artistId, e);
        } catch (HttpClientErrorException e) {
            log.error("Failed to fetch artist details. Status: {}, Body: {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Failed to fetch Spotify artist: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error fetching artist with ID: {}", artistId, e);
            throw new RuntimeException("Failed to fetch Spotify artist", e);
        }
    }

    /**
     * Helper method to extract genres from JSON node.
     */
    private List<String> extractGenres(JsonNode node) {
        if (!node.has("genres") || !node.get("genres").isArray()) {
            return Collections.emptyList();
        }

        return node.get("genres").findValuesAsText("").stream()
                .filter(genre -> !genre.isEmpty())
                .toList();
    }
}
