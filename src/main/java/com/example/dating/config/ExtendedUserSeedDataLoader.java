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
import com.example.dating.models.user.preferences.dao.UserMusicPreferences;
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
 * Seeds 200 fully-completed users for comprehensive matching algorithm testing.
 * All users have RegistrationStage.FINISHED with every related table populated:
 * UserEntity, UserMusicPreferences, UserLifestyle, UserPersonality,
 * UserDatingPreferences, UserPrivacySettings, UserPhoto, UserGenrePreference.
 *
 * Password for all users: password123
 * Emails follow pattern: ext.user.001@test.hr … ext.user.200@test.hr
 *
 * ⚠️ WARNING: Development/testing only. Remove before production!
 */
@Slf4j
@Component
@Order(3)
@RequiredArgsConstructor
public class ExtendedUserSeedDataLoader implements CommandLineRunner {

    private final UserJpaRepository userRepository;
    private final UserMusicPreferencesRepository musicPreferencesRepository;
    private final UserLifestyleRepository lifestyleRepository;
    private final UserPersonalityRepository personalityRepository;
    private final UserDatingPreferencesRepository datingPreferencesRepository;
    private final UserPrivacySettingsRepository privacySettingsRepository;
    private final UserPhotoRepository photoRepository;
    private final UserGenrePreferenceRepository genrePreferenceRepository;
    private final CanonicalGenreRepository genreRepository;
    private final PasswordEncoder passwordEncoder;

    // ─── Cities ──────────────────────────────────────────────────────────────
    private static final String[][] CITIES = {
        {"Zagreb",    "45.8150", "15.9819"},
        {"Split",     "43.5081", "16.4402"},
        {"Rijeka",    "45.3271", "14.4422"},
        {"Osijek",    "45.5550", "18.6955"},
        {"Zadar",     "44.1194", "15.2314"},
        {"Pula",      "44.8666", "13.8496"},
        {"Dubrovnik", "42.6507", "18.0944"},
        {"Karlovac",  "45.4870", "15.5478"},
        {"Varaždin",  "46.3044", "16.3366"},
        {"Šibenik",   "43.7350", "15.9000"}
    };

    // ─── Names ───────────────────────────────────────────────────────────────
    private static final String[] MALE_NAMES = {
        "Alen", "Andrej", "Boris", "Branko", "Bruno", "Danijel", "Darko",
        "Davor", "Denis", "Dinko", "Domagoj", "Dragan", "Fran", "Franko",
        "Goran", "Grgo", "Hrvoje", "Igor", "Ivo", "Jadran", "Josip", "Juraj",
        "Krešimir", "Kristijan", "Leo", "Lovro", "Marijan", "Mario", "Matija",
        "Miro", "Mladen", "Nino", "Ozren", "Patrik", "Petar", "Robert",
        "Roman", "Sandro", "Stjepan", "Tin", "Tvrtko", "Vedran", "Vinko",
        "Vlado", "Željko", "Zvonimir", "Andro", "Bartol", "Dominik", "Edvin"
    };

    private static final String[] FEMALE_NAMES = {
        "Adriana", "Andela", "Andrea", "Anja", "Barbara", "Branka", "Dalma",
        "Daniela", "Dijana", "Doris", "Ela", "Emanuela", "Filipa", "Gordana",
        "Helena", "Iva", "Ivona", "Jana", "Jelena", "Josipa", "Katarina",
        "Kristina", "Lena", "Martina", "Melita", "Mirjana", "Monika", "Nadia",
        "Natalia", "Nina", "Renata", "Roberta", "Romana", "Sandra", "Silvija",
        "Snježana", "Sunčana", "Tamara", "Tea", "Tihana", "Valentina",
        "Vedrana", "Vesna", "Viktorija", "Zrinka", "Anica", "Blanka",
        "Cvita", "Dunja", "Franka"
    };

    private static final String[] OTHER_NAMES = {
        "Alex", "Ariel", "Blair", "Casey", "Dakota", "Eden", "Finley",
        "Harper", "Jamie", "Jordan", "Kai", "Lake", "Logan", "Morgan",
        "Parker", "Quinn", "Reese", "Riley", "River", "Rowan"
    };

