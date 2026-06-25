package com.example.dating.services.impl;

import com.example.dating.enums.user.Gender;
import com.example.dating.enums.user.RegistrationStage;
import com.example.dating.exceptions.UserNotFoundException;
import com.example.dating.mappers.UserMapper;
import com.example.dating.models.geocoding.GeocodingResult;
import com.example.dating.models.onboarding.dto.*;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.common.dto.UserDtoResponse;
import com.example.dating.models.user.dating.dao.UserDatingPreferences;
import com.example.dating.models.user.domain.User;
import com.example.dating.models.user.lifestyle.dao.UserLifestyle;
import com.example.dating.models.user.personality.dao.UserPersonality;
import com.example.dating.models.user.photos.dao.UserPhoto;
import com.example.dating.models.user.preferences.dao.UserMusicPreferences;
import com.example.dating.models.user.privacy.dao.UserPrivacySettings;
import com.example.dating.repositories.*;
import com.example.dating.services.GeocodingService;
import com.example.dating.services.JwtService;
import com.example.dating.services.OnboardingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OnboardingServiceImpl implements OnboardingService {

    private final UserJpaRepository userJpaRepository;
    private final GeocodingService geocodingService;
    private final UserMapper userMapper;
    private final UserMusicPreferencesRepository musicPreferencesRepository;
    private final UserLifestyleRepository lifestyleRepository;
    private final UserPersonalityRepository personalityRepository;
    private final UserDatingPreferencesRepository datingPreferencesRepository;
    private final UserPrivacySettingsRepository privacySettingsRepository;
    private final UserPhotoRepository photoRepository;
    private final com.example.dating.mappers.OnboardingMapper onboardingMapper;
    private final com.example.dating.services.matching.GenreExtractionService genreExtractionService;

    @Override
    @Transactional
    public UserDtoResponse updateBasicProfile(String id, BasicProfileRequestDto request) throws JwtException, UserNotFoundException, IllegalArgumentException, OptimisticLockingFailureException {
        Optional<User> optUser = userJpaRepository.findById(id).map(userMapper::toDomain);
        if (optUser.isEmpty()) {
            throw new UserNotFoundException("User with id " + id + " not found");
        }
        User user = optUser.get();

        user.setDateOfBirth(request.getDateOfBirth());
        user.setGender(request.getGender());
        user.setName(request.getName());
        user.setSexualOrientation(request.getSexualOrientation());

        // Update registration stage ONLY if user is still in onboarding (not FINISHED)
        if (user.getRegistrationStage() != RegistrationStage.FINISHED) {
            user.setRegistrationStage(RegistrationStage.BASIC_PROFILE);
            user.getUserEntity().setRegistrationStage(RegistrationStage.BASIC_PROFILE);
        }

        return userMapper.toDtoResponse(userMapper.toDomain(userJpaRepository.save(userMapper.toEntity(user))));
    }

    @Override
    @Transactional
    public UserDtoResponse updateLocation(String id, LocationDto request) throws JwtException, UserNotFoundException, IllegalArgumentException, OptimisticLockingFailureException {
        Optional<User> optUser = userJpaRepository.findById(id).map(userMapper::toDomain);
        if (optUser.isEmpty()) {
            throw new UserNotFoundException("User with id " + id + " not found");
        }
        User user = optUser.get();
        user.setLocationCity(request.locationCity());
        user.setLocationCountry(request.locationCountry());

        if (request.latitude().isPresent() && request.longitude().isPresent()) {
            user.setLocationLat(request.latitude().get());
            user.setLocationLon(request.longitude().get());
        } else {
            GeocodingResult geocodingResult = geocodingService.geocode(request.locationCity(), request.locationCountry());
            user.setLocationLat(geocodingResult.getLatitude());
            user.setLocationLon(geocodingResult.getLongitude());
        }

        // Update registration stage ONLY if user is still in onboarding (not FINISHED)
        if (user.getRegistrationStage() != RegistrationStage.FINISHED) {
            user.setRegistrationStage(RegistrationStage.LOCATION_INFO);
            user.getUserEntity().setRegistrationStage(RegistrationStage.LOCATION_INFO);
        }

        return userMapper.toDtoResponse(userMapper.toDomain(userJpaRepository.save(userMapper.toEntity(user))));
    }

    @Override
    @Transactional
    public UserDtoResponse updatePhotos(String id, PhotosRequestDto request) throws JwtException, UserNotFoundException, IllegalArgumentException, OptimisticLockingFailureException {
        Optional<User> optUser = userJpaRepository.findById(id).map(userMapper::toDomain);
        if (optUser.isEmpty()) {
            throw new UserNotFoundException("User with id " + id + " not found");
        }
        User user = optUser.get();
        UserEntity userEntity = user.getUserEntity();

        // Replace photos THROUGH the managed collection so Hibernate's cascade +
        // orphanRemoval delete the old rows and insert the new ones as one consistent
        // unit of work. The previous code deleted/inserted photos via separate
        // repository calls (a derived deleteByUserId loads + removes the entities) and
        // then re-saved the parent, whose photos collection still referenced the
        // now-deleted instances — the cascade merge then threw ObjectDeletedException
        // ("deleted instance passed to merge: [UserPhoto#<null>]"), 500-ing the request.
        userEntity.getPhotos().clear();
        request.getPhotos().forEach(photoDto -> userEntity.getPhotos().add(
                UserPhoto.builder()
                        .user(userEntity)
                        .imageUrl(photoDto.getImageUrl())
                        .displayOrder(photoDto.getDisplayOrder())
                        .isPrimary(photoDto.getIsPrimary())
                        .caption(photoDto.getCaption())
                        .build()));

        // Update registration stage ONLY if user is still in onboarding (not FINISHED)
        if (user.getRegistrationStage() != RegistrationStage.FINISHED) {
            user.setRegistrationStage(RegistrationStage.PHOTOS);
            userEntity.setRegistrationStage(RegistrationStage.PHOTOS);
        }

        return userMapper.toDtoResponse(userMapper.toDomain(userJpaRepository.save(userMapper.toEntity(user))));
    }

    @Override
    @Transactional
    public UserDtoResponse updateMusicPreferences(String id, MusicPreferencesRequestDto request) throws JwtException, UserNotFoundException, IllegalArgumentException, OptimisticLockingFailureException {
        Optional<User> optUser = userJpaRepository.findById(id).map(userMapper::toDomain);
        if (optUser.isEmpty()) {
            throw new UserNotFoundException("User with id " + id + " not found");
        }
        User user = optUser.get();

        Optional<UserMusicPreferences> existingPrefs = musicPreferencesRepository.findByUserId(id);
        UserMusicPreferences musicPreferences;

        musicPreferences = existingPrefs.orElseGet(() -> UserMusicPreferences.builder()
                .user(user.getUserEntity())
                .build());

        // Update fields
        musicPreferences.setFavoriteGenres(String.join(",", request.getFavoriteGenres()));
        musicPreferences.setConcertFrequency(request.getConcertFrequency());
        musicPreferences.setMusicImportance(request.getMusicImportance());
        if (request.getFavoriteDecades() != null) {
            musicPreferences.setFavoriteDecades(String.join(",", request.getFavoriteDecades()));
        }
        musicPreferences.setOpenToNewGenres(request.getOpenToNewGenres());
        if (request.getListeningTimes() != null) {
            musicPreferences.setListeningTimes(String.join(",", request.getListeningTimes()));
        }
        musicPreferences.setHoursPerDay(request.getHoursPerDay());

        musicPreferencesRepository.save(musicPreferences);

        // Option 2: also persist the weighted GenrePreference records the matching engine scores
        // against. Previously the client fanned out separate POST /preferences/genres calls, which
        // the email-verification filter blocks for unverified users — so the genre list looked
        // saved (CSV above) while the matcher had nothing. Folding it into this already-exempt
        // write closes that silent data gap. Joins this @Transactional method, so it commits
        // atomically with the CSV + registration-stage update.
        genreExtractionService.replaceManualPreferences(user, request.getFavoriteGenres());

        // Update registration stage ONLY if user is still in onboarding (not FINISHED)
        if (user.getRegistrationStage() != RegistrationStage.FINISHED) {
            user.setRegistrationStage(RegistrationStage.MUSIC_PREFERENCES);
            user.getUserEntity().setRegistrationStage(RegistrationStage.MUSIC_PREFERENCES);
        }

        UserEntity saved = userJpaRepository.save(userMapper.toEntity(user));

        // Bump updatedAt so cached match scores are invalidated (stale if either user updated since
        // computedAt). A FINISHED user re-submitting the music step leaves the UserEntity itself
        // unchanged, so @PreUpdate would not fire and the bumped genres would otherwise score
        // against a still-fresh cache. Runs after save() so the merge flush precedes this bulk
        // UPDATE. Mirrors GenreExtractionService.persistGenreSync.
        userJpaRepository.touchUpdatedAt(id);

        return userMapper.toDtoResponse(userMapper.toDomain(saved));
    }

    @Override
    @Transactional
    public UserDtoResponse updateLifestyle(String id, LifestyleRequestDto request) throws JwtException, UserNotFoundException, IllegalArgumentException, OptimisticLockingFailureException {
        Optional<User> optUser = userJpaRepository.findById(id).map(userMapper::toDomain);
        if (optUser.isEmpty()) {
            throw new UserNotFoundException("User with id " + id + " not found");
        }
        User user = optUser.get();

        Optional<UserLifestyle> existingLifestyle = lifestyleRepository.findByUserId(id);
        UserLifestyle lifestyle;

        lifestyle = existingLifestyle.orElseGet(() -> UserLifestyle.builder()
                .user(user.getUserEntity())
                .build());

        // Update fields
        lifestyle.setEducation(request.getEducation());
        lifestyle.setOccupation(request.getOccupation());
        lifestyle.setCompany(request.getCompany());
        lifestyle.setRelationshipStatus(request.getRelationshipStatus());
        lifestyle.setWantsKids(request.getWantsKids());
        lifestyle.setSmokingHabits(request.getSmokingHabits());
        lifestyle.setDrinkingHabits(request.getDrinkingHabits());
        lifestyle.setExerciseFrequency(request.getExerciseFrequency());
        lifestyle.setReligion(request.getReligion());
        lifestyle.setPoliticalViews(request.getPoliticalViews());

        lifestyleRepository.save(lifestyle);

        // Update registration stage ONLY if user is still in onboarding (not FINISHED)
        if (user.getRegistrationStage() != RegistrationStage.FINISHED) {
            user.setRegistrationStage(RegistrationStage.LIFESTYLE);
            user.getUserEntity().setRegistrationStage(RegistrationStage.LIFESTYLE);
        }

        return userMapper.toDtoResponse(userMapper.toDomain(userJpaRepository.save(userMapper.toEntity(user))));
    }

    @Override
    @Transactional
    public UserDtoResponse updatePersonality(String id, PersonalityRequestDto request) throws JwtException, UserNotFoundException, IllegalArgumentException, OptimisticLockingFailureException {
        Optional<User> optUser = userJpaRepository.findById(id).map(userMapper::toDomain);
        if (optUser.isEmpty()) {
            throw new UserNotFoundException("User with id " + id + " not found");
        }
        User user = optUser.get();

        Optional<UserPersonality> existingPersonality = personalityRepository.findByUserId(id);
        UserPersonality personality;

        personality = existingPersonality.orElseGet(() -> UserPersonality.builder()
                .user(user.getUserEntity())
                .build());

        // Update fields
        personality.setBio(request.getBio());
        if (request.getInterests() != null) {
            personality.setInterests(String.join(",", request.getInterests()));
        }
        personality.setMbti(request.getMbti());
        personality.setLookingForText(request.getLookingForText());
        personality.setFavoriteQuote(request.getFavoriteQuote());
        personality.setConversationStarters(request.getConversationStarters());

        personalityRepository.save(personality);

        // Update registration stage ONLY if user is still in onboarding (not FINISHED)
        if (user.getRegistrationStage() != RegistrationStage.FINISHED) {
            user.setRegistrationStage(RegistrationStage.PERSONALITY);
            user.getUserEntity().setRegistrationStage(RegistrationStage.PERSONALITY);
        }

        return userMapper.toDtoResponse(userMapper.toDomain(userJpaRepository.save(userMapper.toEntity(user))));
    }

    @Override
    @Transactional
    public UserDtoResponse updateDatingPreferences(String id, DatingPreferencesRequestDto request) throws JwtException, UserNotFoundException, IllegalArgumentException, OptimisticLockingFailureException {
        Optional<User> optUser = userJpaRepository.findById(id).map(userMapper::toDomain);
        if (optUser.isEmpty()) {
            throw new UserNotFoundException("User with id " + id + " not found");
        }
        User user = optUser.get();

        Optional<UserDatingPreferences> existingPrefs = datingPreferencesRepository.findByUserId(id);
        UserDatingPreferences datingPreferences;

        if (existingPrefs.isPresent()) {
            datingPreferences = existingPrefs.get();
        } else {
            datingPreferences = UserDatingPreferences.builder()
                    .user(user.getUserEntity())
                    .build();
        }

        // Update fields
        datingPreferences.setMinAge(request.getMinAge());
        datingPreferences.setMaxAge(request.getMaxAge());
        datingPreferences.setMaxDistanceKm(request.getMaxDistanceKm());

        if (request.getInterestedInGenders() != null) {
            String genders = request.getInterestedInGenders().stream()
                    .map(Gender::name)
                    .collect(Collectors.joining(","));
            datingPreferences.setInterestedInGenders(genders);
        }

        datingPreferences.setRelationshipGoal(request.getRelationshipGoal());
        if (request.getDealBreakers() != null) {
            datingPreferences.setDealBreakers(String.join(",", request.getDealBreakers()));
        }
        datingPreferences.setShowMe(request.getShowMe());
        datingPreferences.setMusicMatchImportance(request.getMusicMatchImportance());

        datingPreferencesRepository.save(datingPreferences);

        // Update registration stage ONLY if user is still in onboarding (not FINISHED)
        if (user.getRegistrationStage() != RegistrationStage.FINISHED) {
            user.setRegistrationStage(RegistrationStage.DATING_PREFERENCES);
            user.getUserEntity().setRegistrationStage(RegistrationStage.DATING_PREFERENCES);
        }

        return userMapper.toDtoResponse(userMapper.toDomain(userJpaRepository.save(userMapper.toEntity(user))));
    }

    @Override
    @Transactional
    public UserDtoResponse updatePrivacySettings(String id, PrivacySettingsRequestDto request) throws JwtException, UserNotFoundException, IllegalArgumentException, OptimisticLockingFailureException {
        Optional<User> optUser = userJpaRepository.findById(id).map(userMapper::toDomain);
        if (optUser.isEmpty()) {
            throw new UserNotFoundException("User with id " + id + " not found");
        }
        User user = optUser.get();

        Optional<UserPrivacySettings> existingSettings = privacySettingsRepository.findByUserId(id);
        UserPrivacySettings privacySettings;

        if (existingSettings.isPresent()) {
            privacySettings = existingSettings.get();
        } else {
            privacySettings = UserPrivacySettings.builder()
                    .user(user.getUserEntity())
                    .build();
        }

        // Update fields
        privacySettings.setIsProfilePublic(request.getIsProfilePublic());
        privacySettings.setShowAge(request.getShowAge());
        privacySettings.setShowDistance(request.getShowDistance());
        privacySettings.setShowLastActive(request.getShowLastActive());
        privacySettings.setDiscoverable(request.getDiscoverable());
        privacySettings.setShowLikedByYou(request.getShowLikedByYou());
        privacySettings.setShowSpotifyProfile(request.getShowSpotifyProfile());
        privacySettings.setShowMusicStats(request.getShowMusicStats());
        privacySettings.setIncognitoMode(request.getIncognitoMode());
        privacySettings.setReadReceipts(request.getReadReceipts());

        privacySettingsRepository.save(privacySettings);

        // Update registration stage to FINISHED (final step) ONLY if user is still in onboarding
        // Once FINISHED, it should never change back
        if (user.getRegistrationStage() != RegistrationStage.FINISHED) {
            user.setRegistrationStage(RegistrationStage.FINISHED);
            user.getUserEntity().setRegistrationStage(RegistrationStage.FINISHED);
        }

        return userMapper.toDtoResponse(userMapper.toDomain(userJpaRepository.save(userMapper.toEntity(user))));
    }

    @Override
    @Transactional(readOnly = true)
    public CompleteProfileResponseDto getCompleteProfile(String id) throws UserNotFoundException {
        UserEntity entity = userJpaRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User with id " + id + " not found"));
        return onboardingMapper.toCompleteProfileDto(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public OnboardingProgressDto getOnboardingProgress(String id) throws UserNotFoundException {
        UserEntity entity = userJpaRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User with id " + id + " not found"));
        return onboardingMapper.toProgressDto(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public MusicPreferencesResponseDto getMusicPreferences(String id) throws UserNotFoundException {
        UserEntity entity = userJpaRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User with id " + id + " not found"));
        return onboardingMapper.toMusicPreferencesDto(entity.getMusicPreferences());
    }

    @Override
    @Transactional(readOnly = true)
    public LifestyleResponseDto getLifestyle(String id) throws UserNotFoundException {
        UserEntity entity = userJpaRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User with id " + id + " not found"));
        return onboardingMapper.toLifestyleDto(entity.getLifestyle());
    }

    @Override
    @Transactional(readOnly = true)
    public PersonalityResponseDto getPersonality(String id) throws UserNotFoundException {
        UserEntity entity = userJpaRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User with id " + id + " not found"));
        return onboardingMapper.toPersonalityDto(entity.getPersonality());
    }

    @Override
    @Transactional(readOnly = true)
    public DatingPreferencesResponseDto getDatingPreferences(String id) throws UserNotFoundException {
        UserEntity entity = userJpaRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User with id " + id + " not found"));
        return onboardingMapper.toDatingPreferencesDto(entity.getDatingPreferences());
    }

    @Override
    @Transactional(readOnly = true)
    public PrivacySettingsResponseDto getPrivacySettings(String id) throws UserNotFoundException {
        UserEntity entity = userJpaRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User with id " + id + " not found"));
        return onboardingMapper.toPrivacySettingsDto(entity.getPrivacySettings());
    }

    @Override
    @Transactional(readOnly = true)
    public List<PhotoResponseDto> getPhotos(String id) throws UserNotFoundException {
        if (userJpaRepository.findById(id).isEmpty()) {
            throw new UserNotFoundException("User with id " + id + " not found");
        }
        List<UserPhoto> photos = photoRepository.findByUserIdOrderByDisplayOrderAsc(id);
        return photos.stream()
                .map(onboardingMapper::toPhotoDto)
                .collect(Collectors.toList());
    }
}
