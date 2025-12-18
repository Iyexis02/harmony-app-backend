package com.example.dating.controllers;

import com.example.dating.models.user.domain.User;
import com.example.dating.mappers.SpotifyMapper;
import com.example.dating.models.user.common.dto.SpotifyUserProfile;
import com.example.dating.models.user.artists.dto.SpotifyArtistDto;
import com.example.dating.models.user.tracks.dto.SpotifyTrackDto;
import com.example.dating.postgres.UserRepository;
import com.example.dating.services.JwtService;
import com.example.dating.services.SpotifyService;
import com.example.dating.services.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

import static com.example.dating.constants.AppConstants.BASE_API_ROUTE;
import static com.example.dating.constants.SpotifyConstants.*;

@Slf4j
@RequiredArgsConstructor
@RequestMapping(BASE_API_ROUTE + "/user")
@RestController
public class UserController {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final UserService userService;
    private final SpotifyService spotifyService;

    @GetMapping("")
    public ResponseEntity<SpotifyUserProfile> getUserProfile(@RequestHeader("Authorization") String authHeader) {
        try {
            String jwt = authHeader.replace("Bearer ", "");
            String id = jwtService.getUserIdFromToken(jwt);
            if (id == null) {
                return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
            }
            Optional<User> optUser = userRepository.findById(id);
            if (optUser.isEmpty()) {
                return new ResponseEntity<>(HttpStatus.NOT_FOUND);
            }
            User user = optUser.get();
            String spotifyToken = userService.getValidSpotifyToken(user);

            return ResponseEntity.ok(spotifyService.getCurrentUserProfile(spotifyToken));
        } catch (JwtException e) {
            log.error("Error while trying to decode jwt{}", String.valueOf(e));
            throw new RuntimeException(e);
        }
        catch (Exception e) {
            log.error("Unexpected error while to get user profile{}", String.valueOf(e));
            throw new RuntimeException(e);
        }
    }

    @GetMapping("/artists")
    public ResponseEntity<SpotifyArtistDto> getTopArtists(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam("limit") Optional<Integer> limit_param,
            @RequestParam("time_range") Optional<String> time_range_param,
            @RequestParam("offset") Optional<Integer> offset_param
    ) {
        try {

            Integer limit = limit_param.orElse(DEFAULT_LIMIT);
            String time_range = time_range_param.orElse(DEFAULT_TIME_RANGE);
            Integer offset = offset_param.orElse(DEFAULT_OFFSET);

            String jwt = authHeader.replace("Bearer ", "");
            String id = jwtService.getUserIdFromToken(jwt);

            Optional<User> userOpt = userRepository.findById(id);
            if (userOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }

            User user = userOpt.get();

            // Automatically handles token refresh
            String spotifyToken = userService.getValidSpotifyToken(user);

            SpotifyArtistDto topArtists = spotifyService.getTopArtists(spotifyToken, limit, time_range, offset);


            return ResponseEntity.ok(topArtists);

        } catch (Exception e) {
            log.error("Error fetching top artists: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/tracks")
    public ResponseEntity<SpotifyTrackDto> getTopTracks(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam("limit") Optional<Integer> limit_param,
            @RequestParam("time_range") Optional<String> time_range_param,
            @RequestParam("offset") Optional<Integer> offset_param
    ) {
        try {

            Integer limit = limit_param.orElse(DEFAULT_LIMIT);
            String time_range = time_range_param.orElse(DEFAULT_TIME_RANGE);
            Integer offset = offset_param.orElse(DEFAULT_OFFSET);

            String jwt = authHeader.replace("Bearer ", "");
            String id = jwtService.getUserIdFromToken(jwt);

            Optional<User> userOpt = userRepository.findById(id);
            if (userOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }

            User user = userOpt.get();

            // Automatically handles token refresh
            String spotifyToken = userService.getValidSpotifyToken(user);

            SpotifyTrackDto topTracks = spotifyService.getTopTracks(spotifyToken, limit, time_range, offset);


            return ResponseEntity.ok(topTracks);

        } catch (Exception e) {
            log.error("Error fetching top artists: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/genres")
    public ResponseEntity<java.util.List<String>> getSuggestedGenres(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam("limit") Optional<Integer> limit_param,
            @RequestParam("time_range") Optional<String> time_range_param
    ) {
        try {
            Integer limit = limit_param.orElse(DEFAULT_LIMIT);
            String time_range = time_range_param.orElse(DEFAULT_TIME_RANGE);

            String jwt = authHeader.replace("Bearer ", "");
            String id = jwtService.getUserIdFromToken(jwt);

            Optional<User> userOpt = userRepository.findById(id);
            if (userOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }

            User user = userOpt.get();

            // Automatically handles token refresh
            String spotifyToken = userService.getValidSpotifyToken(user);

            java.util.List<String> genres = spotifyService.getGenresFromTopArtists(spotifyToken, limit, time_range);

            return ResponseEntity.ok(genres);

        } catch (Exception e) {
            log.error("Error fetching suggested genres: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
