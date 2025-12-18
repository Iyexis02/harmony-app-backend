package com.example.dating.services;

import com.example.dating.exceptions.UserNotFoundException;
import com.example.dating.models.onboarding.dto.*;
import com.example.dating.models.user.common.dto.UserDtoResponse;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.oauth2.jwt.JwtException;

import java.util.List;

public interface OnboardingService {
    // Update methods
    UserDtoResponse updateBasicProfile(String id, BasicProfileRequestDto request) throws JwtException, UserNotFoundException, IllegalArgumentException, OptimisticLockingFailureException;
    UserDtoResponse updateLocation(String id, LocationDto request) throws JwtException, UserNotFoundException, IllegalArgumentException, OptimisticLockingFailureException;
    UserDtoResponse updatePhotos(String id, PhotosRequestDto request) throws JwtException, UserNotFoundException, IllegalArgumentException, OptimisticLockingFailureException;
    UserDtoResponse updateMusicPreferences(String id, MusicPreferencesRequestDto request) throws JwtException, UserNotFoundException, IllegalArgumentException, OptimisticLockingFailureException;
    UserDtoResponse updateLifestyle(String id, LifestyleRequestDto request) throws JwtException, UserNotFoundException, IllegalArgumentException, OptimisticLockingFailureException;
    UserDtoResponse updatePersonality(String id, PersonalityRequestDto request) throws JwtException, UserNotFoundException, IllegalArgumentException, OptimisticLockingFailureException;
    UserDtoResponse updateDatingPreferences(String id, DatingPreferencesRequestDto request) throws JwtException, UserNotFoundException, IllegalArgumentException, OptimisticLockingFailureException;
    UserDtoResponse updatePrivacySettings(String id, PrivacySettingsRequestDto request) throws JwtException, UserNotFoundException, IllegalArgumentException, OptimisticLockingFailureException;

    // Get methods
    CompleteProfileResponseDto getCompleteProfile(String id) throws UserNotFoundException;
    OnboardingProgressDto getOnboardingProgress(String id) throws UserNotFoundException;
    MusicPreferencesResponseDto getMusicPreferences(String id) throws UserNotFoundException;
    LifestyleResponseDto getLifestyle(String id) throws UserNotFoundException;
    PersonalityResponseDto getPersonality(String id) throws UserNotFoundException;
    DatingPreferencesResponseDto getDatingPreferences(String id) throws UserNotFoundException;
    PrivacySettingsResponseDto getPrivacySettings(String id) throws UserNotFoundException;
    List<PhotoResponseDto> getPhotos(String id) throws UserNotFoundException;
}