    private static final String[] SURNAMES = {
        "Babić", "Barić", "Bašić", "Bečirević", "Bogdanić", "Borozan",
        "Bošnjak", "Božić", "Brkić", "Bušić", "Car", "Cindrić", "Čuljak",
        "Dragičević", "Dukić", "Đurić", "Filipović", "Franić", "Gabrić",
        "Gašpar", "Golubić", "Grubić", "Gudelj", "Herceg", "Hrgović",
        "Husić", "Ivanić", "Ivanović", "Jakšić", "Jelić", "Jurčić",
        "Jurković", "Kalajdžić", "Katić", "Klaić", "Knežević", "Kolić",
        "Korunić", "Kovač", "Kovačević", "Krajina", "Kramarić", "Krstić",
        "Kučan", "Lalić", "Lazić", "Leko", "Livajić", "Ljubičić", "Lovrić",
        "Lukač", "Lulić", "Mandić", "Marić", "Marković", "Martinović",
        "Matić", "Matković", "Medak", "Mihajlović", "Mikulić", "Milić",
        "Miočić", "Mirković", "Morić", "Musa", "Nović", "Orlić", "Ostojić",
        "Pandža", "Pavelić", "Pavičić", "Pavlović", "Perić", "Petrović",
        "Pleše", "Radić", "Rajić", "Rogić", "Šarić", "Šimić", "Šoštarić",
        "Špoljar", "Subašić", "Tadić", "Tomljanović", "Tomić", "Vidić",
        "Vlaho", "Vranješ", "Vrbanec", "Vukušić", "Zečević", "Zelić",
        "Zrinić", "Anić", "Benić", "Ćurić", "Dumančić", "Ergović"
    };

    // ─── Lifestyle data ───────────────────────────────────────────────────────
    private static final String[] OCCUPATIONS = {
        "Software Engineer", "Frontend Developer", "UX Designer", "Product Manager",
        "Marketing Manager", "Data Analyst", "Teacher", "Architect", "Doctor",
        "Nurse", "Lawyer", "Accountant", "Journalist", "Photographer", "Chef",
        "Graphic Designer", "Business Analyst", "HR Specialist", "Sales Manager",
        "Financial Advisor", "Civil Engineer", "Pharmacist", "Veterinarian",
        "Dentist", "Psychologist", "Social Worker", "Event Planner",
        "Real Estate Agent", "Physical Therapist", "Electrician", "Musician",
        "Artist", "Writer", "Project Manager", "DevOps Engineer",
        "Backend Developer", "Mobile Developer", "Content Creator",
        "Digital Marketer", "Brand Strategist"
    };

    private static final String[] COMPANIES = {
        "Infobip", "Rimac Automobili", "Span d.d.", "IN2 Group", "Epam Systems",
        "Nsoft", "Vip Mobile", "HT Ericsson", "Alma Career", "Deloitte Croatia",
        "PwC Croatia", "Addiko Bank", "Atlantic Grupa", "Podravka", "Valamar Hotels",
        "Arena Hospitality", "Erste Bank", "Croatia osiguranje", "Combis",
        "King ICT", "Sofascore", "Photomath", "Freelance", "Self-employed"
    };

    private static final Religion[] RELIGIONS = {
        Religion.CHRISTIAN, Religion.CHRISTIAN, Religion.MUSLIM, Religion.CHRISTIAN, Religion.AGNOSTIC,
        Religion.ATHEIST, Religion.SPIRITUAL, Religion.BUDDHIST, Religion.OTHER,
        Religion.PREFER_NOT_TO_SAY
    };

    private static final PoliticalViews[] POLITICAL_VIEWS = {
        PoliticalViews.LIBERAL, PoliticalViews.PROGRESSIVE, PoliticalViews.MODERATE,
        PoliticalViews.CONSERVATIVE, PoliticalViews.MODERATE,
        PoliticalViews.LIBERAL, PoliticalViews.APOLITICAL, PoliticalViews.PROGRESSIVE
    };

