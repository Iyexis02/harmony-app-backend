package com.example.dating.matching;

import com.example.dating.enums.user.*;
import com.example.dating.models.matching.dao.CanonicalGenre;
import com.example.dating.models.matching.dao.UserBehavioralProfile;
import com.example.dating.models.matching.dao.UserGenrePreference;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.dating.dao.UserDatingPreferences;
import com.example.dating.models.user.lifestyle.dao.UserLifestyle;
import com.example.dating.models.user.personality.dao.UserPersonality;
import com.example.dating.repositories.UserGenrePreferenceRepository;
import com.example.dating.services.matching.BehavioralScoreCalculator;
import com.example.dating.services.matching.GenrePrefetchContext;
import com.example.dating.services.matching.InterestsScoreCalculator;
import com.example.dating.services.matching.LifestyleScoreCalculator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Pure unit tests for the three score calculators:
 *   - LifestyleScoreCalculator  (goal / kids / smoking / drinking)
 *   - InterestsScoreCalculator  (Jaccard similarity)
 *   - BehavioralScoreCalculator (cosine similarity on genre centroids)
 *
 * No Spring context required — all dependencies are either
 * instantiated directly or mocked with Mockito.
 *
 * Expected values for seed-user pairs are derived from the formulas
 * documented in the plan and verified manually.
 */
