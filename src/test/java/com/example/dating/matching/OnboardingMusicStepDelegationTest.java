package com.example.dating.matching;

import com.example.dating.enums.user.ConcertFrequency;
import com.example.dating.enums.user.MusicImportance;
import com.example.dating.enums.user.RegistrationStage;
import com.example.dating.mappers.OnboardingMapper;
import com.example.dating.mappers.UserMapper;
import com.example.dating.models.onboarding.dto.MusicPreferencesRequestDto;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.common.dto.UserDtoResponse;
import com.example.dating.models.user.domain.User;
import com.example.dating.models.user.preferences.dao.UserMusicPreferences;
import com.example.dating.repositories.*;
import com.example.dating.services.GeocodingService;
import com.example.dating.services.impl.OnboardingServiceImpl;
import com.example.dating.services.matching.GenreExtractionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Email-verification onboarding fix — Option 2 wiring guard.
 *
 * <p>{@code OnboardingGenrePersistenceTest} proves {@link GenreExtractionService#replaceManualPreferences}
 * creates the weighted records, and the filter test proves {@code /onboarding/**} is exempt. This test
 * closes the middle link: that {@code updateMusicPreferences} actually <b>delegates</b> to
 * {@code replaceManualPreferences} with the request's {@code favoriteGenres}. Without it, reverting the
 * one-line wiring in {@link OnboardingServiceImpl} would silently reinstate the data gap while every
 * leaf test still passed.
 *
 * <p>Also asserts the cache-staleness bump ({@code touchUpdatedAt}) fires for a FINISHED user — whose
 * unchanged {@code UserEntity} would otherwise not move {@code updatedAt}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OnboardingMusicStepDelegationTest {

    @Mock private UserJpaRepository userJpaRepository;
    @Mock private GeocodingService geocodingService;
    @Mock private UserMapper userMapper;
    @Mock private UserMusicPreferencesRepository musicPreferencesRepository;
    @Mock private UserLifestyleRepository lifestyleRepository;
    @Mock private UserPersonalityRepository personalityRepository;
    @Mock private UserDatingPreferencesRepository datingPreferencesRepository;
    @Mock private UserPrivacySettingsRepository privacySettingsRepository;
    @Mock private UserPhotoRepository photoRepository;
    @Mock private OnboardingMapper onboardingMapper;
    @Mock private GenreExtractionService genreExtractionService;

    @InjectMocks private OnboardingServiceImpl service;

    @Test
    @DisplayName("updateMusicPreferences delegates favoriteGenres to replaceManualPreferences and bumps updatedAt (FINISHED user)")
    void updateMusicPreferences_delegatesToReplaceManualPreferences() {
        String userId = "user-finished-unverified";

        UserEntity entity = new UserEntity();
        entity.setId(userId);
        // FINISHED + unverified: exactly the state from the bug report.
        entity.setEmailVerified(false);
        entity.setRegistrationStage(RegistrationStage.FINISHED);

        User user = User.builder()
                .id(userId)
                .registrationStage(RegistrationStage.FINISHED)
                .userEntity(entity)
                .build();

        when(userJpaRepository.findById(userId)).thenReturn(Optional.of(entity));
        when(userMapper.toDomain(any(UserEntity.class))).thenReturn(user);
        when(userMapper.toEntity(any(User.class))).thenReturn(entity);
        when(userJpaRepository.save(any(UserEntity.class))).thenReturn(entity);
        when(userMapper.toDtoResponse(any(User.class))).thenReturn(UserDtoResponse.builder().build());
        when(musicPreferencesRepository.findByUserId(userId))
                .thenReturn(Optional.of(UserMusicPreferences.builder().user(entity).build()));

        List<String> favoriteGenres = List.of("rock", "hip-hop");
        MusicPreferencesRequestDto request = MusicPreferencesRequestDto.builder()
                .favoriteGenres(favoriteGenres)
                .concertFrequency(ConcertFrequency.MONTHLY)
                .musicImportance(MusicImportance.VERY_IMPORTANT)
                .openToNewGenres(true)
                .build();

        service.updateMusicPreferences(userId, request);

        // The weighted records must be persisted server-side with the same genres the client sent.
        verify(genreExtractionService).replaceManualPreferences(eq(user), eq(favoriteGenres));
        // Cached match scores must be invalidated even though the FINISHED user's entity is unchanged.
        verify(userJpaRepository).touchUpdatedAt(userId);
    }
}
