package com.example.dating.config;

import com.example.dating.enums.matching.GenrePreferenceSource;
import com.example.dating.enums.user.*;
import com.example.dating.models.matching.dao.CanonicalGenre;
import com.example.dating.models.matching.dao.UserGenrePreference;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.dating.dao.UserDatingPreferences;
import com.example.dating.models.user.lifestyle.dao.UserLifestyle;
import com.example.dating.models.user.personality.dao.UserPersonality;
import com.example.dating.models.user.photos.dao.UserPhoto;
import com.example.dating.models.user.privacy.dao.UserPrivacySettings;
import com.example.dating.repositories.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Seeds the database with diverse test users across Croatian cities.
 * All FINISHED users have full profiles: lifestyle, personality, dating prefs,
 * privacy settings, photos, and genre preferences — enabling all scoring
 * dimensions (music, lifestyle, interests, location) and hard filters
 * (gender, distance, privacy) to be exercised during testing.
 *
 * ⚠️ WARNING: Development/testing only. Remove before production!
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class UserSeedDataLoader implements CommandLineRunner {

    private final UserJpaRepository userRepository;
    private final UserGenrePreferenceRepository preferenceRepository;
    private final CanonicalGenreRepository genreRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserLifestyleRepository lifestyleRepository;
    private final UserPersonalityRepository personalityRepository;
    private final UserDatingPreferencesRepository datingPreferencesRepository;
    private final UserPrivacySettingsRepository privacySettingsRepository;
    private final UserPhotoRepository photoRepository;

    private static final Map<String, double[]> CROATIAN_CITIES = Map.of(
        "Zagreb",    new double[]{45.8150, 15.9819},
        "Split",     new double[]{43.5081, 16.4402},
        "Rijeka",    new double[]{45.3271, 14.4422},
        "Osijek",    new double[]{45.5550, 18.6955},
        "Zadar",     new double[]{44.1194, 15.2314},
        "Pula",      new double[]{44.8666, 13.8496},
        "Dubrovnik", new double[]{42.6507, 18.0944},
        "Karlovac",  new double[]{45.4870, 15.5478},
        "Varaždin",  new double[]{46.3044, 16.3366},
        "Šibenik",   new double[]{43.7350, 15.9000}
    );

    private final Map<String, UserEntity> userMap = new HashMap<>();

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepository.count() > 4) {
            log.info("Users already exist. Skipping test user seed data.");
            return;
        }
        if (genreRepository.count() == 0) {
            log.warn("No genres found. Skipping user seed - run GenreSeedDataLoader first.");
            return;
        }

        log.info("Seeding Croatian test users with full profiles...");

        // === COMPLETE PROFILES (FINISHED) — all scoring dimensions exercised ===
        createCompleteRockFan();
        createCompletePopLover();
        createCompleteElectronicFan();
        createCompleteIndieFan();
        createCompleteJazzLover();
        createCompleteEclecticUser();
        createCompleteHipHopFan();
        createCompleteMetalhead();

        // === NEARLY COMPLETE (PRIVACY_SETTINGS) ===
        createNearlyCompleteUser1();
        createNearlyCompleteUser2();
        createNearlyCompleteUser3();
        createNearlyCompleteUser4();

        // === MID-LEVEL COMPLETION (DATING_PREFERENCES) ===
        createMidLevelUser1();
        createMidLevelUser2();
        createMidLevelUser3();
        createMidLevelUser4();

        // === MUSIC PREFERENCES ONLY (MUSIC_PREFERENCES) ===
        createMusicOnlyUser1();
        createMusicOnlyUser2();
        createMusicOnlyUser3();
        createMusicOnlyUser4();

        // === EARLY STAGE (PHOTOS) ===
        createEarlyStageUser1();
        createEarlyStageUser2();
        createEarlyStageUser3();

        // === MINIMAL DATA (BASIC_PROFILE) ===
        createMinimalUser1();
        createMinimalUser2();
        createMinimalUser3();

        // === JUST REGISTERED (STARTED) ===
        createJustRegisteredUser1();
        createJustRegisteredUser2();
        createJustRegisteredUser3();

        // === EDGE CASES ===
        createUserWithNoPreferences();
        createUserWithOneGenre();
        createUserWith15Genres();

        log.info("Successfully seeded {} test users.", userRepository.count());
    }

    // ==================== COMPLETE PROFILES (FINISHED) ====================

    private void createCompleteRockFan() {
        // Male, Zagreb, straight, 30yo — looking for women aged 24-35, max 100km
        // Serious relationship, non-smoker, social drinker, wants kids
        // Interests: concerts, hiking, camping, guitar, travel
        UserEntity user = createUser(
            "Marko Horvat", "marko.horvat@test.hr",
            LocalDate.of(1995, 3, 15),
            Gender.MALE, SexualOrientation.STRAIGHT, "Zagreb",
            100, RegistrationStage.FINISHED
        );
        userMap.put("complete_rock_fan", user);
        addPreferences(user, Map.of(
            "rock", 0.95, "alternative-rock", 0.90, "indie-rock", 0.85,
            "punk-rock", 0.75, "grunge", 0.80
        ));
        addLifestyle(user, KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER,
                DrinkingHabits.SOCIAL_DRINKER, EducationLevel.BACHELORS_DEGREE,
                "Software Engineer", RelationshipStatus.SINGLE);
        addPersonality(user, "Rock music is my life. Always at concerts, always discovering new bands.",
                "concerts,hiking,camping,guitar,travel", MBTI.ENFP);
        addDatingPreferences(user, 24, 35, 500, "FEMALE",
                RelationshipGoal.SERIOUS_RELATIONSHIP, 80);
        addPrivacySettings(user);
        addPhoto(user, "marko");
    }

    private void createCompletePopLover() {
        // Female, Split, straight, 27yo — looking for men aged 25-35, max 80km
        // Serious relationship, non-smoker, social drinker, open to kids
        // Interests: fashion, dancing, travel, brunch, yoga
        UserEntity user = createUser(
            "Ana Kovačić", "ana.kovacic@test.hr",
            LocalDate.of(1998, 7, 22),
            Gender.FEMALE, SexualOrientation.STRAIGHT, "Split",
            100, RegistrationStage.FINISHED
        );
        userMap.put("complete_pop_lover", user);
        addPreferences(user, Map.of(
            "pop", 0.95, "dance-pop", 0.90, "indie-pop", 0.80,
            "synth-pop", 0.75, "k-pop", 0.70
        ));
        addLifestyle(user, KidsPreference.OPEN_TO_KIDS, SmokingHabits.NON_SMOKER,
                DrinkingHabits.SOCIAL_DRINKER, EducationLevel.BACHELORS_DEGREE,
                "Marketing Manager", RelationshipStatus.SINGLE);
        addPersonality(user, "Pop music enthusiast and dance floor regular. Split by heart.",
                "fashion,dancing,travel,brunch,yoga", MBTI.ESFP);
        addDatingPreferences(user, 25, 35, 500, "MALE",
                RelationshipGoal.SERIOUS_RELATIONSHIP, 70);
        addPrivacySettings(user);
        addPhoto(user, "ana");
    }

    private void createCompleteElectronicFan() {
        // Male, Rijeka, bisexual, 29yo — interested in MALE,FEMALE, max 150km
        // Casual dating, non-smoker, moderate drinker, doesn't want kids
        // Interests: festivals, gaming, travel, clubbing, DJ sets
        UserEntity user = createUser(
            "Luka Babić", "luka.babic@test.hr",
            LocalDate.of(1996, 11, 5),
            Gender.MALE, SexualOrientation.BISEXUAL, "Rijeka",
            100, RegistrationStage.FINISHED
        );
        userMap.put("complete_electronic_fan", user);
        addPreferences(user, Map.of(
            "electronic", 0.95, "house", 0.90, "techno", 0.88,
            "ambient", 0.85, "trance", 0.80
        ));
        addLifestyle(user, KidsPreference.DOESNT_WANT_KIDS, SmokingHabits.NON_SMOKER,
                DrinkingHabits.MODERATE_DRINKER, EducationLevel.BACHELORS_DEGREE,
                "UX Designer", RelationshipStatus.SINGLE);
        addPersonality(user, "Electronic music is the soundtrack of my life. Festivals, clubs, studio.",
                "festivals,gaming,travel,clubbing,cooking", MBTI.ENTP);
        addDatingPreferences(user, 22, 35, 500, "MALE,FEMALE",
                RelationshipGoal.CASUAL_DATING, 90);
        addPrivacySettings(user);
        addPhoto(user, "luka");
    }

    private void createCompleteIndieFan() {
        // Female, Zadar, straight, 28yo — looking for men aged 25-35, max 120km
        // Serious relationship, non-smoker, social drinker, wants kids
        // Interests: photography, reading, hiking, coffee, cinema
        UserEntity user = createUser(
            "Petra Jurić", "petra.juric@test.hr",
            LocalDate.of(1997, 9, 12),
            Gender.FEMALE, SexualOrientation.STRAIGHT, "Zadar",
            100, RegistrationStage.FINISHED
        );
        userMap.put("complete_indie_fan", user);
        addPreferences(user, Map.of(
            "indie", 0.95, "indie-rock", 0.90, "indie-pop", 0.85,
            "alternative", 0.80, "singer-songwriter", 0.75
        ));
        addLifestyle(user, KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER,
                DrinkingHabits.SOCIAL_DRINKER, EducationLevel.MASTERS_DEGREE,
                "Journalist", RelationshipStatus.SINGLE);
        addPersonality(user, "Indie music and good books are my two loves. Always looking for the next hidden gem.",
                "photography,reading,hiking,coffee,cinema", MBTI.INFJ);
        addDatingPreferences(user, 25, 35, 500, "MALE",
                RelationshipGoal.SERIOUS_RELATIONSHIP, 75);
        addPrivacySettings(user);
        addPhoto(user, "petra");
    }

    private void createCompleteJazzLover() {
        // Male, Osijek, straight, 37yo — looking for women aged 28-42, max 80km
        // Marriage, non-smoker, social drinker, has kids wants more
        // Interests: jazz clubs, cooking, cycling, wine tasting, literature
        UserEntity user = createUser(
            "Ivan Knežević", "ivan.knezevic@test.hr",
            LocalDate.of(1988, 3, 10),
            Gender.MALE, SexualOrientation.STRAIGHT, "Osijek",
            100, RegistrationStage.FINISHED
        );
        userMap.put("complete_jazz_lover", user);
        addPreferences(user, Map.of(
            "jazz", 0.95, "smooth-jazz", 0.90, "bebop", 0.85,
            "blues", 0.75, "soul", 0.70
        ));
        addLifestyle(user, KidsPreference.HAS_KIDS_WANTS_MORE, SmokingHabits.NON_SMOKER,
                DrinkingHabits.SOCIAL_DRINKER, EducationLevel.MASTERS_DEGREE,
                "Lawyer", RelationshipStatus.DIVORCED);
        addPersonality(user, "Jazz is the only music that sounds better live. Looking for someone who appreciates depth.",
                "jazz clubs,cooking,cycling,wine tasting,literature", MBTI.INTJ);
        addDatingPreferences(user, 28, 42, 500, "FEMALE",
                RelationshipGoal.MARRIAGE, 60);
        addPrivacySettings(user);
        addPhoto(user, "ivan");
    }

    private void createCompleteEclecticUser() {
        // Female, Pula, bisexual, 33yo — interested in MALE,FEMALE, max 200km
        // Figuring it out, non-smoker, moderate drinker, not sure about kids
        // Interests: painting, yoga, travel, theatre, cooking
        UserEntity user = createUser(
            "Sara Tomić", "sara.tomic@test.hr",
            LocalDate.of(1993, 6, 18),
            Gender.FEMALE, SexualOrientation.BISEXUAL, "Pula",
            100, RegistrationStage.FINISHED
        );
        userMap.put("complete_eclectic", user);
        addPreferences(user, Map.of(
            "indie", 0.85, "indie-folk", 0.80, "indie-rock", 0.75,
            "electronic", 0.65, "jazz", 0.60, "alternative", 0.70
        ));
        addLifestyle(user, KidsPreference.NOT_SURE, SmokingHabits.NON_SMOKER,
                DrinkingHabits.MODERATE_DRINKER, EducationLevel.MASTERS_DEGREE,
                "Graphic Designer", RelationshipStatus.SINGLE);
        addPersonality(user, "Eclectic taste in music and life. Every genre has something to offer.",
                "painting,yoga,travel,theatre,cooking", MBTI.INFP);
        addDatingPreferences(user, 28, 40, 500, "MALE,FEMALE",
                RelationshipGoal.FIGURING_IT_OUT, 65);
        addPrivacySettings(user);
        addPhoto(user, "sara");
    }

    private void createCompleteHipHopFan() {
        // Male, Dubrovnik, straight, 32yo — looking for women aged 24-35, max 100km
        // Casual dating, social smoker, moderate drinker, doesn't want kids
        // Interests: gaming, football, travel, food, basketball
        UserEntity user = createUser(
            "Dino Šarić", "dino.saric@test.hr",
            LocalDate.of(1994, 1, 28),
            Gender.MALE, SexualOrientation.STRAIGHT, "Dubrovnik",
            100, RegistrationStage.FINISHED
        );
        userMap.put("complete_hiphop_fan", user);
        addPreferences(user, Map.of(
            "hip-hop", 0.95, "trap", 0.92, "drill", 0.85,
            "rnb", 0.80, "soul", 0.70
        ));
        addLifestyle(user, KidsPreference.DOESNT_WANT_KIDS, SmokingHabits.SOCIAL_SMOKER,
                DrinkingHabits.MODERATE_DRINKER, EducationLevel.BACHELORS_DEGREE,
                "Content Creator", RelationshipStatus.SINGLE);
        addPersonality(user, "Hip-hop is culture. I live it. Always on the lookout for new drops.",
                "gaming,football,travel,food,basketball", MBTI.ESTP);
        addDatingPreferences(user, 22, 34, 500, "FEMALE",
                RelationshipGoal.CASUAL_DATING, 85);
        addPrivacySettings(user);
        addPhoto(user, "dino");
    }

    private void createCompleteMetalhead() {
        // Male, Karlovac, straight, 34yo — looking for women aged 25-40, max 150km
        // Serious relationship, non-smoker, moderate drinker, wants kids
        // Interests: gaming, camping, concerts, woodworking, dogs
        UserEntity user = createUser(
            "Tomislav Pavlović", "tomislav.pavlovic@test.hr",
            LocalDate.of(1991, 12, 1),
            Gender.MALE, SexualOrientation.STRAIGHT, "Karlovac",
            100, RegistrationStage.FINISHED
        );
        userMap.put("complete_metalhead", user);
        addPreferences(user, Map.of(
            "metal", 0.95, "death-metal", 0.90, "progressive-metal", 0.88,
            "thrash-metal", 0.82
        ));
        addLifestyle(user, KidsPreference.WANTS_KIDS, SmokingHabits.NON_SMOKER,
                DrinkingHabits.MODERATE_DRINKER, EducationLevel.BACHELORS_DEGREE,
                "Civil Engineer", RelationshipStatus.SINGLE);
        addPersonality(user, "Metal is precision, emotion, and power. The most expressive music on Earth.",
                "gaming,camping,concerts,woodworking,dogs", MBTI.ISTP);
        addDatingPreferences(user, 25, 40, 500, "FEMALE",
                RelationshipGoal.SERIOUS_RELATIONSHIP, 70);
        addPrivacySettings(user);
        addPhoto(user, "tomislav");
    }

    // ==================== NEARLY COMPLETE (PRIVACY_SETTINGS) ====================

    private void createNearlyCompleteUser1() {
        UserEntity user = createUser(
            "Maja Petrović", "maja.petrovic@test.hr",
            LocalDate.of(1996, 4, 20),
            Gender.FEMALE, SexualOrientation.STRAIGHT, "Zagreb",
            90, RegistrationStage.PRIVACY_SETTINGS
        );
        userMap.put("nearly_complete_1", user);
        addPreferences(user, Map.of("pop", 0.85, "indie-pop", 0.80, "rock", 0.70));
    }

    private void createNearlyCompleteUser2() {
        UserEntity user = createUser(
            "Filip Novak", "filip.novak@test.hr",
            LocalDate.of(1992, 8, 14),
            Gender.MALE, SexualOrientation.STRAIGHT, "Split",
            88, RegistrationStage.PRIVACY_SETTINGS
        );
        userMap.put("nearly_complete_2", user);
        addPreferences(user, Map.of("electronic", 0.90, "house", 0.85, "indie", 0.65));
    }

    private void createNearlyCompleteUser3() {
        UserEntity user = createUser(
            "Ivana Marić", "ivana.maric@test.hr",
            LocalDate.of(1999, 2, 11),
            Gender.FEMALE, SexualOrientation.BISEXUAL, "Rijeka",
            92, RegistrationStage.PRIVACY_SETTINGS
        );
        userMap.put("nearly_complete_3", user);
        addPreferences(user, Map.of("indie", 0.90, "folk", 0.80, "singer-songwriter", 0.75));
    }

    private void createNearlyCompleteUser4() {
        UserEntity user = createUser(
            "Mateo Božić", "mateo.bozic@test.hr",
            LocalDate.of(1995, 10, 7),
            Gender.MALE, SexualOrientation.STRAIGHT, "Osijek",
            87, RegistrationStage.PRIVACY_SETTINGS
        );
        userMap.put("nearly_complete_4", user);
        addPreferences(user, Map.of("rock", 0.88, "alternative-rock", 0.82, "metal", 0.70));
    }

    // ==================== MID-LEVEL COMPLETION (DATING_PREFERENCES) ====================

    private void createMidLevelUser1() {
        UserEntity user = createUser(
            "Antonija Lovrić", "antonija.lovric@test.hr",
            LocalDate.of(1997, 5, 25),
            Gender.FEMALE, SexualOrientation.STRAIGHT, "Zadar",
            65, RegistrationStage.DATING_PREFERENCES
        );
        userMap.put("mid_level_1", user);
        addPreferences(user, Map.of("pop", 0.80, "dance-pop", 0.75));
    }

    private void createMidLevelUser2() {
        UserEntity user = createUser(
            "Karlo Vidović", "karlo.vidovic@test.hr",
            LocalDate.of(1994, 9, 3),
            Gender.MALE, SexualOrientation.STRAIGHT, "Pula",
            68, RegistrationStage.DATING_PREFERENCES
        );
        userMap.put("mid_level_2", user);
        addPreferences(user, Map.of("hip-hop", 0.85, "rnb", 0.75, "pop", 0.65));
    }

    private void createMidLevelUser3() {
        UserEntity user = createUser(
            "Laura Jurković", "laura.jurkovic@test.hr",
            LocalDate.of(1998, 12, 19),
            Gender.FEMALE, SexualOrientation.STRAIGHT, "Varaždin",
            70, RegistrationStage.DATING_PREFERENCES
        );
        userMap.put("mid_level_3", user);
        addPreferences(user, Map.of("indie", 0.82, "alternative", 0.78));
    }

    private void createMidLevelUser4() {
        UserEntity user = createUser(
            "David Bošnjak", "david.bosnjak@test.hr",
            LocalDate.of(1993, 7, 16),
            Gender.MALE, SexualOrientation.GAY, "Dubrovnik",
            62, RegistrationStage.DATING_PREFERENCES
        );
        userMap.put("mid_level_4", user);
        addPreferences(user, Map.of("electronic", 0.88, "techno", 0.85, "house", 0.82));
    }

    // ==================== MUSIC PREFERENCES ONLY ====================

    private void createMusicOnlyUser1() {
        UserEntity user = createUser(
            "Elena Perić", "elena.peric@test.hr",
            LocalDate.of(1996, 3, 8),
            Gender.FEMALE, SexualOrientation.STRAIGHT, "Šibenik",
            45, RegistrationStage.MUSIC_PREFERENCES
        );
        userMap.put("music_only_1", user);
        addPreferences(user, Map.of("jazz", 0.90, "blues", 0.80));
    }

    private void createMusicOnlyUser2() {
        UserEntity user = createUser(
            "Roko Stipić", "roko.stipic@test.hr",
            LocalDate.of(1995, 11, 23),
            Gender.MALE, SexualOrientation.STRAIGHT, "Zagreb",
            48, RegistrationStage.MUSIC_PREFERENCES
        );
        userMap.put("music_only_2", user);
        addPreferences(user, Map.of("rock", 0.92, "punk-rock", 0.85, "alternative-rock", 0.80));
    }

    private void createMusicOnlyUser3() {
        UserEntity user = createUser(
            "Lucija Barišić", "lucija.barisic@test.hr",
            LocalDate.of(1999, 6, 30),
            Gender.FEMALE, SexualOrientation.LESBIAN, "Split",
            50, RegistrationStage.MUSIC_PREFERENCES
        );
        userMap.put("music_only_3", user);
        addPreferences(user, Map.of("indie-pop", 0.88, "synth-pop", 0.82, "pop", 0.75));
    }

    private void createMusicOnlyUser4() {
        UserEntity user = createUser(
            "Jakov Martinović", "jakov.martinovic@test.hr",
            LocalDate.of(1992, 1, 17),
            Gender.MALE, SexualOrientation.STRAIGHT, "Rijeka",
            42, RegistrationStage.MUSIC_PREFERENCES
        );
        userMap.put("music_only_4", user);
        addPreferences(user, Map.of("metal", 0.93, "thrash-metal", 0.88));
    }

    // ==================== EARLY STAGE (PHOTOS) ====================

    private void createEarlyStageUser1() {
        UserEntity user = createUser(
            "Mia Šimić", "mia.simic@test.hr",
            LocalDate.of(1997, 4, 12),
            Gender.FEMALE, SexualOrientation.STRAIGHT, "Osijek",
            30, RegistrationStage.PHOTOS
        );
        userMap.put("early_stage_1", user);
    }

    private void createEarlyStageUser2() {
        UserEntity user = createUser(
            "Leon Grgić", "leon.grgic@test.hr",
            LocalDate.of(1994, 8, 9),
            Gender.MALE, SexualOrientation.STRAIGHT, "Karlovac",
            28, RegistrationStage.PHOTOS
        );
        userMap.put("early_stage_2", user);
    }

    private void createEarlyStageUser3() {
        UserEntity user = createUser(
            "Nika Kralj", "nika.kralj@test.hr",
            LocalDate.of(1998, 10, 21),
            Gender.FEMALE, SexualOrientation.BISEXUAL, "Zadar",
            25, RegistrationStage.PHOTOS
        );
        userMap.put("early_stage_3", user);
    }

    // ==================== MINIMAL DATA (BASIC_PROFILE) ====================

    private void createMinimalUser1() {
        userMap.put("minimal_1", createUser(
            "Bruno Matić", "bruno.matic@test.hr",
            LocalDate.of(1996, 2, 14),
            Gender.MALE, SexualOrientation.STRAIGHT, "Varaždin",
            15, RegistrationStage.BASIC_PROFILE
        ));
    }

    private void createMinimalUser2() {
        userMap.put("minimal_2", createUser(
            "Klara Filipović", "klara.filipovic@test.hr",
            LocalDate.of(1999, 7, 5),
            Gender.FEMALE, SexualOrientation.STRAIGHT, "Pula",
            12, RegistrationStage.BASIC_PROFILE
        ));
    }

    private void createMinimalUser3() {
        userMap.put("minimal_3", createUser(
            "Nikola Vukić", "nikola.vukic@test.hr",
            LocalDate.of(1993, 11, 28),
            Gender.MALE, SexualOrientation.STRAIGHT, "Šibenik",
            18, RegistrationStage.BASIC_PROFILE
        ));
    }

    // ==================== JUST REGISTERED (STARTED) ====================

    private void createJustRegisteredUser1() {
        userMap.put("just_registered_1", createUser(
            "Tena Pavić", "tena.pavic@test.hr",
            LocalDate.of(1997, 9, 2),
            Gender.FEMALE, SexualOrientation.STRAIGHT, "Zagreb",
            5, RegistrationStage.STARTED
        ));
    }

    private void createJustRegisteredUser2() {
        userMap.put("just_registered_2", createUser(
            "Ante Radić", "ante.radic@test.hr",
            LocalDate.of(1995, 5, 19),
            Gender.MALE, SexualOrientation.STRAIGHT, "Split",
            3, RegistrationStage.STARTED
        ));
    }

    private void createJustRegisteredUser3() {
        userMap.put("just_registered_3", createUser(
            "Dora Belić", "dora.belic@test.hr",
            LocalDate.of(1998, 3, 24),
            Gender.FEMALE, SexualOrientation.STRAIGHT, "Rijeka",
            0, RegistrationStage.STARTED
        ));
    }

    // ==================== EDGE CASES ====================

    private void createUserWithNoPreferences() {
        userMap.put("no_preferences", createUser(
            "Josip Novosel", "josip.novosel@test.hr",
            LocalDate.of(1994, 6, 11),
            Gender.MALE, SexualOrientation.STRAIGHT, "Dubrovnik",
            75, RegistrationStage.PERSONALITY
        ));
    }

    private void createUserWithOneGenre() {
        UserEntity user = createUser(
            "Nikolina Đurić", "nikolina.duric@test.hr",
            LocalDate.of(1997, 1, 7),
            Gender.FEMALE, SexualOrientation.STRAIGHT, "Osijek",
            55, RegistrationStage.LIFESTYLE
        );
        userMap.put("one_genre", user);
        addPreferences(user, Map.of("rock", 1.0));
    }

    private void createUserWith15Genres() {
        UserEntity user = createUser(
            "Antonio Herceg", "antonio.herceg@test.hr",
            LocalDate.of(1991, 12, 31),
            Gender.MALE, SexualOrientation.STRAIGHT, "Zagreb",
            95, RegistrationStage.PRIVACY_SETTINGS
        );
        userMap.put("many_genres", user);
        addPreferences(user, Map.ofEntries(
            Map.entry("rock", 0.90),   Map.entry("pop", 0.85),
            Map.entry("electronic", 0.80), Map.entry("indie", 0.78),
            Map.entry("hip-hop", 0.75), Map.entry("jazz", 0.72),
            Map.entry("alternative", 0.70), Map.entry("folk", 0.68),
            Map.entry("rnb", 0.65),   Map.entry("blues", 0.62),
            Map.entry("soul", 0.60),  Map.entry("funk", 0.58),
            Map.entry("reggae", 0.55), Map.entry("punk", 0.52),
            Map.entry("metal", 0.50)
        ));
    }

    // ==================== HELPER METHODS ====================

    private UserEntity createUser(
            String name, String email, LocalDate dateOfBirth,
            Gender gender, SexualOrientation orientation, String city,
            int profileCompletionScore, RegistrationStage registrationStage) {

        double[] coords = CROATIAN_CITIES.get(city);
        if (coords == null) throw new IllegalArgumentException("Unknown Croatian city: " + city);

        UserEntity user = UserEntity.builder()
                .name(name)
                .email(email)
                .dateOfBirth(dateOfBirth)
                .gender(gender)
                .sexualOrientation(orientation)
                .locationCity(city)
                .locationCountry("Croatia")
                .locationLat(BigDecimal.valueOf(coords[0]))
                .locationLon(BigDecimal.valueOf(coords[1]))
                .authProvider(AuthProvider.EMAIL)
                .passwordHash(passwordEncoder.encode("password123"))
                .emailVerified(true)
                .registrationStage(registrationStage)
                .premiumStatus(false)
                .profileCompletionScore(profileCompletionScore)
                .language("hr")
                .createdAt(LocalDateTime.now())
                .build();

        return userRepository.save(user);
    }

    private void addLifestyle(UserEntity user,
            KidsPreference wantsKids, SmokingHabits smoking, DrinkingHabits drinking,
            EducationLevel education, String occupation, RelationshipStatus relStatus) {
        lifestyleRepository.save(UserLifestyle.builder()
                .user(user)
                .education(education)
                .occupation(occupation)
                .wantsKids(wantsKids)
                .smokingHabits(smoking)
                .drinkingHabits(drinking)
                .exerciseFrequency(ExerciseFrequency.FEW_TIMES_A_WEEK)
                .relationshipStatus(relStatus)
                .religion(Religion.AGNOSTIC)
                .build());
    }

    private void addPersonality(UserEntity user, String bio, String interests, MBTI mbti) {
        personalityRepository.save(UserPersonality.builder()
                .user(user)
                .bio(bio)
                .interests(interests)
                .mbti(mbti)
                .build());
    }

    private void addDatingPreferences(UserEntity user,
            int minAge, int maxAge, int maxDistanceKm,
            String interestedInGenders, RelationshipGoal goal, int musicImportance) {
        datingPreferencesRepository.save(UserDatingPreferences.builder()
                .user(user)
                .minAge(minAge)
                .maxAge(maxAge)
                .maxDistanceKm(maxDistanceKm)
                .interestedInGenders(interestedInGenders)
                .relationshipGoal(goal)
                .showMe("EVERYONE")
                .musicMatchImportance(musicImportance)
                .build());
    }

    private void addPrivacySettings(UserEntity user) {
        privacySettingsRepository.save(UserPrivacySettings.builder()
                .user(user)
                .isProfilePublic(true)
                .showAge(true)
                .showDistance(true)
                .showLastActive(true)
                .discoverable(true)
                .showLikedByYou(false)
                .showSpotifyProfile(true)
                .showMusicStats(true)
                .incognitoMode(false)
                .readReceipts(true)
                .build());
    }

    private void addPhoto(UserEntity user, String seed) {
        photoRepository.save(UserPhoto.builder()
                .user(user)
                .imageUrl("https://picsum.photos/seed/" + seed + "/400/600")
                .displayOrder(0)
                .isPrimary(true)
                .build());
    }

    private void addPreferences(UserEntity user, Map<String, Double> genreWeights) {
        List<UserGenrePreference> preferences = new ArrayList<>();
        int rank = 1;

        List<Map.Entry<String, Double>> sorted = genreWeights.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .toList();

        for (Map.Entry<String, Double> entry : sorted) {
            Optional<CanonicalGenre> genreOpt = genreRepository.findByName(entry.getKey());
            if (genreOpt.isEmpty()) {
                log.warn("Genre '{}' not found. Skipping preference for user {}", entry.getKey(), user.getName());
                continue;
            }
            preferences.add(UserGenrePreference.builder()
                    .user(user)
                    .genre(genreOpt.get())
                    .weight(entry.getValue())
                    .rank(rank++)
                    .source(GenrePreferenceSource.SEED_DATA)
                    .confidence(1.0)
                    .createdAt(LocalDateTime.now())
                    .build());
        }

        preferenceRepository.saveAll(preferences);
    }
}