@ExtendWith(MockitoExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MatchingAlgorithmUnitTest {

    // ── calculators under test ──────────────────────────────────────
    private final LifestyleScoreCalculator  lifestyleCalc  = new LifestyleScoreCalculator();
    private final InterestsScoreCalculator  interestsCalc  = new InterestsScoreCalculator();

    @Mock private UserGenrePreferenceRepository genreRepository;
    private BehavioralScoreCalculator behavioralCalc;

    @BeforeEach
    void initBehavioral() {
        // GenrePrefetchContext is inactive (no set() called) so the scorer always falls
        // back to the mock repository — identical behaviour to the pre-Batch E code.
        behavioralCalc = new BehavioralScoreCalculator(genreRepository, new ObjectMapper(), new GenrePrefetchContext());
    }

    // ── helpers ─────────────────────────────────────────────────────

    /** Build a mock UserEntity whose lifestyle+dating-prefs return the given values. */
    private UserEntity lifestyleUser(RelationshipGoal goal, KidsPreference kids,
                                     SmokingHabits smoking, DrinkingHabits drinking) {
        UserEntity user = mock(UserEntity.class);
        UserLifestyle lifestyle = UserLifestyle.builder()
                .wantsKids(kids).smokingHabits(smoking).drinkingHabits(drinking).build();
        UserDatingPreferences prefs = UserDatingPreferences.builder()
                .relationshipGoal(goal).build();
        when(user.getLifestyle()).thenReturn(lifestyle);
        when(user.getDatingPreferences()).thenReturn(prefs);
        return user;
    }

    /** Build a mock UserEntity whose personality returns the given comma-separated interests. */
    private UserEntity interestUser(String interests) {
        UserEntity user = mock(UserEntity.class);
        UserPersonality personality = UserPersonality.builder().interests(interests).build();
        when(user.getPersonality()).thenReturn(personality);
        return user;
    }

    /** Build a mock UserGenrePreference that returns genreName and weight. */
    private UserGenrePreference genrePref(String genreName, double weight) {
        UserGenrePreference pref = mock(UserGenrePreference.class);
        CanonicalGenre genre = mock(CanonicalGenre.class);
        when(genre.getName()).thenReturn(genreName);
        when(pref.getGenre()).thenReturn(genre);
        when(pref.getWeight()).thenReturn(weight);
        return pref;
    }

    // ================================================================
    //  1. LIFESTYLE SCORE CALCULATOR — GOAL COMPONENT
    // ================================================================

    @Test @Order(1)
    @DisplayName("Lifestyle › goal: same SERIOUS_RELATIONSHIP → 100 across the board → 100.0")
    void lifestyle_goal_same() {
        UserEntity a = lifestyleUser(RelationshipGoal.SERIOUS_RELATIONSHIP,
                KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.SOCIAL_DRINKER);
        UserEntity b = lifestyleUser(RelationshipGoal.SERIOUS_RELATIONSHIP,
                KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.SOCIAL_DRINKER);

        // goal=100, kids=100, smoking=100, drinking=100
        assertEquals(100.0, lifestyleCalc.calculate(a, b), 0.01);
    }

    @Test @Order(2)
    @DisplayName("Lifestyle › goal: MARRIAGE + SERIOUS_RELATIONSHIP (both serious group) → goalScore=85")
    void lifestyle_goal_bothSeriousGroup() {
        // goal=85, kids=100, smoking=100, drinking=100
        // = 85*0.4 + 100*0.3 + 100*0.15 + 100*0.15 = 34 + 30 + 15 + 15 = 94.0
        UserEntity a = lifestyleUser(RelationshipGoal.MARRIAGE,
                KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.SOCIAL_DRINKER);
        UserEntity b = lifestyleUser(RelationshipGoal.SERIOUS_RELATIONSHIP,
                KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.SOCIAL_DRINKER);

        assertEquals(94.0, lifestyleCalc.calculate(a, b), 0.01);
    }

    @Test @Order(3)
    @DisplayName("Lifestyle › goal: CASUAL_DATING + SOMETHING_CASUAL (both casual group) → goalScore=85")
    void lifestyle_goal_bothCasualGroup() {
        // goal=85, kids=100, smoking=100, drinking=100 → 94.0
        UserEntity a = lifestyleUser(RelationshipGoal.CASUAL_DATING,
                KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.SOCIAL_DRINKER);
        UserEntity b = lifestyleUser(RelationshipGoal.SOMETHING_CASUAL,
                KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.SOCIAL_DRINKER);

        assertEquals(94.0, lifestyleCalc.calculate(a, b), 0.01);
    }

    @Test @Order(4)
    @DisplayName("Lifestyle › goal: SERIOUS_RELATIONSHIP vs CASUAL_DATING (cross-group) → goalScore=10")
    void lifestyle_goal_crossGroup() {
        // goal=10, kids=100, smoking=100, drinking=100
        // = 10*0.4 + 30 + 15 + 15 = 64.0
        UserEntity a = lifestyleUser(RelationshipGoal.SERIOUS_RELATIONSHIP,
                KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.SOCIAL_DRINKER);
        UserEntity b = lifestyleUser(RelationshipGoal.CASUAL_DATING,
                KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.SOCIAL_DRINKER);

        assertEquals(64.0, lifestyleCalc.calculate(a, b), 0.01);
    }

    @Test @Order(5)
    @DisplayName("Lifestyle › goal: flexible goal (FIGURING_IT_OUT) involved → goalScore=55")
    void lifestyle_goal_flexible() {
        // goal=55, kids=100, smoking=100, drinking=100
        // = 55*0.4 + 30 + 15 + 15 = 82.0
        UserEntity a = lifestyleUser(RelationshipGoal.SERIOUS_RELATIONSHIP,
                KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.SOCIAL_DRINKER);
        UserEntity b = lifestyleUser(RelationshipGoal.FIGURING_IT_OUT,
                KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.SOCIAL_DRINKER);

        assertEquals(82.0, lifestyleCalc.calculate(a, b), 0.01);
    }

    // ================================================================
    //  2. LIFESTYLE SCORE CALCULATOR — KIDS COMPONENT
    // ================================================================

    @Test @Order(6)
    @DisplayName("Lifestyle › kids: WANTS_KIDS vs DOESNT_WANT_KIDS → kidsScore=5 (hard incompatibility)")
    void lifestyle_kids_hardIncompatibility() {
        // goal=100, kids=5, smoking=100, drinking=100
        // = 40 + 5*0.3 + 15 + 15 = 71.5
        UserEntity a = lifestyleUser(RelationshipGoal.SERIOUS_RELATIONSHIP,
                KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.SOCIAL_DRINKER);
        UserEntity b = lifestyleUser(RelationshipGoal.SERIOUS_RELATIONSHIP,
                KidsPreference.DOESNT_WANT_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.SOCIAL_DRINKER);

        assertEquals(71.5, lifestyleCalc.calculate(a, b), 0.01);
    }

    @Test @Order(7)
    @DisplayName("Lifestyle › kids: WANTS_KIDS vs OPEN_TO_KIDS → kidsScore=65 (flexible)")
    void lifestyle_kids_flexible() {
        // goal=100, kids=65, smoking=100, drinking=100
        // = 40 + 65*0.3 + 15 + 15 = 89.5
        UserEntity a = lifestyleUser(RelationshipGoal.SERIOUS_RELATIONSHIP,
                KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.SOCIAL_DRINKER);
        UserEntity b = lifestyleUser(RelationshipGoal.SERIOUS_RELATIONSHIP,
                KidsPreference.OPEN_TO_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.SOCIAL_DRINKER);

        assertEquals(89.5, lifestyleCalc.calculate(a, b), 0.01);
    }

    @Test @Order(8)
    @DisplayName("Lifestyle › kids: WANTS_KIDS vs HAS_KIDS_WANTS_MORE → kidsScore=80")
    void lifestyle_kids_wantsAndHasMore() {
        // goal=100, kids=80, smoking=100, drinking=100
        // = 40 + 80*0.3 + 15 + 15 = 94.0
        UserEntity a = lifestyleUser(RelationshipGoal.SERIOUS_RELATIONSHIP,
                KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.SOCIAL_DRINKER);
        UserEntity b = lifestyleUser(RelationshipGoal.SERIOUS_RELATIONSHIP,
                KidsPreference.HAS_KIDS_WANTS_MORE, SmokingHabits.NON_SMOKER, DrinkingHabits.SOCIAL_DRINKER);

        assertEquals(94.0, lifestyleCalc.calculate(a, b), 0.01);
    }

    // ================================================================
    //  3. LIFESTYLE SCORE CALCULATOR — SMOKING COMPONENT
    // ================================================================

    @Test @Order(9)
    @DisplayName("Lifestyle › smoking: NON_SMOKER vs REGULAR_SMOKER → smokingScore=10")
    void lifestyle_smoking_worstCase() {
        // goal=100, kids=100, smoking=10, drinking=100
        // = 40 + 30 + 10*0.15 + 15 = 86.5
        UserEntity a = lifestyleUser(RelationshipGoal.SERIOUS_RELATIONSHIP,
                KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.SOCIAL_DRINKER);
        UserEntity b = lifestyleUser(RelationshipGoal.SERIOUS_RELATIONSHIP,
                KidsPreference.WANTS_KIDS, SmokingHabits.REGULAR_SMOKER, DrinkingHabits.SOCIAL_DRINKER);

        assertEquals(86.5, lifestyleCalc.calculate(a, b), 0.01);
    }

    @Test @Order(10)
    @DisplayName("Lifestyle › smoking: NON_SMOKER vs SOCIAL_SMOKER → smokingScore=40")
    void lifestyle_smoking_nonVsSocial() {
        // goal=100, kids=100, smoking=40, drinking=100
        // = 40 + 30 + 40*0.15 + 15 = 91.0
        UserEntity a = lifestyleUser(RelationshipGoal.SERIOUS_RELATIONSHIP,
                KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.SOCIAL_DRINKER);
        UserEntity b = lifestyleUser(RelationshipGoal.SERIOUS_RELATIONSHIP,
                KidsPreference.WANTS_KIDS, SmokingHabits.SOCIAL_SMOKER, DrinkingHabits.SOCIAL_DRINKER);

        assertEquals(91.0, lifestyleCalc.calculate(a, b), 0.01);
    }

    @Test @Order(11)
    @DisplayName("Lifestyle › smoking: NON_SMOKER vs TRYING_TO_QUIT → smokingScore=65")
    void lifestyle_smoking_nonVsTrying() {
        // goal=100, kids=100, smoking=65, drinking=100
        // = 40 + 30 + 65*0.15 + 15 = 94.75
        UserEntity a = lifestyleUser(RelationshipGoal.SERIOUS_RELATIONSHIP,
                KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.SOCIAL_DRINKER);
        UserEntity b = lifestyleUser(RelationshipGoal.SERIOUS_RELATIONSHIP,
                KidsPreference.WANTS_KIDS, SmokingHabits.TRYING_TO_QUIT, DrinkingHabits.SOCIAL_DRINKER);

        assertEquals(94.75, lifestyleCalc.calculate(a, b), 0.01);
    }

    // ================================================================
    //  4. LIFESTYLE SCORE CALCULATOR — DRINKING COMPONENT
    // ================================================================

    @Test @Order(12)
    @DisplayName("Lifestyle › drinking: diff=1 (SOCIAL vs MODERATE) → drinkingScore=75")
    void lifestyle_drinking_diff1() {
        // goal=100, kids=100, smoking=100, drinking=75
        // = 40 + 30 + 15 + 75*0.15 = 96.25
        UserEntity a = lifestyleUser(RelationshipGoal.SERIOUS_RELATIONSHIP,
                KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.SOCIAL_DRINKER);
        UserEntity b = lifestyleUser(RelationshipGoal.SERIOUS_RELATIONSHIP,
                KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.MODERATE_DRINKER);

        assertEquals(96.25, lifestyleCalc.calculate(a, b), 0.01);
    }

    @Test @Order(13)
    @DisplayName("Lifestyle › drinking: diff=3 (NON_DRINKER vs REGULAR_DRINKER) → drinkingScore=20")
    void lifestyle_drinking_diff3() {
        // goal=100, kids=100, smoking=100, drinking=20
        // = 40 + 30 + 15 + 20*0.15 = 88.0
        UserEntity a = lifestyleUser(RelationshipGoal.SERIOUS_RELATIONSHIP,
                KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.NON_DRINKER);
        UserEntity b = lifestyleUser(RelationshipGoal.SERIOUS_RELATIONSHIP,
                KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.REGULAR_DRINKER);

        assertEquals(88.0, lifestyleCalc.calculate(a, b), 0.01);
    }

    // ================================================================
    //  5. LIFESTYLE — NULL HANDLING
    // ================================================================

    @Test @Order(14)
    @DisplayName("Lifestyle › null lifestyle on either side → neutral 50.0")
    void lifestyle_nullLifestyle() {
        UserEntity a = mock(UserEntity.class);
        UserEntity b = mock(UserEntity.class);
        when(a.getLifestyle()).thenReturn(null);
        when(b.getLifestyle()).thenReturn(null);

        assertEquals(50.0, lifestyleCalc.calculate(a, b));
    }

    // ================================================================
    //  6. LIFESTYLE — SEED USER PAIRS (exact expected values)
    //
    //  Seed user profiles:
    //   Marko    — SERIOUS_RELATIONSHIP, WANTS_KIDS,          NON_SMOKER,    SOCIAL_DRINKER
    //   Ana      — SERIOUS_RELATIONSHIP, OPEN_TO_KIDS,        NON_SMOKER,    SOCIAL_DRINKER
    //   Luka     — CASUAL_DATING,        DOESNT_WANT_KIDS,    NON_SMOKER,    MODERATE_DRINKER
    //   Petra    — SERIOUS_RELATIONSHIP, WANTS_KIDS,          NON_SMOKER,    SOCIAL_DRINKER
    //   Ivan     — MARRIAGE,             HAS_KIDS_WANTS_MORE, NON_SMOKER,    SOCIAL_DRINKER
    //   Sara     — FIGURING_IT_OUT,      NOT_SURE,            NON_SMOKER,    MODERATE_DRINKER
    //   Dino     — CASUAL_DATING,        DOESNT_WANT_KIDS,    SOCIAL_SMOKER, MODERATE_DRINKER
    //   Tomislav — SERIOUS_RELATIONSHIP, WANTS_KIDS,          NON_SMOKER,    MODERATE_DRINKER
    // ================================================================

    @Test @Order(15)
    @DisplayName("Seed › Marko vs Petra → 100.0 (identical lifestyle)")
    void lifestyle_seed_markoVsPetra() {
        UserEntity marko = lifestyleUser(RelationshipGoal.SERIOUS_RELATIONSHIP,
                KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.SOCIAL_DRINKER);
        UserEntity petra = lifestyleUser(RelationshipGoal.SERIOUS_RELATIONSHIP,
                KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.SOCIAL_DRINKER);
        assertEquals(100.0, lifestyleCalc.calculate(marko, petra), 0.01);
    }

    @Test @Order(16)
    @DisplayName("Seed › Marko vs Ana → 89.5 (differ only on kids: WANTS vs OPEN_TO)")
    void lifestyle_seed_markoVsAna() {
        // goal=100, kids=65, smoking=100, drinking=100
        // = 40 + 19.5 + 15 + 15 = 89.5
        UserEntity marko = lifestyleUser(RelationshipGoal.SERIOUS_RELATIONSHIP,
                KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.SOCIAL_DRINKER);
        UserEntity ana   = lifestyleUser(RelationshipGoal.SERIOUS_RELATIONSHIP,
                KidsPreference.OPEN_TO_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.SOCIAL_DRINKER);
        assertEquals(89.5, lifestyleCalc.calculate(marko, ana), 0.01);
    }

    @Test @Order(17)
    @DisplayName("Seed › Marko vs Ivan → 88.0 (SERIOUS+MARRIAGE, WANTS+HAS_MORE)")
    void lifestyle_seed_markoVsIvan() {
        // goal=85, kids=80, smoking=100, drinking=100
        // = 34 + 24 + 15 + 15 = 88.0
        UserEntity marko = lifestyleUser(RelationshipGoal.SERIOUS_RELATIONSHIP,
                KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.SOCIAL_DRINKER);
        UserEntity ivan  = lifestyleUser(RelationshipGoal.MARRIAGE,
                KidsPreference.HAS_KIDS_WANTS_MORE, SmokingHabits.NON_SMOKER, DrinkingHabits.SOCIAL_DRINKER);
        assertEquals(88.0, lifestyleCalc.calculate(marko, ivan), 0.01);
    }

    @Test @Order(18)
    @DisplayName("Seed › Marko vs Tomislav → 96.25 (same goal/kids/smoking, SOCIAL vs MODERATE drinking)")
    void lifestyle_seed_markoVsTomislav() {
        // goal=100, kids=100, smoking=100, drinking=75
        // = 40 + 30 + 15 + 11.25 = 96.25
        UserEntity marko    = lifestyleUser(RelationshipGoal.SERIOUS_RELATIONSHIP,
                KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.SOCIAL_DRINKER);
        UserEntity tomislav = lifestyleUser(RelationshipGoal.SERIOUS_RELATIONSHIP,
                KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.MODERATE_DRINKER);
        assertEquals(96.25, lifestyleCalc.calculate(marko, tomislav), 0.01);
    }

    @Test @Order(19)
    @DisplayName("Seed › Marko vs Sara → 67.75 (FIGURING_IT_OUT goal, NOT_SURE kids)")
    void lifestyle_seed_markoVsSara() {
        // goal=55, kids=65, smoking=100, drinking=75
        // = 22 + 19.5 + 15 + 11.25 = 67.75
        UserEntity marko = lifestyleUser(RelationshipGoal.SERIOUS_RELATIONSHIP,
                KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.SOCIAL_DRINKER);
        UserEntity sara  = lifestyleUser(RelationshipGoal.FIGURING_IT_OUT,
                KidsPreference.NOT_SURE, SmokingHabits.NON_SMOKER, DrinkingHabits.MODERATE_DRINKER);
        assertEquals(67.75, lifestyleCalc.calculate(marko, sara), 0.01);
    }

    @Test @Order(20)
    @DisplayName("Seed › Marko vs Luka → 31.75 (serious vs casual, wants vs doesn't want kids)")
    void lifestyle_seed_markoVsLuka() {
        // goal=10, kids=5, smoking=100, drinking=75
        // = 4 + 1.5 + 15 + 11.25 = 31.75
        UserEntity marko = lifestyleUser(RelationshipGoal.SERIOUS_RELATIONSHIP,
                KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.SOCIAL_DRINKER);
        UserEntity luka  = lifestyleUser(RelationshipGoal.CASUAL_DATING,
                KidsPreference.DOESNT_WANT_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.MODERATE_DRINKER);
        assertEquals(31.75, lifestyleCalc.calculate(marko, luka), 0.01);
    }

    @Test @Order(21)
    @DisplayName("Seed › Marko vs Dino → 22.75 (worst pair: cross-goal, no kids, non vs social smoker)")
    void lifestyle_seed_markoVsDino() {
        // goal=10, kids=5, smoking=40, drinking=75
        // = 4 + 1.5 + 6 + 11.25 = 22.75
        UserEntity marko = lifestyleUser(RelationshipGoal.SERIOUS_RELATIONSHIP,
                KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.SOCIAL_DRINKER);
        UserEntity dino  = lifestyleUser(RelationshipGoal.CASUAL_DATING,
                KidsPreference.DOESNT_WANT_KIDS, SmokingHabits.SOCIAL_SMOKER, DrinkingHabits.MODERATE_DRINKER);
        assertEquals(22.75, lifestyleCalc.calculate(marko, dino), 0.01);
    }

    @Test @Order(22)
    @DisplayName("Lifestyle score is symmetric: calculate(A,B) == calculate(B,A)")
    void lifestyle_isSymmetric() {
        UserEntity marko = lifestyleUser(RelationshipGoal.SERIOUS_RELATIONSHIP,
                KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER, DrinkingHabits.SOCIAL_DRINKER);
        UserEntity sara  = lifestyleUser(RelationshipGoal.FIGURING_IT_OUT,
                KidsPreference.NOT_SURE, SmokingHabits.NON_SMOKER, DrinkingHabits.MODERATE_DRINKER);
        assertEquals(lifestyleCalc.calculate(marko, sara),
                     lifestyleCalc.calculate(sara, marko), 0.001);
    }

    // ================================================================
    //  7. INTERESTS SCORE CALCULATOR — JACCARD SIMILARITY
    // ================================================================

    @Test @Order(23)
    @DisplayName("Interests › identical sets → 100.0")
    void interests_identical() {
        UserEntity a = interestUser("hiking,cooking,travel");
        UserEntity b = interestUser("hiking,cooking,travel");
        assertEquals(100.0, interestsCalc.calculate(a, b), 0.01);
    }

    @Test @Order(24)
    @DisplayName("Interests › no overlap → floor 10.0")
    void interests_noOverlap() {
        UserEntity a = interestUser("hiking,camping,guitar");
        UserEntity b = interestUser("fashion,yoga,brunch");
        assertEquals(10.0, interestsCalc.calculate(a, b), 0.01);
    }

    @Test @Order(25)
    @DisplayName("Interests › null personality returns neutral 50.0")
    void interests_nullPersonality() {
        UserEntity a = mock(UserEntity.class);
        UserEntity b = mock(UserEntity.class);
        when(a.getPersonality()).thenReturn(null);
        when(b.getPersonality()).thenReturn(null);
        assertEquals(50.0, interestsCalc.calculate(a, b));
    }

    @Test @Order(26)
    @DisplayName("Seed › Marko vs Tomislav → 25.0 (camping+concerts shared, union=8)")
    void interests_seed_markoVsTomislav() {
        // Marko:    concerts,hiking,camping,guitar,travel  (5 items)
        // Tomislav: gaming,camping,concerts,woodworking,dogs (5 items)
        // ∩ = {camping, concerts} = 2,  ∪ = 8
        // Jaccard = 2/8 = 25.0
        UserEntity marko    = interestUser("concerts,hiking,camping,guitar,travel");
        UserEntity tomislav = interestUser("gaming,camping,concerts,woodworking,dogs");
        assertEquals(25.0, interestsCalc.calculate(marko, tomislav), 0.01);
    }

    @Test @Order(27)
    @DisplayName("Seed › Marko vs Ana → 11.1 (only travel shared, union=9)")
    void interests_seed_markoVsAna() {
        // Marko: concerts,hiking,camping,guitar,travel (5)
        // Ana:   fashion,dancing,travel,brunch,yoga   (5)
        // ∩ = {travel} = 1,  ∪ = 9
        // Jaccard = 1/9 ≈ 11.11
        UserEntity marko = interestUser("concerts,hiking,camping,guitar,travel");
        UserEntity ana   = interestUser("fashion,dancing,travel,brunch,yoga");
        assertEquals(100.0 / 9.0, interestsCalc.calculate(marko, ana), 0.01);
    }

    @Test @Order(28)
    @DisplayName("Seed › Luka vs Sara → 25.0 (travel+cooking shared, union=8)")
    void interests_seed_lukaVsSara() {
        // Luka: festivals,gaming,travel,clubbing,cooking (5)
        // Sara: painting,yoga,travel,theatre,cooking    (5)
        // ∩ = {travel, cooking} = 2,  ∪ = 8
        // Jaccard = 2/8 = 25.0
        UserEntity luka = interestUser("festivals,gaming,travel,clubbing,cooking");
        UserEntity sara = interestUser("painting,yoga,travel,theatre,cooking");
        assertEquals(25.0, interestsCalc.calculate(luka, sara), 0.01);
    }

    @Test @Order(29)
    @DisplayName("Interests › getSharedInterests returns sorted list")
    void interests_getSharedInterests_sorted() {
        UserEntity a = interestUser("hiking,camping,travel,guitar");
        UserEntity b = interestUser("travel,camping,yoga,cooking");
        var shared = interestsCalc.getSharedInterests(a, b);
        assertEquals(2, shared.size());
        assertEquals("camping", shared.get(0)); // alphabetically first
        assertEquals("travel",  shared.get(1));
    }

    @Test @Order(30)
    @DisplayName("Interests › matching is case-insensitive and whitespace-tolerant")
    void interests_caseInsensitiveAndTrimmed() {
        UserEntity a = interestUser("Hiking, Camping, Travel");
        UserEntity b = interestUser("hiking,camping,travel");
        assertEquals(100.0, interestsCalc.calculate(a, b), 0.01);
    }

    @Test @Order(31)
    @DisplayName("Interests › synonym: literature matches reading → 100.0")
    void interests_synonym_literatureReading() {
        UserEntity a = interestUser("literature");
        UserEntity b = interestUser("reading");
        assertEquals(100.0, interestsCalc.calculate(a, b), 0.01);
    }

    @Test @Order(32)
    @DisplayName("Interests › synonym: live music matches concerts → 100.0 (both normalize to concert)")
    void interests_synonym_liveMusicConcerts() {
        // "live music" → synonym → "concert", "concerts" → plural strip → "concert"
        UserEntity a = interestUser("live music");
        UserEntity b = interestUser("concerts");
        assertEquals(100.0, interestsCalc.calculate(a, b), 0.01);
    }

    @Test @Order(33)
    @DisplayName("Interests › synonym: baking+food both normalize to cooking → 100.0")
    void interests_synonym_bakingFoodCooking() {
        UserEntity a = interestUser("baking,food");
        UserEntity b = interestUser("cooking");
        // baking→cooking, food→cooking → {cooking} vs {cooking} → 100.0
        assertEquals(100.0, interestsCalc.calculate(a, b), 0.01);
    }

    @Test @Order(34)
    @DisplayName("Interests › zero-overlap floor: completely disjoint → exactly 10.0")
    void interests_zeroOverlapFloor() {
        UserEntity a = interestUser("swimming,photography,writing");
        UserEntity b = interestUser("gaming,travel,meditation");
        assertEquals(10.0, interestsCalc.calculate(a, b), 0.01);
    }

    // ================================================================
    //  8. BEHAVIORAL SCORE CALCULATOR — COLD START + COSINE SIMILARITY
    // ================================================================

    @Test @Order(35)
    @DisplayName("Behavioral › cold start: totalLikes < 5 → neutral 50.0")
    void behavioral_coldStart() {
        UserBehavioralProfile profile = UserBehavioralProfile.builder()
                .totalLikes(3)
                .learnedGenreWeights("{\"rock\": 0.9}")
                .confidenceLevel(0.0)
                .build();
        UserEntity candidate = mock(UserEntity.class);

        assertEquals(50.0, behavioralCalc.calculate(candidate, profile));
    }

    @Test @Order(36)
    @DisplayName("Behavioral › null profile → neutral 50.0")
    void behavioral_nullProfile() {
        UserEntity candidate = mock(UserEntity.class);
        assertEquals(50.0, behavioralCalc.calculate(candidate, null));
    }

    @Test @Order(37)
    @DisplayName("Behavioral › empty learned weights → neutral 50.0")
    void behavioral_emptyLearnedWeights() {
        UserBehavioralProfile profile = UserBehavioralProfile.builder()
                .totalLikes(10)
                .learnedGenreWeights("{}")
                .confidenceLevel(0.2)
                .build();
        UserEntity candidate = mock(UserEntity.class);
        when(candidate.getId()).thenReturn("cand-1");
        when(genreRepository.findByUserIdOrderByWeightDesc("cand-1")).thenReturn(List.of());

        assertEquals(50.0, behavioralCalc.calculate(candidate, profile));
    }

    @Test @Order(38)
    @DisplayName("Behavioral › identical genre profile → near 100.0")
    void behavioral_identicalGenreProfile() {
        // Learned centroid: rock=0.9, pop=0.5
        // Candidate:        rock=0.9, pop=0.5 (same)
        // Cosine similarity = 1.0 → score ≈ 100.0
        UserBehavioralProfile profile = UserBehavioralProfile.builder()
                .totalLikes(10)
                .learnedGenreWeights("{\"rock\": 0.9, \"pop\": 0.5}")
                .confidenceLevel(0.2)
                .build();

        UserEntity candidate = mock(UserEntity.class);
        when(candidate.getId()).thenReturn("cand-identical");
        when(genreRepository.findByUserIdOrderByWeightDesc("cand-identical"))
                .thenReturn(List.of(genrePref("rock", 0.9), genrePref("pop", 0.5)));

        double score = behavioralCalc.calculate(candidate, profile);
        assertEquals(100.0, score, 0.01);
    }

    @Test @Order(39)
    @DisplayName("Behavioral › zero genre overlap → 0.0")
    void behavioral_noGenreOverlap() {
        // Learned centroid: rock=0.9
        // Candidate:        jazz=0.8  (completely different)
        // Dot product = 0 → cosine = 0 → score = 0.0
        UserBehavioralProfile profile = UserBehavioralProfile.builder()
                .totalLikes(10)
                .learnedGenreWeights("{\"rock\": 0.9}")
                .confidenceLevel(0.2)
                .build();

        UserEntity candidate = mock(UserEntity.class);
        when(candidate.getId()).thenReturn("cand-no-overlap");
        when(genreRepository.findByUserIdOrderByWeightDesc("cand-no-overlap"))
                .thenReturn(List.of(genrePref("jazz", 0.8)));

        double score = behavioralCalc.calculate(candidate, profile);
        assertEquals(0.0, score, 0.01);
    }

    @Test @Order(40)
    @DisplayName("Behavioral › partial genre overlap — cosine similarity is between 0 and 100")
    void behavioral_partialOverlap() {
        // Learned centroid: rock=0.9, electronic=0.7
        // Candidate:        rock=0.6, jazz=0.8   (partial overlap)
        UserBehavioralProfile profile = UserBehavioralProfile.builder()
                .totalLikes(10)
                .learnedGenreWeights("{\"rock\": 0.9, \"electronic\": 0.7}")
                .confidenceLevel(0.2)
                .build();

        UserEntity candidate = mock(UserEntity.class);
        when(candidate.getId()).thenReturn("cand-partial");
        when(genreRepository.findByUserIdOrderByWeightDesc("cand-partial"))
                .thenReturn(List.of(genrePref("rock", 0.6), genrePref("jazz", 0.8)));

        double score = behavioralCalc.calculate(candidate, profile);
        assertTrue(score > 0.0 && score < 100.0,
                "Partial overlap should yield score between 0 and 100, got: " + score);
    }
}