    // ─── Genre profiles: 20 distinct music tastes ─────────────────────────────
    // Each entry: {genreName, weight}
    private static final String[][][] GENRE_PROFILES = {
        // 0: Rock
        {{"rock","0.95"},{"alternative-rock","0.88"},{"indie-rock","0.80"},{"hard-rock","0.75"},{"grunge","0.70"}},
        // 1: Pop
        {{"pop","0.95"},{"dance-pop","0.88"},{"indie-pop","0.78"},{"synth-pop","0.72"},{"k-pop","0.65"}},
        // 2: Electronic / House
        {{"electronic","0.95"},{"house","0.92"},{"techno","0.85"},{"trance","0.75"},{"drum-and-bass","0.70"}},
        // 3: Hip-Hop / Trap
        {{"hip-hop","0.95"},{"trap","0.92"},{"drill","0.80"},{"rnb","0.75"},{"soul","0.65"}},
        // 4: Jazz / Blues
        {{"jazz","0.95"},{"smooth-jazz","0.85"},{"blues","0.80"},{"soul","0.75"},{"bebop","0.68"}},
        // 5: Metal
        {{"metal","0.95"},{"death-metal","0.88"},{"thrash-metal","0.82"},{"progressive-metal","0.78"},{"metalcore","0.72"}},
        // 6: Indie / Alternative
        {{"indie","0.92"},{"indie-rock","0.88"},{"alternative","0.85"},{"indie-pop","0.75"},{"indie-folk","0.68"}},
        // 7: Folk / Acoustic / Singer-Songwriter
        {{"folk","0.92"},{"indie-folk","0.88"},{"singer-songwriter","0.85"},{"acoustic","0.80"},{"americana","0.72"}},
        // 8: R&B / Neo-Soul
        {{"rnb","0.92"},{"neo-soul","0.88"},{"soul","0.85"},{"contemporary-rnb","0.80"},{"funk","0.70"}},
        // 9: Classical
        {{"classical","0.92"},{"baroque","0.80"},{"romantic","0.85"},{"opera","0.70"},{"ambient","0.65"}},
        // 10: Latin / Reggaeton
        {{"latin","0.92"},{"reggaeton","0.88"},{"salsa","0.80"},{"bachata","0.78"},{"latin-pop","0.72"}},
        // 11: Punk / Grunge / Emo
        {{"punk","0.92"},{"punk-rock","0.88"},{"alternative-rock","0.82"},{"grunge","0.78"},{"emo","0.65"}},
        // 12: Deep Electronic / Techno
        {{"techno","0.92"},{"house","0.88"},{"electronic","0.85"},{"dubstep","0.75"},{"future-bass","0.68"}},
        // 13: Country / Americana / Bluegrass
        {{"country","0.92"},{"bluegrass","0.82"},{"americana","0.85"},{"folk","0.75"},{"alt-country","0.68"}},
        // 14: Eclectic / Wide taste
        {{"indie","0.82"},{"pop","0.78"},{"rock","0.75"},{"electronic","0.70"},{"soul","0.68"},{"jazz","0.62"}},
        // 15: Reggae / Afrobeat / World
        {{"reggae","0.92"},{"afrobeat","0.85"},{"soul","0.75"},{"funk","0.70"},{"world","0.68"}},
        // 16: Ambient / Chill / Downtempo
        {{"ambient","0.92"},{"chillwave","0.85"},{"downtempo","0.82"},{"electronic","0.72"},{"acoustic","0.65"}},
        // 17: Singer-Songwriter / Acoustic
        {{"singer-songwriter","0.95"},{"acoustic","0.90"},{"folk","0.82"},{"indie-folk","0.78"},{"indie-pop","0.68"}},
        // 18: Hip-Hop / R&B crossover
        {{"rnb","0.88"},{"hip-hop","0.85"},{"neo-soul","0.78"},{"soul","0.80"},{"trap","0.72"}},
        // 19: Hard Rock / Metal crossover
        {{"hard-rock","0.88"},{"rock","0.85"},{"metal","0.80"},{"progressive-rock","0.75"},{"progressive-metal","0.68"}}
    };

