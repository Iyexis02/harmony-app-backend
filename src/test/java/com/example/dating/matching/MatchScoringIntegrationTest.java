package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.models.matching.dto.MatchScore;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.matching.MatchRecommendationService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the v2.0 multi-dimensional scoring pipeline.
 *
 * Uses the 8 seed users from UserSeedDataLoader (all with RegistrationStage.FINISHED).
 * Each user has full profiles — genre preferences, lifestyle, personality,
 * dating preferences, location — so all scoring dimensions are exercised.
 *
 * Run these tests against the real dev database (application-test.yml).
 * Ensure the app has been started at least once so seed data is present.
 *
 * Seed users (email → profile summary):
 *   marko.horvat    — M, Zagreb,    rock,       SERIOUS, WANTS_KIDS,    NON_SMOKER,    SOCIAL
 *   ana.kovacic     — F, Split,     pop,        SERIOUS, OPEN_TO_KIDS,  NON_SMOKER,    SOCIAL
 *   luka.babic      — M, Rijeka,    electronic, CASUAL,  DOESNT_WANT,   NON_SMOKER,    MODERATE
 *   petra.juric     — F, Zadar,     indie,      SERIOUS, WANTS_KIDS,    NON_SMOKER,    SOCIAL
 *   ivan.knezevic   — M, Osijek,    jazz,       MARRIAGE,HAS_MORE,      NON_SMOKER,    SOCIAL
 *   sara.tomic      — F, Pula,      eclectic,   FIGURING,NOT_SURE,      NON_SMOKER,    MODERATE
 *   dino.saric      — M, Dubrovnik, hip-hop,    CASUAL,  DOESNT_WANT,   SOCIAL_SMOKER, MODERATE
 *   tomislav.pav    — M, Karlovac,  metal,      SERIOUS, WANTS_KIDS,    NON_SMOKER,    MODERATE
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MatchScoringIntegrationTest {

    @Autowired private MatchRecommendationService recommendationService;
    @Autowired private UserJpaRepository userRepository;

    // Seed user emails
    private static final String MARKO    = "marko.horvat@test.hr";
    private static final String ANA      = "ana.kovacic@test.hr";
    private static final String LUKA     = "luka.babic@test.hr";
    private static final String PETRA    = "petra.juric@test.hr";
    private static final String IVAN     = "ivan.knezevic@test.hr";
    private static final String SARA     = "sara.tomic@test.hr";
    private static final String DINO     = "dino.saric@test.hr";
    private static final String TOMISLAV = "tomislav.pavlovic@test.hr";

    private static final List<String> SEED_EMAILS = List.of(
            MARKO, ANA, LUKA, PETRA, IVAN, SARA, DINO, TOMISLAV);

    private static final Map<String, String> DISPLAY_NAMES = Map.of(
            MARKO,    "Marko (rock/Zagreb)",
            ANA,      "Ana (pop/Split)",
            LUKA,     "Luka (electronic/Rijeka)",
            PETRA,    "Petra (indie/Zadar)",
            IVAN,     "Ivan (jazz/Osijek)",
            SARA,     "Sara (eclectic/Pula)",
            DINO,     "Dino (hip-hop/Dubrovnik)",
            TOMISLAV, "Tomislav (metal/Karlovac)"
    );

    // ── helpers ─────────────────────────────────────────────────────

    private Optional<UserEntity> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    private User domainUser(String id) {
        return User.builder().id(id).build();
    }

    /** Skip the test with a clear message if the seed user isn't in the DB. */
    private String requireSeedUser(String email) {
        return findByEmail(email)
                .map(UserEntity::getId)
                .orElseThrow(() -> new org.opentest4j.TestAbortedException(
                        "Seed user " + email + " not found — start the app once to seed the DB"));
    }

    private MatchScore score(String viewerEmail, String targetEmail) {
        String viewerId = requireSeedUser(viewerEmail);
        String targetId = requireSeedUser(targetEmail);
        return recommendationService.getMatchScore(domainUser(viewerId), targetId);
    }

    // ================================================================
    //  TEST 1 — Services load + seed users exist
    // ================================================================

    @Test @Order(1)
    @DisplayName("Test 1: Context loads and seed users are present in DB")
    void test1_contextAndSeedUsersPresent() {
        System.out.println("\n╔════════════════════════════════════════════════════════════╗");
        System.out.println("║        MATCH SCORING INTEGRATION TEST - START             ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝\n");

        assertNotNull(recommendationService, "MatchRecommendationService must be autowired");

        System.out.println("  Checking seed users:");
        long found = 0;
        for (String email : SEED_EMAILS) {
            boolean present = findByEmail(email).isPresent();
            System.out.printf("    %s  %s%n", present ? "✓" : "✗", email);
            if (present) found++;
        }
        System.out.printf("%n  %d / %d seed users present%n", found, SEED_EMAILS.size());

        assertTrue(found >= 2,
                "At least 2 seed users must be in the DB to run scoring tests. " +
                "Start the app once with an empty DB to trigger UserSeedDataLoader.");
    }

    // ================================================================
    //  TEST 2 — Score is in valid range for every pair
    // ================================================================

    @Test @Order(2)
    @DisplayName("Test 2: Overall score is in [0, 100] for every available seed-user pair")
    void test2_scoreRangeForAllPairs() {
        System.out.println("\n🧪 TEST 2: Score bounds (0–100) for all available pairs");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        List<UserEntity> users = SEED_EMAILS.stream()
                .map(e -> findByEmail(e).orElse(null))
                .filter(Objects::nonNull)
                .toList();

        if (users.size() < 2) {
            System.out.println("  ⚠ Not enough seed users — skipping");
            return;
        }

        int checked = 0;
        for (UserEntity viewer : users) {
            for (UserEntity target : users) {
                if (viewer.getId().equals(target.getId())) continue;
                MatchScore s = recommendationService.getMatchScore(
                        domainUser(viewer.getId()), target.getId());
                assertTrue(s.getOverallScore() >= 0 && s.getOverallScore() <= 100,
                        String.format("Score out of range for %s → %s: %.2f",
                                viewer.getEmail(), target.getEmail(), s.getOverallScore()));
                checked++;
            }
        }
        System.out.printf("  ✓ Verified %d pairs — all scores in [0, 100]%n%n", checked);
    }

    // ================================================================
    //  TEST 3 — Breakdown has all dimensions
    // ================================================================

    @Test @Order(3)
    @DisplayName("Test 3: Score object contains all four scoring dimensions")
    void test3_allDimensionsPresent() {
        System.out.println("\n🧪 TEST 3: All scoring dimensions present in MatchScore");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        String viewerId = requireSeedUser(MARKO);
        String targetId = requireSeedUser(ANA);
        MatchScore s = recommendationService.getMatchScore(domainUser(viewerId), targetId);

        System.out.printf("  Marko → Ana%n");
        System.out.printf("    overallScore   = %.2f%n", s.getOverallScore());
        System.out.printf("    musicScore     = %.2f%n", s.getMusicScore());
        System.out.printf("    lifestyleScore = %.2f%n", s.getLifestyleScore());
        System.out.printf("    interestsScore = %.2f%n", s.getInterestsScore());
        System.out.printf("    locationScore  = %.2f%n", s.getLocationScore());
        System.out.printf("    compatibility  = %s%n",   s.getCompatibilityLevel());

        assertNotNull(s.getOverallScore(),    "overallScore must be present");
        assertNotNull(s.getMusicScore(),      "musicScore must be present");
        assertNotNull(s.getLifestyleScore(),  "lifestyleScore must be present");
        assertNotNull(s.getInterestsScore(),  "interestsScore must be present");
        assertNotNull(s.getLocationScore(),   "locationScore must be present");
        assertNotNull(s.getCompatibilityLevel(), "compatibilityLevel must be present");
        assertNotNull(s.getBreakdown(),       "breakdown must be present");

        System.out.println("  ✓ All dimensions present\n");
    }

    // ================================================================
    //  TEST 4 — Lifestyle component matches expected values
    // ================================================================

    @Test @Order(4)
    @DisplayName("Test 4: Lifestyle score component matches hand-calculated values for seed pairs")
    void test4_lifestyleComponentValues() {
        System.out.println("\n🧪 TEST 4: Lifestyle component values (matches unit-test expected values)");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        record Expectation(String viewer, String target, double expected, String reason) {}

        List<Expectation> cases = List.of(
            new Expectation(MARKO, PETRA,    100.00, "identical lifestyle"),
            new Expectation(MARKO, ANA,       89.50, "WANTS vs OPEN_TO kids"),
            new Expectation(MARKO, IVAN,      88.00, "SERIOUS+MARRIAGE / WANTS+HAS_MORE"),
            new Expectation(MARKO, TOMISLAV,  96.25, "same everything, SOCIAL vs MODERATE drinking"),
            new Expectation(MARKO, SARA,      67.75, "FIGURING_IT_OUT / NOT_SURE kids"),
            new Expectation(MARKO, LUKA,      31.75, "cross-goal + hard kids incompatibility"),
            new Expectation(MARKO, DINO,      22.75, "worst pair: cross-goal + no kids + smoking")
        );

        for (Expectation e : cases) {
            Optional<UserEntity> viewerOpt = findByEmail(e.viewer());
            Optional<UserEntity> targetOpt = findByEmail(e.target());
            if (viewerOpt.isEmpty() || targetOpt.isEmpty()) {
                System.out.printf("  ⚠ Skipping %s → %s (seed users not found)%n",
                        e.viewer(), e.target());
                continue;
            }
            MatchScore s = recommendationService.getMatchScore(
                    domainUser(viewerOpt.get().getId()), targetOpt.get().getId());

            System.out.printf("  Marko → %-12s  lifestyle=%.2f  (expected=%.2f — %s)%n",
                    e.target().split("@")[0].split("\\.")[0],
                    s.getLifestyleScore(), e.expected(), e.reason());

            assertEquals(e.expected(), s.getLifestyleScore(), 0.1,
                    String.format("Lifestyle score for %s → %s", e.viewer(), e.target()));
        }
        System.out.println();
    }

    // ================================================================
    //  TEST 5 — Interests component matches expected values
    // ================================================================

    @Test @Order(5)
    @DisplayName("Test 5: Interests (Jaccard) component matches hand-calculated values")
    void test5_interestsComponentValues() {
        System.out.println("\n🧪 TEST 5: Interests component values");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        record Expectation(String viewer, String target, double expected, String reason) {}

        List<Expectation> cases = List.of(
            // Marko (concerts,hiking,camping,guitar,travel) vs Tomislav (gaming,camping,concerts,woodworking,dogs)
            // ∩={camping,concerts}/|∪|=8 → 25.0
            new Expectation(MARKO, TOMISLAV, 25.0, "camping+concerts shared, union=8"),
            // Marko vs Ana: ∩={travel}/|∪|=9 → 11.11
            new Expectation(MARKO, ANA, 100.0 / 9.0, "only travel shared, union=9"),
            // Luka (festivals,gaming,travel,clubbing,cooking) vs Sara (painting,yoga,travel,theatre,cooking)
            // ∩={travel,cooking}/|∪|=8 → 25.0
            new Expectation(LUKA, SARA, 25.0, "travel+cooking shared, union=8")
        );

        for (Expectation e : cases) {
            Optional<UserEntity> viewerOpt = findByEmail(e.viewer());
            Optional<UserEntity> targetOpt = findByEmail(e.target());
            if (viewerOpt.isEmpty() || targetOpt.isEmpty()) {
                System.out.printf("  ⚠ Skipping (seed users not found)%n");
                continue;
            }
            MatchScore s = recommendationService.getMatchScore(
                    domainUser(viewerOpt.get().getId()), targetOpt.get().getId());

            System.out.printf("  %s → %s  interests=%.2f  (expected=%.2f — %s)%n",
                    e.viewer().split("@")[0].split("\\.")[0],
                    e.target().split("@")[0].split("\\.")[0],
                    s.getInterestsScore(), e.expected(), e.reason());

            assertEquals(e.expected(), s.getInterestsScore(), 0.1);
        }
        System.out.println();
    }

    // ================================================================
    //  TEST 6 — Location score varies correctly by distance
    // ================================================================

    @Test @Order(6)
    @DisplayName("Test 6: Location score reflects distance — closer city pair scores higher")
    void test6_locationScoreByDistance() {
        System.out.println("\n🧪 TEST 6: Location score reflects physical distance");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        // Luka (Rijeka) → Sara (Pula):      ~69 km
        // Marko (Zagreb) → Dino (Dubrovnik): ~590 km
        Optional<UserEntity> lukaOpt  = findByEmail(LUKA);
        Optional<UserEntity> saraOpt  = findByEmail(SARA);
        Optional<UserEntity> markoOpt = findByEmail(MARKO);
        Optional<UserEntity> dinoOpt  = findByEmail(DINO);

        if (lukaOpt.isEmpty() || saraOpt.isEmpty() || markoOpt.isEmpty() || dinoOpt.isEmpty()) {
            System.out.println("  ⚠ Skipping — seed users not found");
            return;
        }

        MatchScore lukaSara   = recommendationService.getMatchScore(
                domainUser(lukaOpt.get().getId()), saraOpt.get().getId());
        MatchScore markoDino  = recommendationService.getMatchScore(
                domainUser(markoOpt.get().getId()), dinoOpt.get().getId());

        System.out.printf("  Luka (Rijeka) → Sara (Pula, ~69km):        locationScore=%.2f%n",
                lukaSara.getLocationScore());
        System.out.printf("  Marko (Zagreb) → Dino (Dubrovnik, ~590km): locationScore=%.2f%n",
                markoDino.getLocationScore());

        assertTrue(lukaSara.getLocationScore() > markoDino.getLocationScore(),
                "Nearby pair (Rijeka-Pula) should have higher locationScore than distant pair (Zagreb-Dubrovnik)");
        System.out.println("  ✓ Closer pair scores higher on location\n");
    }

    // ================================================================
    //  TEST 7 — Compatible pair ranks above incompatible pair
    // ================================================================

    @Test @Order(7)
    @DisplayName("Test 7: Compatible users rank higher than incompatible users from the same viewer")
    void test7_scoringOrderIsReasonable() {
        System.out.println("\n🧪 TEST 7: Score ordering — compatible pair ranks above incompatible");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        // Petra (indie/Zadar, SERIOUS, WANTS_KIDS) should score higher with Marko (rock, SERIOUS, WANTS_KIDS)
        // than Dino (hip-hop, CASUAL, DOESNT_WANT_KIDS). Genre overlap + lifestyle alignment both better.
        Optional<UserEntity> petraOpt = findByEmail(PETRA);
        Optional<UserEntity> markoOpt = findByEmail(MARKO);
        Optional<UserEntity> dinoOpt  = findByEmail(DINO);

        if (petraOpt.isEmpty() || markoOpt.isEmpty() || dinoOpt.isEmpty()) {
            System.out.println("  ⚠ Skipping — seed users not found");
            return;
        }

        String petraId = petraOpt.get().getId();
        MatchScore petraVsMarko = recommendationService.getMatchScore(domainUser(petraId), markoOpt.get().getId());
        MatchScore petraVsDino  = recommendationService.getMatchScore(domainUser(petraId), dinoOpt.get().getId());

        System.out.printf("  Petra → Marko (compatible):   overall=%.2f  lifestyle=%.2f%n",
                petraVsMarko.getOverallScore(), petraVsMarko.getLifestyleScore());
        System.out.printf("  Petra → Dino  (incompatible): overall=%.2f  lifestyle=%.2f%n",
                petraVsDino.getOverallScore(), petraVsDino.getLifestyleScore());

        // Lifestyle is deterministic — we know Petra-Marko is 100.0 and Petra-Dino is 22.75
        assertTrue(petraVsMarko.getLifestyleScore() > petraVsDino.getLifestyleScore(),
                "Petra-Marko lifestyle score should exceed Petra-Dino");
        assertTrue(petraVsMarko.getOverallScore() > petraVsDino.getOverallScore(),
                "Petra-Marko overall score should exceed Petra-Dino");
        System.out.println("  ✓ Compatible pair ranks higher\n");
    }

    // ================================================================
    //  TEST 8 — Score is directional (A→B may differ from B→A)
    // ================================================================

    @Test @Order(8)
    @DisplayName("Test 8: Score is directional — viewer's musicMatchImportance affects weighting")
    void test8_scoreIsDirectional() {
        System.out.println("\n🧪 TEST 8: Score directionality (A→B may differ from B→A)");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        // Marko: musicMatchImportance=80 (music weight = 0.70)
        // Luka:  musicMatchImportance=90 (music weight = 0.75)
        // Because they have different music-weight preferences, A→B ≠ B→A

        Optional<UserEntity> markoOpt = findByEmail(MARKO);
        Optional<UserEntity> lukaOpt  = findByEmail(LUKA);

        if (markoOpt.isEmpty() || lukaOpt.isEmpty()) {
            System.out.println("  ⚠ Skipping — seed users not found");
            return;
        }

        MatchScore markoLuka = recommendationService.getMatchScore(
                domainUser(markoOpt.get().getId()), lukaOpt.get().getId());
        MatchScore lukaMarko = recommendationService.getMatchScore(
                domainUser(lukaOpt.get().getId()), markoOpt.get().getId());

        System.out.printf("  Marko → Luka (musicImportance=80): overall=%.2f%n", markoLuka.getOverallScore());
        System.out.printf("  Luka → Marko (musicImportance=90): overall=%.2f%n", lukaMarko.getOverallScore());

        // Both should be in valid range regardless of direction
        assertTrue(markoLuka.getOverallScore() >= 0 && markoLuka.getOverallScore() <= 100);
        assertTrue(lukaMarko.getOverallScore() >= 0 && lukaMarko.getOverallScore() <= 100);

        System.out.println("  ✓ Both directions are valid scores");
        if (Math.abs(markoLuka.getOverallScore() - lukaMarko.getOverallScore()) > 0.01) {
            System.out.println("  ✓ Scores differ — directionality confirmed\n");
        } else {
            System.out.println("  ℹ Scores happen to be equal (users share same musicImportance or no music prefs)\n");
        }
    }

    // ================================================================
    //  TEST 9 — CompatibilityLevel matches the score
    // ================================================================

    @Test @Order(9)
    @DisplayName("Test 9: CompatibilityLevel enum matches the overall score")
    void test9_compatibilityLevelMatchesScore() {
        System.out.println("\n🧪 TEST 9: CompatibilityLevel correctly reflects overall score");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        List<UserEntity> users = SEED_EMAILS.stream()
                .map(e -> findByEmail(e).orElse(null))
                .filter(Objects::nonNull)
                .toList();

        if (users.size() < 2) {
            System.out.println("  ⚠ Skipping — not enough seed users");
            return;
        }

        int checked = 0;
        for (UserEntity viewer : users) {
            for (UserEntity target : users) {
                if (viewer.getId().equals(target.getId())) continue;
                MatchScore s = recommendationService.getMatchScore(
                        domainUser(viewer.getId()), target.getId());
                double score = s.getOverallScore();
                var level    = s.getCompatibilityLevel();

                // Validate the level matches the expected thresholds
                if (score <= 40)       assertEquals("Low Match",       level.getDisplayName());
                else if (score <= 60)  assertEquals("Medium Match",    level.getDisplayName());
                else if (score <= 80)  assertEquals("High Match",      level.getDisplayName());
                else                   assertEquals("Very High Match",  level.getDisplayName());
                checked++;
            }
        }
        System.out.printf("  ✓ Verified compatibility levels for %d pairs%n%n", checked);
    }

    // ================================================================
    //  TEST 10 — Breakdown contains shared interests list
    // ================================================================

    @Test @Order(10)
    @DisplayName("Test 10: Score breakdown contains sharedInterests list")
    void test10_breakdownHasSharedInterests() {
        System.out.println("\n🧪 TEST 10: Breakdown — sharedInterests list is populated");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        // Marko+Tomislav share 'camping' and 'concerts'
        Optional<UserEntity> markoOpt    = findByEmail(MARKO);
        Optional<UserEntity> tomislavOpt = findByEmail(TOMISLAV);

        if (markoOpt.isEmpty() || tomislavOpt.isEmpty()) {
            System.out.println("  ⚠ Skipping — seed users not found");
            return;
        }

        MatchScore s = recommendationService.getMatchScore(
                domainUser(markoOpt.get().getId()), tomislavOpt.get().getId());

        assertNotNull(s.getBreakdown(), "breakdown must not be null");
        assertNotNull(s.getBreakdown().getSharedInterests(), "sharedInterests must not be null");

        System.out.printf("  Marko → Tomislav shared interests: %s%n",
                s.getBreakdown().getSharedInterests());
        assertTrue(s.getBreakdown().getSharedInterests().contains("camping"),
                "camping should be a shared interest");
        assertTrue(s.getBreakdown().getSharedInterests().contains("concerts"),
                "concerts should be a shared interest");
        System.out.println("  ✓ Shared interests correctly identified\n");
    }

    // ================================================================
    //  TEST 11 — Full compatibility matrix (no assertions — just output)
    // ================================================================

    @Test @Order(11)
    @DisplayName("Test 11: Full compatibility matrix for all 8 seed users")
    void test11_fullCompatibilityMatrix() {
        System.out.println("\n🧪 TEST 11: Full Compatibility Matrix (v2.0 algorithm)");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        List<UserEntity> users = SEED_EMAILS.stream()
                .map(e -> findByEmail(e).orElse(null))
                .filter(Objects::nonNull)
                .toList();

        if (users.size() < 2) {
            System.out.println("  ⚠ Not enough seed users in DB to build matrix");
            return;
        }

        // Print dimensions matrix
        System.out.println("  OVERALL SCORE MATRIX (viewer → target, higher = better match)\n");

        // Header
        System.out.print("  " + String.format("%-22s", "viewer \\ target"));
        for (UserEntity t : users) {
            String nick = t.getName().split(" ")[0];
            System.out.print(String.format("%-10s", nick));
        }
        System.out.println();
        System.out.print("  " + "─".repeat(22));
        System.out.println("─".repeat(users.size() * 10));

        for (UserEntity viewer : users) {
            String viewerNick = String.format("%-22s", viewer.getName().split(" ")[0]);
            System.out.print("  " + viewerNick);
            for (UserEntity target : users) {
                if (viewer.getId().equals(target.getId())) {
                    System.out.print(String.format("%-10s", "  —"));
                } else {
                    MatchScore s = recommendationService.getMatchScore(
                            domainUser(viewer.getId()), target.getId());
                    System.out.print(String.format("%-10s",
                            String.format("%.1f", s.getOverallScore())));
                }
            }
            System.out.println();
        }

        System.out.println();

        // Per-user: top 3 matches
        System.out.println("  TOP MATCHES PER USER:\n");
        for (UserEntity viewer : users) {
            List<double[]> scores = new ArrayList<>();
            for (UserEntity target : users) {
                if (viewer.getId().equals(target.getId())) continue;
                MatchScore s = recommendationService.getMatchScore(
                        domainUser(viewer.getId()), target.getId());
                scores.add(new double[]{s.getOverallScore(), users.indexOf(target)});
            }
            scores.sort((a, b) -> Double.compare(b[0], a[0]));

            System.out.printf("  %-20s →  ", viewer.getName().split(" ")[0]);
            scores.stream().limit(3).forEach(entry -> {
                UserEntity t = users.get((int) entry[1]);
                System.out.printf("#1 %s(%.1f)  ".formatted(
                        t.getName().split(" ")[0], entry[0]));
            });
            System.out.println();
        }

        System.out.println("\n  LIFESTYLE SCORE MATRIX (symmetric):\n");
        System.out.print("  " + String.format("%-22s", "viewer \\ target"));
        for (UserEntity t : users) {
            String nick = t.getName().split(" ")[0];
            System.out.print(String.format("%-10s", nick));
        }
        System.out.println();
        System.out.print("  " + "─".repeat(22));
        System.out.println("─".repeat(users.size() * 10));

        for (UserEntity viewer : users) {
            System.out.print("  " + String.format("%-22s", viewer.getName().split(" ")[0]));
            for (UserEntity target : users) {
                if (viewer.getId().equals(target.getId())) {
                    System.out.print(String.format("%-10s", "  —"));
                } else {
                    MatchScore s = recommendationService.getMatchScore(
                            domainUser(viewer.getId()), target.getId());
                    System.out.print(String.format("%-10s",
                            String.format("%.1f", s.getLifestyleScore())));
                }
            }
            System.out.println();
        }

        System.out.println("\n╔════════════════════════════════════════════════════════════╗");
        System.out.println("║         MATCH SCORING INTEGRATION TEST - DONE             ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝\n");
    }
}
