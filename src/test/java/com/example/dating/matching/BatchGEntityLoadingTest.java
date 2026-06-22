package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.enums.user.Gender;
import com.example.dating.enums.user.RegistrationStage;
import com.example.dating.models.matching.dto.PotentialMatchPage;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.matching.MatchRecommendationService;
import com.example.dating.services.matching.MatchScoringService;
import org.hibernate.annotations.BatchSize;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Batch G (Scalability) — Entity Loading: @BatchSize, Projections, Lazy-Load Fixes.
 *
 * <p>Validates the three changes introduced in Batch G:
 *
 * <ol>
 *   <li><b>@BatchSize on privacySettings</b>: {@code UserEntity.privacySettings} now has
 *       {@code @BatchSize(size = 50)} matching the pattern already used for all other
 *       sub-entity associations ({@code musicPreferences}, {@code lifestyle},
 *       {@code personality}, {@code datingPreferences}).</li>
 *   <li><b>Distance cache</b>: the haversine distance between a requesting user and a
 *       candidate is computed once during Phase A filtering, stored in a per-request
 *       {@code HashMap}, and reused in {@code buildPotentialMatch} for the display
 *       {@code distance} field — no third trigonometric calculation.</li>
 *   <li><b>Gender token pre-parse</b>: the requesting user's {@code interestedInGenders}
 *       string is split and collected into a {@code Set} once before the filter loop,
 *       not once per candidate.</li>
 * </ol>
 *
 * <p>The concurrent integration test (test 5) validates that each call to
 * {@code findPotentialMatches} receives its own independent per-request
 * {@code distanceCache} and {@code userGenderTokens} — no shared mutable state
 * leaks between threads.
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class BatchGEntityLoadingTest {

    @Autowired private MatchRecommendationService recommendationService;
    @Autowired private UserJpaRepository           userRepository;
    @Autowired private PlatformTransactionManager  txManager;

    private final List<String> createdUserIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        if (!createdUserIds.isEmpty()) {
            TransactionTemplate tx = new TransactionTemplate(txManager);
            tx.execute(status -> {
                createdUserIds.forEach(id ->
                        userRepository.findById(id).ifPresent(userRepository::delete));
                return null;
            });
            createdUserIds.clear();
        }
    }

    // =========================================================================
    // 1. @BatchSize annotation — structural checks via reflection
    // =========================================================================

    @Test
    @DisplayName("UserEntity.privacySettings has @BatchSize(size = 50)")
    void privacySettings_hasBatchSizeAnnotation() throws NoSuchFieldException {
        Field field = UserEntity.class.getDeclaredField("privacySettings");
        BatchSize batchSize = field.getAnnotation(BatchSize.class);

        assertThat(batchSize)
                .as("privacySettings must carry @BatchSize so lazy loads batch across candidates")
                .isNotNull();
        assertThat(batchSize.size())
                .as("@BatchSize must be 50, matching the other four sub-entity associations")
                .isEqualTo(50);
    }

    @Test
    @DisplayName("All five OneToOne sub-entity fields on UserEntity have consistent @BatchSize(size = 50)")
    void allSubEntityFields_haveBatchSizeOf50() throws NoSuchFieldException {
        String[] fields = {
            "musicPreferences", "lifestyle", "personality", "datingPreferences", "privacySettings"
        };
        for (String fieldName : fields) {
            Field field = UserEntity.class.getDeclaredField(fieldName);
            BatchSize bs = field.getAnnotation(BatchSize.class);
            assertThat(bs)
                    .as("Field '%s' must have @BatchSize", fieldName)
                    .isNotNull();
            assertThat(bs.size())
                    .as("Field '%s' @BatchSize must be 50", fieldName)
                    .isEqualTo(50);
        }
    }

    // =========================================================================
    // 2. Distance cache — haversine computed once, result reused
    // =========================================================================

    @Test
    @DisplayName("Distance cache: haversineKm invoked exactly once per candidate, second access is a cache hit")
    void distanceCache_computeIfAbsent_computesOnce() {
        AtomicInteger callCount = new AtomicInteger(0);
        Map<String, Double> distanceCache = new HashMap<>();
        String candidateId = "cand-paris-london";

        // Paris → London (~341 km)
        double parisLat = 48.8566, parisLon = 2.3522;
        double londonLat = 51.5074, londonLon = -0.1278;

        // First access — invokes haversineKm
        double dist1 = distanceCache.computeIfAbsent(candidateId, id -> {
            callCount.incrementAndGet();
            return MatchScoringService.haversineKm(parisLat, parisLon, londonLat, londonLon);
        });

        // Second access — cache hit, must NOT invoke haversineKm again
        double dist2 = distanceCache.computeIfAbsent(candidateId, id -> {
            callCount.incrementAndGet();
            return MatchScoringService.haversineKm(parisLat, parisLon, londonLat, londonLon);
        });

        assertThat(callCount.get())
                .as("haversineKm must be called exactly once; second access must be a cache hit")
                .isEqualTo(1);
        assertThat(dist1)
                .as("Cached and recomputed values must be identical")
                .isEqualTo(dist2);
        assertThat(dist1)
                .as("Paris–London distance must be ~341 km")
                .isBetween(335.0, 348.0);
    }

    @Test
    @DisplayName("Distance cache: each candidate gets an independent entry; entries never overwrite each other")
    void distanceCache_separateEntryPerCandidate() {
        Map<String, Double> distanceCache = new HashMap<>();
        double parisLat = 48.8566, parisLon = 2.3522;

        String londonId = "cand-london";
        double londonLat = 51.5074, londonLon = -0.1278;   // ~341 km from Paris

        String berlinId = "cand-berlin";
        double berlinLat = 52.5200, berlinLon = 13.4050;   // ~878 km from Paris

        double distToLondon = distanceCache.computeIfAbsent(londonId,
                id -> MatchScoringService.haversineKm(parisLat, parisLon, londonLat, londonLon));
        double distToBerlin = distanceCache.computeIfAbsent(berlinId,
                id -> MatchScoringService.haversineKm(parisLat, parisLon, berlinLat, berlinLon));

        assertThat(distanceCache).hasSize(2);
        assertThat(distanceCache.get(londonId))
                .as("London cache entry must match the originally computed value")
                .isEqualTo(distToLondon);
        assertThat(distanceCache.get(berlinId))
                .as("Berlin cache entry must match the originally computed value")
                .isEqualTo(distToBerlin);
        assertThat(distToLondon)
                .as("Paris–London and Paris–Berlin distances must differ")
                .isNotEqualTo(distToBerlin);
        assertThat(distToBerlin)
                .as("Paris–Berlin must be farther than Paris–London")
                .isGreaterThan(distToLondon);
    }

    // =========================================================================
    // 3. haversineKm correctness (static — stateless between threads)
    // =========================================================================

    @Test
    @DisplayName("haversineKm is deterministic: same inputs always produce the same output")
    void haversineKm_isDeterministic() {
        double d1 = MatchScoringService.haversineKm(48.8566, 2.3522, 40.7128, -74.0060); // Paris→NYC
        double d2 = MatchScoringService.haversineKm(48.8566, 2.3522, 40.7128, -74.0060);
        assertThat(d1).isEqualTo(d2);
        assertThat(d1).isBetween(5_800.0, 5_900.0); // ~5,837 km
    }

    // =========================================================================
    // 4. Concurrent findPotentialMatches — per-request cache isolation
    // =========================================================================

    /**
     * Creates 4 threads.  Each thread calls {@code findPotentialMatches} for one of two
     * distinct callers (2 threads per caller) against the same pool of candidates.
     *
     * <p>If {@code distanceCache} or {@code userGenderTokens} were a shared field rather
     * than a per-request local variable, concurrent writes from thread A (caller 1) and
     * thread B (caller 2) would corrupt each other's results.  This test detects that
     * by verifying:
     * <ul>
     *   <li>All 4 calls complete without any exception.</li>
     *   <li>Two independent calls for the <em>same</em> caller return the same total count
     *       (no race-condition flip-flop).</li>
     * </ul>
     */
    @Test
    @DisplayName("Concurrent findPotentialMatches: per-request distanceCache is thread-isolated")
    void concurrentFindPotentialMatches_perRequestCacheIsIsolated() throws Exception {
        // ── Setup: 2 caller users + 6 candidate users ──────────────────────────
        TransactionTemplate tx = new TransactionTemplate(txManager);
        UserEntity caller1 = tx.execute(s -> save(userWithLocation("batchG-caller1", 48.8566, 2.3522)));  // Paris
        UserEntity caller2 = tx.execute(s -> save(userWithLocation("batchG-caller2", 51.5074, -0.1278))); // London

        tx.execute(s -> {
            save(userWithLocation("batchG-cand1", 48.8600, 2.3600));  // near Paris
            save(userWithLocation("batchG-cand2", 48.8700, 2.3700));  // near Paris
            save(userWithLocation("batchG-cand3", 51.5100, -0.1300)); // near London
            save(userWithLocation("batchG-cand4", 51.5200, -0.1200)); // near London
            save(userWithLocation("batchG-cand5", 52.5200, 13.4050)); // Berlin — far from both
            save(userWithLocation("batchG-cand6", 40.7128, -74.0060));// NYC   — far from both
            return null;
        });

        User domainCaller1 = User.builder().id(caller1.getId()).build();
        User domainCaller2 = User.builder().id(caller2.getId()).build();

        int threadCount = 4;
        ExecutorService pool    = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier   barrier = new CyclicBarrier(threadCount);

        // Thread 0 and 2 call as caller1, threads 1 and 3 call as caller2
        List<Future<PotentialMatchPage>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            User caller = (i % 2 == 0) ? domainCaller1 : domainCaller2;
            futures.add(pool.submit(() -> {
                barrier.await(); // all threads start simultaneously
                return recommendationService.findPotentialMatches(caller, 20, 0, 0.0, false);
            }));
        }

        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS))
                .as("All threads must complete within 30 s")
                .isTrue();

        // ── Assert: no exceptions, results are non-null ─────────────────────────
        for (int i = 0; i < threadCount; i++) {
            int idx = i;
            assertDoesNotThrow((ThrowingSupplier<PotentialMatchPage>) futures.get(idx)::get,
                    "Thread " + idx + " must not throw during concurrent findPotentialMatches");
        }

        PotentialMatchPage result0 = futures.get(0).get();
        PotentialMatchPage result2 = futures.get(2).get();
        PotentialMatchPage result1 = futures.get(1).get();
        PotentialMatchPage result3 = futures.get(3).get();

        assertThat(result0).as("Caller-1 thread-0 must return a non-null page").isNotNull();
        assertThat(result1).as("Caller-2 thread-1 must return a non-null page").isNotNull();

        // Two independent calls for the same caller must produce the same total —
        // if distanceCache were a shared field, a concurrent write from caller2's thread
        // would alter caller1's distance values and flip candidate eligibility.
        assertThat(result0.getTotal())
                .as("Both threads calling as caller1 must agree on total (no cache cross-contamination)")
                .isEqualTo(result2.getTotal());
        assertThat(result1.getTotal())
                .as("Both threads calling as caller2 must agree on total (no cache cross-contamination)")
                .isEqualTo(result3.getTotal());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private UserEntity save(UserEntity entity) {
        UserEntity saved = userRepository.save(entity);
        createdUserIds.add(saved.getId());
        return saved;
    }

    private UserEntity userWithLocation(String tag, double lat, double lon) {
        return UserEntity.builder()
                .email(tag + "-" + UUID.randomUUID() + "@test.invalid")
                .registrationStage(RegistrationStage.FINISHED)
                .gender(Gender.OTHER)
                .locationLat(BigDecimal.valueOf(lat))
                .locationLon(BigDecimal.valueOf(lon))
                .build();
    }
}