    // ─── Personality content pools ────────────────────────────────────────────
    private static final String[] BIOS = {
        "Music is my first language. Whether it's a festival sunrise or headphones on the train, the soundtrack always matters.",
        "Looking for someone who has strong opinions about albums. Bonus points if you've cried at a concert.",
        "Chef by day, vinyl collector by night. My kitchen always has background music going.",
        "I believe you can tell a lot about a person by their most played song. What's yours?",
        "Avid hiker and weekend traveler. Music is the perfect companion for every adventure.",
        "Software engineer who unwinds with live music. I've been to more concerts than I should admit.",
        "Coffee, good books, and a playlist for every mood. Let's talk music over drinks.",
        "Artist and visual storyteller. I find inspiration in sounds as much as in sights.",
        "Teacher by profession, dreamer by nature. Big fan of finding new artists before they blow up.",
        "Fitness enthusiast who needs the right BPM to push through every workout.",
        "Journalist with a passion for stories – in writing and in songs.",
        "Architecture student who thinks great music and great design solve the same problem: emotion.",
        "I host a small listening party once a month at my apartment. New friends welcome.",
        "Veterinarian who plays guitar badly but enthusiastically. My patients seem to appreciate it.",
        "Night owl who finds the best music between midnight and 3am.",
        "Foodie, traveler, and music nerd. My playlists are categorized by country and cuisine.",
        "Freelance photographer who scores my own work. Every photo has a song attached in my head.",
        "Nurse who needs proper decompression after shifts. Music, long walks, and good company.",
        "Lawyer who argues cases by day and debates album rankings by night.",
        "Passionate about sustainability. Looking for someone equally curious about the world."
    };

    private static final String[] INTERESTS = {
        "hiking,photography,cooking,travel,reading",
        "gaming,movies,fitness,music production,podcasts",
        "yoga,meditation,painting,travel,wine tasting",
        "cycling,coffee,architecture,football,concerts",
        "cooking,board games,travel,running,cinema",
        "surfing,skateboarding,digital art,festivals,street food",
        "climbing,chess,sci-fi books,camping,craft beer",
        "dancing,fashion,brunch,theatre,interior design",
        "bouldering,photography,minimalism,slow travel,literature",
        "crossfit,nutrition,podcasts,history,dogs",
        "swimming,marine life,environmental activism,travel,reading",
        "football,video games,BBQ,road trips,sports analysis",
        "pilates,wellbeing,cooking,nature walks,journaling",
        "poetry,indie films,thrift shopping,coffee,cats",
        "sailing,water polo,local history,cooking,dogs",
        "volunteering,urban gardening,jazz clubs,cycling,philosophy",
        "marathon running,meal prep,travel photography,dogs,tech",
        "stand-up comedy,improv,board games,restaurants,Netflix",
        "pottery,plant parenting,farmers markets,baking,yoga",
        "startup culture,tech meetups,coffee,mountain biking,travel"
    };

    private static final String[] QUOTES = {
        "Without music, life would be a mistake. – Nietzsche",
        "Music gives a soul to the universe and wings to the mind. – Plato",
        "One good thing about music, when it hits you, you feel no pain. – Bob Marley",
        "Music is the shorthand of emotion. – Tolstoy",
        "Where words fail, music speaks. – Hans Christian Andersen",
        "Music is what feelings sound like.",
        "I haven't understood a bar of music in my life, but I have felt it. – Stravinsky",
        "Music is the wine that fills the cup of silence. – Robert Fripp",
        "Life is one grand sweet song, so start the music.",
        "After silence, that which comes nearest to expressing the inexpressible is music. – Huxley",
        "Music is the universal language of mankind. – Longfellow",
        "Music can change the world because it can change people. – Bono",
        "The music is not in the notes, but in the silence between them. – Mozart",
        "Music is the art which is most nigh to tears and memory. – Oscar Wilde",
        "Music produces a kind of pleasure which human nature cannot do without. – Confucius",
        "If music be the food of love, play on. – Shakespeare",
        "Music is the strongest form of magic. – Marilyn Manson",
        "One day your life will flash before your eyes. Make sure it's worth watching.",
        "Not all those who wander are lost. – Tolkien",
        "Be yourself; everyone else is already taken. – Oscar Wilde"
    };

