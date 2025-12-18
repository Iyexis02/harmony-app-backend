package com.example.dating.controllers;

import com.example.dating.exceptions.UserNotFoundException;
import com.example.dating.models.onboarding.dto.*;
import com.example.dating.models.user.common.dto.UserDtoResponse;
import com.example.dating.models.user.domain.User;
import com.example.dating.services.JwtService;
import com.example.dating.services.OnboardingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.example.dating.constants.AppConstants.BASE_API_ROUTE;

@Slf4j
@RequestMapping(BASE_API_ROUTE + "/onboarding")
@RestController
@RequiredArgsConstructor
public class OnboardingController {

    private final JwtService jwtService;
    private final OnboardingService onboardingService;

    @PutMapping("/basic-info")
    public ResponseEntity<UserDtoResponse> updateBasicInfo(@RequestHeader("Authorization") String authHeader, @RequestBody BasicProfileRequestDto request) {
        try {
            String jwt = authHeader.replace("Bearer ", "");
            String id = jwtService.getUserIdFromToken(jwt);
            if (id == null) {
                return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
            }
            UserDtoResponse updatedUser = onboardingService.updateBasicProfile(id, request);
            return ResponseEntity.ok(updatedUser);
        } catch (UserNotFoundException
                 | JwtException
                 | IllegalArgumentException
                 | OptimisticLockingFailureException e) {
            log.error("Error occurred while updating user" + e.getMessage());
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        } catch (Exception e ) {
            log.error("Unexpected error occurred while updating user" + e.getMessage());
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PutMapping("/location")
    public ResponseEntity<UserDtoResponse> updateLocationInfo(@RequestHeader("Authorization") String authHeader, @RequestBody LocationDto request) {
        try {
            String jwt = authHeader.replace("Bearer ", "");
            String id = jwtService.getUserIdFromToken(jwt);
            if (id == null) {
                return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
            }
            UserDtoResponse updatedUser = onboardingService.updateLocation(id, request);
            return ResponseEntity.ok(updatedUser);
        } catch (UserNotFoundException
                 | JwtException
                 | IllegalArgumentException
                 | OptimisticLockingFailureException e) {
            log.error("Error occurred while updating user" + e.getMessage());
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        } catch (Exception e ) {
            log.error("Unexpected error occurred while updating user" + e.getMessage());
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PutMapping("/photos")
    public ResponseEntity<UserDtoResponse> updatePhotos(@RequestHeader("Authorization") String authHeader, @RequestBody PhotosRequestDto request) {
        try {
            String jwt = authHeader.replace("Bearer ", "");
            String id = jwtService.getUserIdFromToken(jwt);
            if (id == null) {
                return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
            }
            UserDtoResponse updatedUser = onboardingService.updatePhotos(id, request);
            return ResponseEntity.ok(updatedUser);
        } catch (UserNotFoundException
                 | JwtException
                 | IllegalArgumentException
                 | OptimisticLockingFailureException e) {
            log.error("Error occurred while updating photos: " + e.getMessage());
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            log.error("Unexpected error occurred while updating photos: " + e.getMessage());
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PutMapping("/music-preferences")
    public ResponseEntity<UserDtoResponse> updateMusicPreferences(@RequestHeader("Authorization") String authHeader, @RequestBody MusicPreferencesRequestDto request) {
        try {
            String jwt = authHeader.replace("Bearer ", "");
            String id = jwtService.getUserIdFromToken(jwt);
            if (id == null) {
                return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
            }
            UserDtoResponse updatedUser = onboardingService.updateMusicPreferences(id, request);
            return ResponseEntity.ok(updatedUser);
        } catch (UserNotFoundException
                 | JwtException
                 | IllegalArgumentException
                 | OptimisticLockingFailureException e) {
            log.error("Error occurred while updating music preferences: " + e.getMessage());
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            log.error("Unexpected error occurred while updating music preferences: " + e.getMessage());
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PutMapping("/lifestyle")
    public ResponseEntity<UserDtoResponse> updateLifestyle(@RequestHeader("Authorization") String authHeader, @RequestBody LifestyleRequestDto request) {
        try {
            String jwt = authHeader.replace("Bearer ", "");
            String id = jwtService.getUserIdFromToken(jwt);
            if (id == null) {
                return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
            }
            UserDtoResponse updatedUser = onboardingService.updateLifestyle(id, request);
            return ResponseEntity.ok(updatedUser);
        } catch (UserNotFoundException
                 | JwtException
                 | IllegalArgumentException
                 | OptimisticLockingFailureException e) {
            log.error("Error occurred while updating lifestyle: " + e.getMessage());
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            log.error("Unexpected error occurred while updating lifestyle: " + e.getMessage());
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PutMapping("/personality")
    public ResponseEntity<UserDtoResponse> updatePersonality(@RequestHeader("Authorization") String authHeader, @RequestBody PersonalityRequestDto request) {
        try {
            String jwt = authHeader.replace("Bearer ", "");
            String id = jwtService.getUserIdFromToken(jwt);
            if (id == null) {
                return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
            }
            UserDtoResponse updatedUser = onboardingService.updatePersonality(id, request);
            return ResponseEntity.ok(updatedUser);
        } catch (UserNotFoundException
                 | JwtException
                 | IllegalArgumentException
                 | OptimisticLockingFailureException e) {
            log.error("Error occurred while updating personality: " + e.getMessage());
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            log.error("Unexpected error occurred while updating personality: " + e.getMessage());
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PutMapping("/dating-preferences")
    public ResponseEntity<UserDtoResponse> updateDatingPreferences(@RequestHeader("Authorization") String authHeader, @RequestBody DatingPreferencesRequestDto request) {
        try {
            String jwt = authHeader.replace("Bearer ", "");
            String id = jwtService.getUserIdFromToken(jwt);
            if (id == null) {
                return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
            }
            UserDtoResponse updatedUser = onboardingService.updateDatingPreferences(id, request);
            return ResponseEntity.ok(updatedUser);
        } catch (UserNotFoundException
                 | JwtException
                 | IllegalArgumentException
                 | OptimisticLockingFailureException e) {
            log.error("Error occurred while updating dating preferences: " + e.getMessage());
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            log.error("Unexpected error occurred while updating dating preferences: " + e.getMessage());
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PutMapping("/privacy-settings")
    public ResponseEntity<UserDtoResponse> updatePrivacySettings(@RequestHeader("Authorization") String authHeader, @RequestBody PrivacySettingsRequestDto request) {
        try {
            String jwt = authHeader.replace("Bearer ", "");
            String id = jwtService.getUserIdFromToken(jwt);
            if (id == null) {
                return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
            }
            UserDtoResponse updatedUser = onboardingService.updatePrivacySettings(id, request);
            return ResponseEntity.ok(updatedUser);
        } catch (UserNotFoundException
                 | JwtException
                 | IllegalArgumentException
                 | OptimisticLockingFailureException e) {
            log.error("Error occurred while updating privacy settings: " + e.getMessage());
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            log.error("Unexpected error occurred while updating privacy settings: " + e.getMessage());
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // GET endpoints
    @GetMapping("/profile")
    public ResponseEntity<CompleteProfileResponseDto> getCompleteProfile(@RequestHeader("Authorization") String authHeader) {
        try {
            String jwt = authHeader.replace("Bearer ", "");
            String id = jwtService.getUserIdFromToken(jwt);
            if (id == null) {
                return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
            }
            CompleteProfileResponseDto profile = onboardingService.getCompleteProfile(id);
            return ResponseEntity.ok(profile);
        } catch (UserNotFoundException e) {
            log.error("User not found: " + e.getMessage());
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        } catch (Exception e) {
            log.error("Unexpected error occurred while getting profile: " + e.getMessage());
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/progress")
    public ResponseEntity<OnboardingProgressDto> getOnboardingProgress(@RequestHeader("Authorization") String authHeader) {
        try {
            String jwt = authHeader.replace("Bearer ", "");
            String id = jwtService.getUserIdFromToken(jwt);
            if (id == null) {
                return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
            }
            OnboardingProgressDto progress = onboardingService.getOnboardingProgress(id);
            return ResponseEntity.ok(progress);
        } catch (UserNotFoundException e) {
            log.error("User not found: " + e.getMessage());
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        } catch (Exception e) {
            log.error("Unexpected error occurred while getting progress: " + e.getMessage());
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/music-preferences")
    public ResponseEntity<MusicPreferencesResponseDto> getMusicPreferences(@RequestHeader("Authorization") String authHeader) {
        try {
            String jwt = authHeader.replace("Bearer ", "");
            String id = jwtService.getUserIdFromToken(jwt);
            if (id == null) {
                return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
            }
            MusicPreferencesResponseDto musicPreferences = onboardingService.getMusicPreferences(id);
            return ResponseEntity.ok(musicPreferences);
        } catch (UserNotFoundException e) {
            log.error("User not found: " + e.getMessage());
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        } catch (Exception e) {
            log.error("Unexpected error occurred while getting music preferences: " + e.getMessage());
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/lifestyle")
    public ResponseEntity<LifestyleResponseDto> getLifestyle(@RequestHeader("Authorization") String authHeader) {
        try {
            String jwt = authHeader.replace("Bearer ", "");
            String id = jwtService.getUserIdFromToken(jwt);
            if (id == null) {
                return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
            }
            LifestyleResponseDto lifestyle = onboardingService.getLifestyle(id);
            return ResponseEntity.ok(lifestyle);
        } catch (UserNotFoundException e) {
            log.error("User not found: " + e.getMessage());
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        } catch (Exception e) {
            log.error("Unexpected error occurred while getting lifestyle: " + e.getMessage());
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/personality")
    public ResponseEntity<PersonalityResponseDto> getPersonality(@RequestHeader("Authorization") String authHeader) {
        try {
            String jwt = authHeader.replace("Bearer ", "");
            String id = jwtService.getUserIdFromToken(jwt);
            if (id == null) {
                return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
            }
            PersonalityResponseDto personality = onboardingService.getPersonality(id);
            return ResponseEntity.ok(personality);
        } catch (UserNotFoundException e) {
            log.error("User not found: " + e.getMessage());
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        } catch (Exception e) {
            log.error("Unexpected error occurred while getting personality: " + e.getMessage());
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/dating-preferences")
    public ResponseEntity<DatingPreferencesResponseDto> getDatingPreferences(@RequestHeader("Authorization") String authHeader) {
        try {
            String jwt = authHeader.replace("Bearer ", "");
            String id = jwtService.getUserIdFromToken(jwt);
            if (id == null) {
                return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
            }
            DatingPreferencesResponseDto datingPreferences = onboardingService.getDatingPreferences(id);
            return ResponseEntity.ok(datingPreferences);
        } catch (UserNotFoundException e) {
            log.error("User not found: " + e.getMessage());
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        } catch (Exception e) {
            log.error("Unexpected error occurred while getting dating preferences: " + e.getMessage());
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/privacy-settings")
    public ResponseEntity<PrivacySettingsResponseDto> getPrivacySettings(@RequestHeader("Authorization") String authHeader) {
        try {
            String jwt = authHeader.replace("Bearer ", "");
            String id = jwtService.getUserIdFromToken(jwt);
            if (id == null) {
                return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
            }
            PrivacySettingsResponseDto privacySettings = onboardingService.getPrivacySettings(id);
            return ResponseEntity.ok(privacySettings);
        } catch (UserNotFoundException e) {
            log.error("User not found: " + e.getMessage());
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        } catch (Exception e) {
            log.error("Unexpected error occurred while getting privacy settings: " + e.getMessage());
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/photos")
    public ResponseEntity<List<PhotoResponseDto>> getPhotos(@RequestHeader("Authorization") String authHeader) {
        try {
            String jwt = authHeader.replace("Bearer ", "");
            String id = jwtService.getUserIdFromToken(jwt);
            if (id == null) {
                return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
            }
            List<PhotoResponseDto> photos = onboardingService.getPhotos(id);
            return ResponseEntity.ok(photos);
        } catch (UserNotFoundException e) {
            log.error("User not found: " + e.getMessage());
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        } catch (Exception e) {
            log.error("Unexpected error occurred while getting photos: " + e.getMessage());
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
