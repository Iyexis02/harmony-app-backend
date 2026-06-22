package com.example.dating.matching;

import com.example.dating.enums.matching.GenrePreferenceSource;
import com.example.dating.models.matching.dao.CanonicalGenre;
import com.example.dating.models.matching.dao.UserGenrePreference;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.CanonicalGenreRepository;
import com.example.dating.repositories.UserGenrePreferenceRepository;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.matching.GenreExtractionService;
import com.example.dating.services.matching.GenreWeightCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Email-verification onboarding fix — Option 2 (server-side genre persistence).
 *
 * <p>Background: an email/password user who has NOT verified their email can still complete
 * all 8 onboarding steps and reach {@code registrationStage = FINISHED}, because
 * {@code /api/v1/onboarding/**} is exempt from {@code EmailVerificationFilter}. Previously the
 * weighted {@link UserGenrePreference} records the matcher scores against were created by
 * separate {@code POST /api/v1/preferences/genres} calls — which the filter <b>blocks</b> with
 * 403 for unverified users. The genre list therefore <i>looked</i> saved (CSV on the profile)
 * while the matching engine had nothing to score.
 *
 * <p>Option 2 folds the weighted-record creation into the already-exempt
 * {@code PUT /onboarding/music-preferences} write via
 * {@link GenreExtractionService#replaceManualPreferences(User, List)}. These tests prove the
 * method actually <b>creates</b> {@code MANUAL_SELECTION} preference rows — not merely that it
 * does not throw — which is the precise failure mode a lenient "skip unknown genre" path could
 * silently reproduce. The onboarding service runs this for any user regardless of verification
 * status, so a FINISHED-but-unverified user's genres are now persisted for matching.
 *
 * <p>Tests:
 * <ol>
 *   <li>Canonical token ("rock") → one MANUAL_SELECTION row, weight 1.0, confidence 1.0.</li>
 *   <li>Display label ("Rock") and alias ("rap") resolve to the right canonical genres.</li>
 *   <li>Unknown token is skipped without failing the step; known tokens still persist.</li>
 *   <li>Existing MANUAL_SELECTION rows are deleted first (idempotent re-submit).</li>
 *   <li>Empty/blank list clears manual prefs and persists nothing.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class OnboardingGenrePersistenceTest {

    @Mock
    private CanonicalGenreRepository canonicalGenreRepository;
    @Mock
    private UserGenrePreferenceRepository userGenrePreferenceRepository;
    @Mock
    private GenreWeightCalculator weightCalculator;
    @Mock
    private UserJpaRepository userJpaRepository;

    private GenreExtractionService service;

    private CanonicalGenre rock;
    private CanonicalGenre hipHop;

    @BeforeEach
    void setUp() {
        service = new GenreExtractionService(
                canonicalGenreRepository, userGenrePreferenceRepository, weightCalculator, userJpaRepository);

        // Mirror GenreSeedDataLoader: canonical name lowercase-kebab, display label, Spotify aliases.
        rock = CanonicalGenre.builder()
                .id("genre-rock")
                .name("rock")
                .displayName("Rock")
                .spotifyAliases("rock,rock music")
                .build();
        hipHop = CanonicalGenre.builder()
                .id("genre-hip-hop")
                .name("hip-hop")
                .displayName("Hip Hop")
                .spotifyAliases("hip hop,hip-hop,rap,hiphop")
                .build();
    }

    private User unverifiedFinishedUser() {
        // The domain object only needs an id for the genre-persistence path; verification status
        // lives on UserEntity and is irrelevant here because /onboarding/** is filter-exempt.
        return User.builder().id("user-unverified-finished").build();
    }

    @Test
    @DisplayName("Canonical token 'rock' persists one MANUAL_SELECTION row with weight/confidence 1.0")
    void canonicalToken_persistsManualPreference() {
        when(canonicalGenreRepository.findAll()).thenReturn(List.of(rock, hipHop));
        when(userGenrePreferenceRepository.findByUserAndGenre(any(), any())).thenReturn(Optional.empty());
        when(userGenrePreferenceRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        int count = service.replaceManualPreferences(unverifiedFinishedUser(), List.of("rock"));

        assertThat(count).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserGenrePreference>> captor = ArgumentCaptor.forClass(List.class);
        verify(userGenrePreferenceRepository).saveAll(captor.capture());

        List<UserGenrePreference> saved = captor.getValue();
        assertThat(saved).hasSize(1);
        UserGenrePreference pref = saved.get(0);
        assertThat(pref.getGenre().getName()).isEqualTo("rock");
        assertThat(pref.getSource()).isEqualTo(GenrePreferenceSource.MANUAL_SELECTION);
        assertThat(pref.getWeight()).isEqualTo(1.0);
        assertThat(pref.getConfidence()).isEqualTo(1.0);
        assertThat(pref.getRank()).isEqualTo(1);
    }

    @Test
    @DisplayName("Display label 'Rock' and alias 'rap' resolve to canonical rock / hip-hop")
    void displayLabelAndAlias_resolveToCanonicalGenres() {
        when(canonicalGenreRepository.findAll()).thenReturn(List.of(rock, hipHop));
        when(userGenrePreferenceRepository.findByUserAndGenre(any(), any())).thenReturn(Optional.empty());
        when(userGenrePreferenceRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        int count = service.replaceManualPreferences(unverifiedFinishedUser(), List.of("Rock", "rap"));

        assertThat(count).isEqualTo(2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserGenrePreference>> captor = ArgumentCaptor.forClass(List.class);
        verify(userGenrePreferenceRepository).saveAll(captor.capture());

        assertThat(captor.getValue())
                .extracting(p -> p.getGenre().getName())
                .containsExactly("rock", "hip-hop");
    }

    @Test
    @DisplayName("Unknown token is skipped without failing; known tokens still persist")
    void unknownToken_skippedKnownPersisted() {
        when(canonicalGenreRepository.findAll()).thenReturn(List.of(rock, hipHop));
        when(userGenrePreferenceRepository.findByUserAndGenre(any(), any())).thenReturn(Optional.empty());
        when(userGenrePreferenceRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        int count = service.replaceManualPreferences(
                unverifiedFinishedUser(), List.of("rock", "totally-not-a-real-genre-xyz"));

        assertThat(count).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserGenrePreference>> captor = ArgumentCaptor.forClass(List.class);
        verify(userGenrePreferenceRepository).saveAll(captor.capture());
        assertThat(captor.getValue())
                .extracting(p -> p.getGenre().getName())
                .containsExactly("rock");
    }

    @Test
    @DisplayName("Existing MANUAL_SELECTION rows are deleted first (idempotent re-submit)")
    void replace_deletesExistingManualPreferencesFirst() {
        when(canonicalGenreRepository.findAll()).thenReturn(List.of(rock, hipHop));
        when(userGenrePreferenceRepository.findByUserAndGenre(any(), any())).thenReturn(Optional.empty());
        when(userGenrePreferenceRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        service.replaceManualPreferences(unverifiedFinishedUser(), List.of("rock"));

        verify(userGenrePreferenceRepository)
                .deleteByUserIdAndSource(eq("user-unverified-finished"), eq(GenrePreferenceSource.MANUAL_SELECTION));
    }

    @Test
    @DisplayName("Empty list clears manual prefs and persists nothing")
    void emptyList_clearsAndPersistsNothing() {
        int count = service.replaceManualPreferences(unverifiedFinishedUser(), List.of());

        assertThat(count).isZero();
        verify(userGenrePreferenceRepository)
                .deleteByUserIdAndSource(eq("user-unverified-finished"), eq(GenrePreferenceSource.MANUAL_SELECTION));
    }
}