    private static final String[] CONV_STARTERS = {
        "I once drove 400km just to see a band play for 45 minutes. Worth it.",
        "I can recommend a playlist for literally any mood. Challenge me.",
        "My most controversial opinion: the bridge is always the best part of a song.",
        "I've been to 47 concerts. Ask me about the best and worst ones.",
        "I still have a physical music collection and I'm not sorry about it.",
        "I once cried at a film score and I don't think that should disqualify me from anything.",
        "My Spotify Wrapped is deeply embarrassing and also very accurate.",
        "I can name the year a song came out based on production style alone.",
        "I've been told my driving playlist is too aggressive for city traffic.",
        "I have a playlist called 'background music' that I can never use as background music.",
        "I've never skipped an album intro track in my life.",
        "My cooking playlists are genre-matched to the cuisine.",
        "I judge restaurants partly by their background music. You should too.",
        "Ask me about my 'albums that changed everything' list. I update it annually.",
        "I remember exactly where I was when I first heard each of my top 5 albums.",
        "I have a running playlist that peaks at exactly the 3km mark.",
        "My reading speed increases measurably with the right background music.",
        "I could talk about live sound engineering for an embarrassing amount of time.",
        "My friends trust me to pick the pre-game playlist. Highest honor.",
        "I always listen to an album front-to-back on the first listen."
    };

    private static final String[] LOOKING_FOR = {
        "Someone who gets excited about new music the same way I do.",
        "A genuine connection with someone who takes art seriously but not themselves too seriously.",
        "Someone to swap playlists with and discover new things together.",
        "Real conversations, shared adventures, and someone with a genuine passion for something.",
        "An equal – intellectually curious, kind, and able to hold their own in a music debate.",
        "Someone equally comfortable at a festival and on a quiet evening at home.",
        "A person with a story worth listening to and opinions worth discussing.",
        "Someone spontaneous enough for last-minute concert tickets.",
        "Connection first. Everything else follows naturally.",
        "Looking for the kind of person who makes you want to make a playlist for them.",
        "Someone who appreciates silence as much as a great song.",
        "A partner who challenges me musically. Introduce me to artists I'd never find.",
        "Authenticity over perfection. Real people with real tastes.",
        "Someone who doesn't need a party to have a good time.",
        "Adventure with someone grounded. The world is large and there's a lot of music in it.",
        "A person who brings something new into my world every week.",
        "Depth over surface level. I want to really know someone.",
        "Someone who remembers the name of the opening act.",
        "Easy chemistry where conversations and playlists flow naturally.",
        "Someone to go to concerts with and argue about albums with."
    };

    // ─── Entry point ──────────────────────────────────────────────────────────
    @Override
    @Transactional
    public void run(String... args) {
        if (userRepository.count() > 40) {
            log.info("Extended seed users already exist. Skipping.");
            return;
        }
        if (genreRepository.count() == 0) {
            log.warn("No genres found. Skipping extended user seed.");
            return;
        }

        log.info("Seeding 200 fully-completed extended test users...");

        Map<String, CanonicalGenre> genreMap = new HashMap<>();
        genreRepository.findAll().forEach(g -> genreMap.put(g.getName(), g));

        for (int i = 0; i < 200; i++) {
            createFullUser(i, genreMap);
        }

        log.info("Successfully seeded 200 extended test users.");
    }

