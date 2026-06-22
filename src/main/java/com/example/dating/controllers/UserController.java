package com.example.dating.controllers;

import com.example.dating.exceptions.UserNotFoundException;
import com.example.dating.mappers.UserMapper;
import com.example.dating.models.user.domain.User;
import com.example.dating.models.user.common.dto.SpotifyUserProfile;
import com.example.dating.models.user.artists.dto.SpotifyArtistDto;
import com.example.dating.models.user.tracks.dto.SpotifyTrackDto;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.SpotifyService;
import com.example.dating.services.SpotifyTokenService;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

import static com.example.dating.constants.AppConstants.BASE_API_ROUTE;
import static com.example.dating.constants.SpotifyConstants.*;

@Tag(name = "User", description = "Current user profile, Spotify top artists and tracks")
@Slf4j
@RequiredArgsConstructor
@RequestMapping(BASE_API_ROUTE + "/user")
@RestController
public class UserController {

    private final UserJpaRepository userJpaRepository;
    private final UserMapper userMapper;
    private final SpotifyTokenService spotifyTokenService;
    private final SpotifyService spotifyService;

    @GetMapping("")
    public ResponseEntity<SpotifyUserProfile> getUserProfile(Authentication authentication) {
        User user = resolveUser(authentication);
        String spotifyToken = spotifyTokenService.getValidSpotifyToken(user);
        return ResponseEntity.ok(spotifyService.getCurrentUserProfile(spotifyToken));
    }

    @GetMapping("/artists")
    public ResponseEntity<SpotifyArtistDto> getTopArtists(
            Authentication authentication,
            @RequestParam("limit") Optional<Integer> limit_param,
            @RequestParam("time_range") Optional<String> time_range_param,
            @RequestParam("offset") Optional<Integer> offset_param) throws JsonProcessingException {
        User user = resolveUser(authentication);
        String spotifyToken = spotifyTokenService.getValidSpotifyToken(user);
        return ResponseEntity.ok(spotifyService.getTopArtists(
                spotifyToken,
                limit_param.orElse(DEFAULT_LIMIT),
                time_range_param.orElse(DEFAULT_TIME_RANGE),
                offset_param.orElse(DEFAULT_OFFSET)));
    }

    @GetMapping("/tracks")
    public ResponseEntity<SpotifyTrackDto> getTopTracks(
            Authentication authentication,
            @RequestParam("limit") Optional<Integer> limit_param,
            @RequestParam("time_range") Optional<String> time_range_param,
            @RequestParam("offset") Optional<Integer> offset_param) throws JsonProcessingException {
        User user = resolveUser(authentication);
        String spotifyToken = spotifyTokenService.getValidSpotifyToken(user);
        return ResponseEntity.ok(spotifyService.getTopTracks(
                spotifyToken,
                limit_param.orElse(DEFAULT_LIMIT),
                time_range_param.orElse(DEFAULT_TIME_RANGE),
                offset_param.orElse(DEFAULT_OFFSET)));
    }

    @GetMapping("/genres")
    public ResponseEntity<List<String>> getSuggestedGenres(
            Authentication authentication,
            @RequestParam("limit") Optional<Integer> limit_param,
            @RequestParam("time_range") Optional<String> time_range_param) throws JsonProcessingException {
        User user = resolveUser(authentication);
        String spotifyToken = spotifyTokenService.getValidSpotifyToken(user);
        return ResponseEntity.ok(spotifyService.getGenresFromTopArtists(
                spotifyToken,
                limit_param.orElse(DEFAULT_LIMIT),
                time_range_param.orElse(DEFAULT_TIME_RANGE)));
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    /** Resolves the authenticated user from DB. Throws UserNotFoundException if the account no longer exists. */
    private User resolveUser(Authentication authentication) {
        String userId = ((Jwt) authentication.getPrincipal()).getClaimAsString("userId");
        return userJpaRepository.findById(userId)
                .map(userMapper::toDomain)
                .orElseThrow(() -> new UserNotFoundException("User not found"));
    }
}
