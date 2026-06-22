package com.example.dating.services.impl;

import com.example.dating.enums.TopItemsEndpoint;
import com.example.dating.mappers.SpotifyMapper;
import com.example.dating.models.user.artists.dao.SpotifyImage;
import com.example.dating.models.user.artists.dao.SimplifiedArtistResponse;
import com.example.dating.models.user.artists.dto.SimplifiedArtistDto;
import com.example.dating.models.user.artists.dto.SpotifyArtistDto;
import com.example.dating.models.user.domain.SpotifyArtist;
import com.example.dating.models.user.common.dto.SpotifyUserProfile;
import com.example.dating.models.user.artists.dao.SpotifyExternalUrl;
import com.example.dating.models.user.artists.dao.SpotifyArtistFollower;
import com.example.dating.models.user.tracks.dao.SpotifyAlbum;
import com.example.dating.models.user.tracks.dao.SpotifyExternalId;
import com.example.dating.models.user.tracks.dao.SpotifyTrack;
import com.example.dating.models.user.tracks.dto.SimplifiedTrackDto;
import com.example.dating.models.user.tracks.dto.SpotifyTrackDto;
import com.example.dating.exceptions.SpotifyApiException;
import com.example.dating.services.SpotifyService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static com.example.dating.constants.SpotifyConstants.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class SpotifyServiceImpl implements SpotifyService {

    private final ObjectMapper objectMapper;
    private final SpotifyMapper spotifyMapper;
    private final RestTemplate spotifyRestTemplate;

    @CircuitBreaker(name = "spotify-data", fallbackMethod = "getCurrentUserProfileFallback")
    public SpotifyUserProfile getCurrentUserProfile(String accessToken) {
        try {
            String url = SPOTIFY_API_BASE_URL + "/me";
            HttpHeaders headers = createHeaders(accessToken);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = spotifyRestTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    String.class
            );

            JsonNode jsonNode = objectMapper.readTree(response.getBody());

            return SpotifyUserProfile.builder()
                    .id(jsonNode.get("id").asText())
                    .displayName(jsonNode.get("display_name").asText())
                    .email(jsonNode.has("email") ? jsonNode.get("email").asText() : null)
                    .country(jsonNode.has("country") ? jsonNode.get("country").asText() : null)
                    .followers(jsonNode.has("followers") ? jsonNode.get("followers").get("total").asInt() : 0)
                    .imageUrl(extractImageUrl(jsonNode))
                    .build();

        } catch (HttpClientErrorException.Unauthorized e) {
            log.error("Spotify access token is invalid or expired");
            throw new RuntimeException("Spotify token expired", e);
        } catch (Exception e) {
            log.error("Error fetching Spotify user profile: {}", e.getMessage());
            throw new RuntimeException("Failed to fetch Spotify profile", e);
        }
    }

    @Override
    @CircuitBreaker(name = "spotify-data", fallbackMethod = "getTopArtistsFallback")
    public SpotifyArtistDto getTopArtists(String accessToken, Integer limit, String time_range, Integer offset) {
        try {

            String url = buildTopItemsUrl(limit, time_range, offset, TopItemsEndpoint.ARTISTS);

            HttpHeaders headers = createHeaders(accessToken);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = spotifyRestTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    String.class
            );

            JsonNode jsonNode = objectMapper.readTree(response.getBody());
            JsonNode items = jsonNode.get("items");
            Integer total = jsonNode.get("total").asInt();
            List<SpotifyArtist> artists = new ArrayList<>();
            if (items != null && items.isArray()) {
                for (JsonNode item : items) {
                    artists.add(SpotifyArtist.builder()
                            .id(item.get("id").asText())
                            .name(item.get("name").asText())
                            .genres(extractGenres(item))
                            .images(extractImages(item))
                            .external_urls(extractExternalUrls(item))
                            .followers(extractFollowers(item))
                            .href(item.get("href").asText())
                            .popularity(item.get("popularity").asInt())
                            .type(item.get("type").asText())
                            .uri(item.get("uri").asText())
                            .build()
                    );
                }
            }

            List<SimplifiedArtistDto> artistDtos = artists.stream()
                    .map(spotifyMapper::toSpotifyArtistDto)
                    .toList();


            return SpotifyArtistDto.builder()
                    .total(total)
                    .artists(artistDtos)
                    .build();

        } catch (HttpClientErrorException.Unauthorized e) {
            log.error("Spotify access token is invalid or expired");
            throw new RuntimeException("Spotify token expired", e);
        } catch (Exception e) {
            log.error("Error fetching Spotify user profile: {}", e.getMessage());
            throw new RuntimeException("Failed to fetch Spotify profile", e);
        }
    }

    @Override
    @CircuitBreaker(name = "spotify-data", fallbackMethod = "getTopTracksFallback")
    public SpotifyTrackDto getTopTracks(String accessToken, Integer limit, String time_range, Integer offset) throws JsonProcessingException {
        String url = buildTopItemsUrl(limit, time_range, offset, TopItemsEndpoint.TRACKS);

        HttpHeaders headers = createHeaders(accessToken);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = spotifyRestTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                String.class
        );

        JsonNode jsonNode = objectMapper.readTree(response.getBody());
        JsonNode items = jsonNode.get("items");
        Integer total = jsonNode.get("total").asInt();
        List<SpotifyTrack> tracks = new ArrayList<>();
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                tracks.add(SpotifyTrack.builder()
                        .artists(extractArtists((item)))
                        .album(extractAlbum(item.get("album")))
                        .available_markets(extractAvailableMarkets(item))
                        .disc_number(item.get("disc_number").asInt())
                        .duration_ms(item.get("duration_ms").asInt())
                        .explicit(item.get("explicit").asBoolean())
                        .is_playable(item.get("is_playable").asBoolean())
                        .external_ids(extractExternalId(item))
                        .track_number(item.get("track_number").asInt())
                        .id(item.get("id").asText())
                        .name(item.get("name").asText())
                        .external_urls(extractExternalUrls(item))
                        .href(item.get("href").asText())
                        .popularity(item.get("popularity").asInt())
                        .type(item.get("type").asText())
                        .uri(item.get("uri").asText())
                        .build()
                );
            }
        }

        List<SimplifiedTrackDto> trackDtos = tracks.stream()
                .map(spotifyMapper::toSpotifyTrackDto)
                .toList();


        return SpotifyTrackDto.builder()
                .total(total)
                .tracks(trackDtos)
                .build();

    }

    @Override
    public List<String> getGenresFromTopArtists(String accessToken, Integer limit, String timeRange) throws JsonProcessingException {
        SpotifyArtistDto artistsDto = getTopArtists(accessToken, limit, timeRange, 0);
        return artistsDto.artists().stream()
                .flatMap(artist -> artist.getGenres() != null ? artist.getGenres().stream() : new ArrayList<String>().stream())
                .distinct()
                .sorted()
                .toList();
    }

    // ── Circuit breaker fallbacks ─────────────────────────────────────────────

    private SpotifyUserProfile getCurrentUserProfileFallback(String accessToken, Throwable t) {
        log.warn("Spotify circuit open for getCurrentUserProfile: {}", t.getMessage());
        throw new SpotifyApiException("Spotify service temporarily unavailable — circuit open");
    }

    private SpotifyArtistDto getTopArtistsFallback(String accessToken, Integer limit, String timeRange, Integer offset, Throwable t) {
        log.warn("Spotify circuit open for getTopArtists: {}", t.getMessage());
        throw new SpotifyApiException("Spotify service temporarily unavailable — circuit open");
    }

    private SpotifyTrackDto getTopTracksFallback(String accessToken, Integer limit, String timeRange, Integer offset, Throwable t) {
        log.warn("Spotify circuit open for getTopTracks: {}", t.getMessage());
        throw new SpotifyApiException("Spotify service temporarily unavailable — circuit open");
    }

    private HttpHeaders createHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String buildTopItemsUrl(Integer limit, String timeRange, Integer offset, TopItemsEndpoint type) {
        String endpoint = type.equals(TopItemsEndpoint.ARTISTS) ? "artists" : "tracks";
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(SPOTIFY_API_BASE_URL + "/me/top/" + endpoint);

        if (!Objects.equals(limit, DEFAULT_LIMIT)) {
            builder.queryParam("limit", limit);
        }

        if (!Objects.equals(timeRange, DEFAULT_TIME_RANGE)) {
            builder.queryParam("time_range", timeRange);
        }

        if (!Objects.equals(offset, DEFAULT_OFFSET)) {
            builder.queryParam("offset", offset);
        }

        return builder.toUriString();
    }

    private String extractImageUrl(JsonNode node) {
        if (node.has("images") && node.get("images").isArray() && !node.get("images").isEmpty()) {
            return node.get("images").get(0).get("url").asText();
        }
        return null;
    }

    private List<SpotifyImage> extractImages(JsonNode node) {
        List<SpotifyImage> images = new ArrayList<>();
        if (node.has("images") && node.get("images").isArray() && !node.get("images").isEmpty()) {
            for (JsonNode image : node.get("images")) {
                SpotifyImage artistImage = new SpotifyImage();
                artistImage.setHeight(image.get("height").asInt());
                artistImage.setWidth(image.get("width").asInt());
                artistImage.setUrl(image.get("url").asText());
                images.add(artistImage);
            }
        }
        return images;
    }

    private List<String> extractGenres(JsonNode node) {
        List<String> genres = new ArrayList<>();
        if (node.has("genres") && node.get("genres").isArray()) {
            for (JsonNode genre : node.get("genres")) {
                genres.add(genre.asText());
            }
        }
        return genres;
    }

    private SpotifyExternalUrl extractExternalUrls(JsonNode node) {
        SpotifyExternalUrl externalUrl = new SpotifyExternalUrl();
        if (node.has("external_urls") && !node.get("external_urls").isEmpty()) {
            externalUrl.setSpotify(node.get("external_urls").get("spotify").asText());
        }
        return externalUrl;
    }

    private SpotifyArtistFollower extractFollowers(JsonNode node) {
        SpotifyArtistFollower spotifyArtistFollower = new SpotifyArtistFollower();
        if (node.has("followers") && !node.get("followers").isEmpty()) {
            spotifyArtistFollower.setHref(node.get("followers").get("href").asText());
            spotifyArtistFollower.setTotal(node.get("followers").get("total").asInt());
        }
        return spotifyArtistFollower;
    }

    private SpotifyExternalId extractExternalId(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }

        return SpotifyExternalId.builder()
                .isrc(getTextOrNull(node, "isrc"))
                .ean(getTextOrNull(node, "ean"))
                .upc(getTextOrNull(node, "upc"))
                .build();
    }

    private SpotifyAlbum extractAlbum(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }

        return SpotifyAlbum.builder()
                .album_type(getTextOrNull(node, "album_type"))
                .artists(extractArtists(node))
                .available_markets(extractAvailableMarkets(node))
                .external_urls(extractExternalUrls(node))
                .href(getTextOrNull(node, "href"))
                .id(getTextOrNull(node, "id"))
                .images(extractImages(node))
                .is_playable(Boolean.TRUE.equals(getBooleanOrNull(node, "is_playable")))
                .name(getTextOrNull(node, "name"))
                .release_date(getTextOrNull(node, "release_date"))
                .release_date_precision(getTextOrNull(node, "release_date_precision"))
                .type(getTextOrNull(node, "type"))
                .uri(getTextOrNull(node, "uri"))
                .total_tracks(getIntOrNull(node, "total_tracks"))
                .build();
    }

    // Extract artists from album
    private List<SimplifiedArtistResponse> extractArtists(JsonNode node) {
        if (!node.has("artists") || !node.get("artists").isArray()) {
            return Collections.emptyList();
        }

        List<SimplifiedArtistResponse> artists = new ArrayList<>();
        for (JsonNode artistNode : node.get("artists")) {
            SimplifiedArtistResponse artist = SimplifiedArtistResponse.builder()
                    .id(getTextOrNull(artistNode, "id"))
                    .name(getTextOrNull(artistNode, "name"))
                    .href(getTextOrNull(artistNode, "href"))
                    .type(getTextOrNull(artistNode, "type"))
                    .uri(getTextOrNull(artistNode, "uri"))
                    .external_urls(extractExternalUrls(artistNode))
                    .build();

            artists.add(artist);
        }

        return artists;
    }

    // Extract available markets
    private List<String> extractAvailableMarkets(JsonNode node) {
        if (!node.has("available_markets") || !node.get("available_markets").isArray()) {
            return Collections.emptyList();
        }

        List<String> markets = new ArrayList<>();
        for (JsonNode market : node.get("available_markets")) {
            markets.add(market.asText());
        }
        return markets;
    }

    // Helper methods to reduce repetition
    private String getTextOrNull(JsonNode node, String fieldName) {
        return node.has(fieldName) && !node.get(fieldName).isNull()
                ? node.get(fieldName).asText()
                : null;
    }

    private Integer getIntOrNull(JsonNode node, String fieldName) {
        return node.has(fieldName) && !node.get(fieldName).isNull()
                ? node.get(fieldName).asInt()
                : null;
    }

    private Boolean getBooleanOrNull(JsonNode node, String fieldName) {
        return node.has(fieldName) && !node.get(fieldName).isNull()
                ? node.get(fieldName).asBoolean()
                : null;
    }
}