    // ─── Per-user creation ────────────────────────────────────────────────────
    private void createFullUser(int i, Map<String, CanonicalGenre> genreMap) {
        Gender gender = pickGender(i);
        String firstName = pickFirstName(i, gender);
        String surname = SURNAMES[i % SURNAMES.length];
        String email = String.format("ext.user.%03d@test.hr", i + 1);

        // Ages 20–43
        LocalDate dob = LocalDate.of(2006 - (i % 24), (i % 12) + 1, (i % 20) + 1);
        SexualOrientation orientation = pickOrientation(i, gender);
        String[] city = CITIES[i % CITIES.length];

        // 1. UserEntity
        UserEntity user = userRepository.save(UserEntity.builder()
                .name(firstName + " " + surname)
                .email(email)
                .dateOfBirth(dob)
                .gender(gender)
                .sexualOrientation(orientation)
                .locationCity(city[0])
                .locationCountry("Croatia")
                .locationLat(new BigDecimal(city[1]))
                .locationLon(new BigDecimal(city[2]))
                .authProvider(AuthProvider.EMAIL)
                .passwordHash(passwordEncoder.encode("password123"))
                .emailVerified(true)
                .registrationStage(RegistrationStage.FINISHED)
                .premiumStatus(i % 5 == 0)
                .profileCompletionScore(100)
                .language("hr")
                .createdAt(LocalDateTime.now().minusDays(i % 365))
                .build());

        // 2. UserMusicPreferences
        ConcertFrequency[] concertFreqs = ConcertFrequency.values();
        MusicImportance[] musicImps = MusicImportance.values();
        String[][] decadeCombos = {
            {"1990s","2000s"}, {"2000s","2010s"}, {"1980s","1990s"},
            {"2010s","2020s"}, {"1970s","1980s"}, {"1990s","2000s","2010s"}
        };
        String[] listenTimes = {
            "morning,commute", "evening,night", "workout,commute",
            "morning,evening", "night,weekend", "commute,lunch,evening"
        };
        musicPreferencesRepository.save(UserMusicPreferences.builder()
                .user(user)
                .favoriteGenres(buildFavoriteGenres(i))
                .concertFrequency(concertFreqs[i % concertFreqs.length])
                .musicImportance(musicImps[2 + (i % 3)]) // IMPORTANT, VERY_IMPORTANT, LIFE_IS_MUSIC
                .favoriteDecades(String.join(",", decadeCombos[i % decadeCombos.length]))
                .openToNewGenres(i % 5 != 0)
                .listeningTimes(listenTimes[i % listenTimes.length])
                .hoursPerDay(1 + (i % 5))
                .build());

        // 3. UserLifestyle
        EducationLevel[] eduLevels = EducationLevel.values();
        RelationshipStatus[] relStatuses = {
            RelationshipStatus.SINGLE, RelationshipStatus.SINGLE, RelationshipStatus.SINGLE,
            RelationshipStatus.DIVORCED, RelationshipStatus.SEPARATED
        };
        KidsPreference[] kidsPrefs = KidsPreference.values();
        DrinkingHabits[] drinkingOptions = {
            DrinkingHabits.NON_DRINKER, DrinkingHabits.SOCIAL_DRINKER, DrinkingHabits.MODERATE_DRINKER
        };
        ExerciseFrequency[] exerciseFreqs = ExerciseFrequency.values();
        lifestyleRepository.save(UserLifestyle.builder()
                .user(user)
                .education(eduLevels[i % (eduLevels.length - 1)])
                .occupation(OCCUPATIONS[i % OCCUPATIONS.length])
                .company(COMPANIES[i % COMPANIES.length])
                .relationshipStatus(relStatuses[i % relStatuses.length])
                .wantsKids(kidsPrefs[i % (kidsPrefs.length - 1)])
                .smokingHabits(i % 6 == 0 ? SmokingHabits.SOCIAL_SMOKER : SmokingHabits.NON_SMOKER)
                .drinkingHabits(drinkingOptions[i % drinkingOptions.length])
                .exerciseFrequency(exerciseFreqs[i % (exerciseFreqs.length - 1)])
                .religion(RELIGIONS[i % RELIGIONS.length])
                .politicalViews(POLITICAL_VIEWS[i % POLITICAL_VIEWS.length])
                .build());

        // 4. UserPersonality
        MBTI[] mbtiTypes = MBTI.values();
        personalityRepository.save(UserPersonality.builder()
                .user(user)
                .bio(BIOS[i % BIOS.length])
                .interests(INTERESTS[i % INTERESTS.length])
                .mbti(mbtiTypes[i % mbtiTypes.length])
                .lookingForText(LOOKING_FOR[i % LOOKING_FOR.length])
                .favoriteQuote(QUOTES[i % QUOTES.length])
                .conversationStarters(CONV_STARTERS[i % CONV_STARTERS.length])
                .build());

        // 5. UserDatingPreferences
        RelationshipGoal[] goals = RelationshipGoal.values();
        int[] musicWeights = {50, 60, 70, 75, 80, 90, 100};
        datingPreferencesRepository.save(UserDatingPreferences.builder()
                .user(user)
                .minAge(18 + (i % 5))
                .maxAge(35 + (i % 10))
                .maxDistanceKm(25 + (i % 8) * 25)
                .interestedInGenders(buildInterestedInGenders(gender, orientation))
                .relationshipGoal(goals[i % (goals.length - 1)])
                .dealBreakers("smoking,dishonesty")
                .showMe("EVERYONE")
                .musicMatchImportance(musicWeights[i % musicWeights.length])
                .build());

        // 6. UserPrivacySettings
        privacySettingsRepository.save(UserPrivacySettings.builder()
                .user(user)
                .isProfilePublic(true)
                .showAge(i % 10 != 0)
                .showDistance(true)
                .showLastActive(i % 5 != 0)
                .discoverable(true)
                .showLikedByYou(i % 3 == 0)
                .showSpotifyProfile(i % 4 != 0)
                .showMusicStats(true)
                .incognitoMode(false)
                .readReceipts(i % 5 != 0)
                .build());

        // 7. UserPhotos (2 per user)
        photoRepository.save(UserPhoto.builder()
                .user(user)
                .imageUrl("https://picsum.photos/seed/ext" + (i * 2) + "/400/600")
                .displayOrder(0)
                .isPrimary(true)
                .build());
        photoRepository.save(UserPhoto.builder()
                .user(user)
                .imageUrl("https://picsum.photos/seed/ext" + (i * 2 + 1) + "/400/600")
                .displayOrder(1)
                .isPrimary(false)
                .caption("At a concert last summer")
                .build());

        // 8. UserGenrePreferences
        String[][] profile = GENRE_PROFILES[i % GENRE_PROFILES.length];
        List<UserGenrePreference> prefs = new ArrayList<>();
        int rank = 1;
        for (String[] entry : profile) {
            CanonicalGenre genre = genreMap.get(entry[0]);
            if (genre != null) {
                prefs.add(UserGenrePreference.builder()
                        .user(user)
                        .genre(genre)
                        .weight(Double.parseDouble(entry[1]))
                        .rank(rank++)
                        .source(GenrePreferenceSource.SEED_DATA)
                        .confidence(1.0)
                        .createdAt(LocalDateTime.now())
                        .build());
            }
        }
        genrePreferenceRepository.saveAll(prefs);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────
    private Gender pickGender(int i) {
        int mod = i % 10;
        if (mod < 5) return Gender.MALE;
        if (mod < 9) return Gender.FEMALE;
        return Gender.NON_BINARY;
    }

    private String pickFirstName(int i, Gender gender) {
        return switch (gender) {
            case MALE -> MALE_NAMES[i % MALE_NAMES.length];
            case FEMALE -> FEMALE_NAMES[i % FEMALE_NAMES.length];
            default -> OTHER_NAMES[i % OTHER_NAMES.length];
        };
    }

    private SexualOrientation pickOrientation(int i, Gender gender) {
        return switch (i % 10) {
            case 6 -> SexualOrientation.BISEXUAL;
            case 7 -> gender == Gender.MALE ? SexualOrientation.GAY : SexualOrientation.LESBIAN;
            case 8 -> SexualOrientation.PANSEXUAL;
            case 9 -> SexualOrientation.QUEER;
            default -> SexualOrientation.STRAIGHT;
        };
    }

    private String buildInterestedInGenders(Gender gender, SexualOrientation orientation) {
        return switch (orientation) {
            case STRAIGHT -> gender == Gender.MALE ? "FEMALE" : "MALE";
            case GAY -> "MALE";
            case LESBIAN -> "FEMALE";
            default -> "MALE,FEMALE,NON_BINARY";
        };
    }

    private String buildFavoriteGenres(int i) {
        StringBuilder sb = new StringBuilder();
        for (String[] entry : GENRE_PROFILES[i % GENRE_PROFILES.length]) {
            if (!sb.isEmpty()) sb.append(",");
            sb.append(entry[0]);
        }
        return sb.toString();
    }
}
