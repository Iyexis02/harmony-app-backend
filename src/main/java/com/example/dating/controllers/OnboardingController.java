package com.example.dating.controllers;

import com.example.dating.models.onboarding.dto.*;
import com.example.dating.models.user.common.dto.UserDtoResponse;
import com.example.dating.services.OnboardingService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.example.dating.constants.AppConstants.BASE_API_ROUTE;

@Tag(name = "Onboarding", description = "Multi-step profile setup: basic info, location, photos, music, lifestyle, personality, dating preferences, and privacy")
@Slf4j
@RequestMapping(BASE_API_ROUTE + "/onboarding")
@RestController
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingService onboardingService;

    /**
     * The Sunset date applied to deprecated sub-section GET endpoints (RFC 8594).
     * 90 days from the deprecation audit date (2026-03-13).
     * Frontend should migrate to GET /onboarding/profile for full profile data.
     */
    private static final String SUNSET_DATE = "Thu, 11 Jun 2026 00:00:00 GMT";

    // ─── PUT endpoints ────────────────────────────────────────────────────────

    @PutMapping("/basic-info")
    public ResponseEntity<UserDtoResponse> updateBasicInfo(
            Authentication authentication,
            @Valid @RequestBody BasicProfileRequestDto request) {
        return ResponseEntity.ok(onboardingService.updateBasicProfile(userId(authentication), request));
    }

    @PutMapping("/location")
    public ResponseEntity<UserDtoResponse> updateLocationInfo(
            Authentication authentication,
            @Valid @RequestBody LocationDto request) {
        return ResponseEntity.ok(onboardingService.updateLocation(userId(authentication), request));
    }

    @PutMapping("/photos")
    public ResponseEntity<UserDtoResponse> updatePhotos(
            Authentication authentication,
            @Valid @RequestBody PhotosRequestDto request) {
        return ResponseEntity.ok(onboardingService.updatePhotos(userId(authentication), request));
    }

    @PutMapping("/music-preferences")
    public ResponseEntity<UserDtoResponse> updateMusicPreferences(
            Authentication authentication,
            @Valid @RequestBody MusicPreferencesRequestDto request) {
        return ResponseEntity.ok(onboardingService.updateMusicPreferences(userId(authentication), request));
    }

    @PutMapping("/lifestyle")
    public ResponseEntity<UserDtoResponse> updateLifestyle(
            Authentication authentication,
            @Valid @RequestBody LifestyleRequestDto request) {
        return ResponseEntity.ok(onboardingService.updateLifestyle(userId(authentication), request));
    }

    @PutMapping("/personality")
    public ResponseEntity<UserDtoResponse> updatePersonality(
            Authentication authentication,
            @Valid @RequestBody PersonalityRequestDto request) {
        return ResponseEntity.ok(onboardingService.updatePersonality(userId(authentication), request));
    }

    @PutMapping("/dating-preferences")
    public ResponseEntity<UserDtoResponse> updateDatingPreferences(
            Authentication authentication,
            @Valid @RequestBody DatingPreferencesRequestDto request) {
        return ResponseEntity.ok(onboardingService.updateDatingPreferences(userId(authentication), request));
    }

    @PutMapping("/privacy-settings")
    public ResponseEntity<UserDtoResponse> updatePrivacySettings(
            Authentication authentication,
            @Valid @RequestBody PrivacySettingsRequestDto request) {
        return ResponseEntity.ok(onboardingService.updatePrivacySettings(userId(authentication), request));
    }

    // ─── GET endpoints ────────────────────────────────────────────────────────

    /** Full profile — the canonical read endpoint. Returns all sub-sections in one response. */
    @GetMapping("/profile")
    public ResponseEntity<CompleteProfileResponseDto> getCompleteProfile(Authentication authentication) {
        return ResponseEntity.ok(onboardingService.getCompleteProfile(userId(authentication)));
    }

    /**
     * @deprecated Use GET /onboarding/profile instead. Sunset: 2026-06-11.
     */
    @Deprecated
    @GetMapping("/progress")
    public ResponseEntity<OnboardingProgressDto> getOnboardingProgress(Authentication authentication) {
        log.warn("Deprecated endpoint called: GET /onboarding/progress — migrate to GET /onboarding/profile");
        return ResponseEntity.ok()
                .header("Sunset", SUNSET_DATE)
                .header("Deprecation", "true")
                .body(onboardingService.getOnboardingProgress(userId(authentication)));
    }

    /**
     * @deprecated Use GET /onboarding/profile instead. Sunset: 2026-06-11.
     */
    @Deprecated
    @GetMapping("/music-preferences")
    public ResponseEntity<MusicPreferencesResponseDto> getMusicPreferences(Authentication authentication) {
        log.warn("Deprecated endpoint called: GET /onboarding/music-preferences — migrate to GET /onboarding/profile");
        return ResponseEntity.ok()
                .header("Sunset", SUNSET_DATE)
                .header("Deprecation", "true")
                .body(onboardingService.getMusicPreferences(userId(authentication)));
    }

    /**
     * @deprecated Use GET /onboarding/profile instead. Sunset: 2026-06-11.
     */
    @Deprecated
    @GetMapping("/lifestyle")
    public ResponseEntity<LifestyleResponseDto> getLifestyle(Authentication authentication) {
        log.warn("Deprecated endpoint called: GET /onboarding/lifestyle — migrate to GET /onboarding/profile");
        return ResponseEntity.ok()
                .header("Sunset", SUNSET_DATE)
                .header("Deprecation", "true")
                .body(onboardingService.getLifestyle(userId(authentication)));
    }

    /**
     * @deprecated Use GET /onboarding/profile instead. Sunset: 2026-06-11.
     */
    @Deprecated
    @GetMapping("/personality")
    public ResponseEntity<PersonalityResponseDto> getPersonality(Authentication authentication) {
        log.warn("Deprecated endpoint called: GET /onboarding/personality — migrate to GET /onboarding/profile");
        return ResponseEntity.ok()
                .header("Sunset", SUNSET_DATE)
                .header("Deprecation", "true")
                .body(onboardingService.getPersonality(userId(authentication)));
    }

    /**
     * @deprecated Use GET /onboarding/profile instead. Sunset: 2026-06-11.
     */
    @Deprecated
    @GetMapping("/dating-preferences")
    public ResponseEntity<DatingPreferencesResponseDto> getDatingPreferences(Authentication authentication) {
        log.warn("Deprecated endpoint called: GET /onboarding/dating-preferences — migrate to GET /onboarding/profile");
        return ResponseEntity.ok()
                .header("Sunset", SUNSET_DATE)
                .header("Deprecation", "true")
                .body(onboardingService.getDatingPreferences(userId(authentication)));
    }

    /**
     * @deprecated Use GET /onboarding/profile instead. Sunset: 2026-06-11.
     */
    @Deprecated
    @GetMapping("/privacy-settings")
    public ResponseEntity<PrivacySettingsResponseDto> getPrivacySettings(Authentication authentication) {
        log.warn("Deprecated endpoint called: GET /onboarding/privacy-settings — migrate to GET /onboarding/profile");
        return ResponseEntity.ok()
                .header("Sunset", SUNSET_DATE)
                .header("Deprecation", "true")
                .body(onboardingService.getPrivacySettings(userId(authentication)));
    }

    /**
     * @deprecated Use GET /onboarding/profile instead. Sunset: 2026-06-11.
     */
    @Deprecated
    @GetMapping("/photos")
    public ResponseEntity<List<PhotoResponseDto>> getPhotos(Authentication authentication) {
        log.warn("Deprecated endpoint called: GET /onboarding/photos — migrate to GET /onboarding/profile");
        return ResponseEntity.ok()
                .header("Sunset", SUNSET_DATE)
                .header("Deprecation", "true")
                .body(onboardingService.getPhotos(userId(authentication)));
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private String userId(Authentication authentication) {
        return ((Jwt) authentication.getPrincipal()).getClaimAsString("userId");
    }
}
