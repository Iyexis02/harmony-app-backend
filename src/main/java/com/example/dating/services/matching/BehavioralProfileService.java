package com.example.dating.services.matching;

import com.example.dating.exceptions.UserNotFoundException;
import com.example.dating.events.SwipeRecordedEvent;
import com.example.dating.models.matching.dao.UserBehavioralProfile;
import com.example.dating.models.matching.dao.UserGenrePreference;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.repositories.UserBehavioralProfileRepository;
import com.example.dating.repositories.UserGenrePreferenceRepository;
import com.example.dating.repositories.UserJpaRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maintains per-user behavioural profiles that are updated after each swipe.
 * Learns which genre patterns, ages and relationship goals the user tends to like.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BehavioralProfileService {

    private final UserBehavioralProfileRepository behavioralProfileRepository;
    private final UserGenrePreferenceRepository genrePreferenceRepository;
    private final UserJpaRepository userRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationContext applicationContext;
    private final BehavioralScoreCalculator behavioralScoreCalculator;

    private static final TypeReference<Map<String, Double>> DOUBLE_MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, Integer>> INT_MAP_TYPE   = new TypeReference<>() {};

    /** EMA smoothing factor for effectiveScoreThreshold updates. */
    private static final double EMA_ALPHA = 0.15;

    /** Days of inactivity before confidence decay kicks in. */
    private static final int INACTIVITY_GRACE_DAYS = 14;

    /** Daily decay factor for confidence after grace period. */
    private static final double INACTIVITY_DECAY_RATE = 0.98;

    /** Minimum genre weight before removal. */
    private static final double GENRE_WEIGHT_FLOOR = 0.01;

    /** Decay multiplier for genres NOT present in a liked user's profile. */
    private static final double GENRE_ABSENT_DECAY = 0.98;

    /**
     * Receives a {@link SwipeRecordedEvent} and delegates to {@link #updateAfterSwipe}.
     *
     * <p>{@code @TransactionalEventListener(phase = AFTER_COMMIT)} guarantees this method
     * is called only after the swipe transaction has committed.  If the transaction rolls
     * back (e.g. due to a constraint violation or an unhandled exception in
     * {@code SwipeService.recordSwipe}), the event is discarded and the behavioral profile
     * is never mutated — preventing phantom preference updates for unperisted swipes.
     *
     * <p>{@code @Async} dispatches the work to the shared task executor so the HTTP
     * response is not held while genre weights and EMA values are recomputed.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onSwipeRecorded(SwipeRecordedEvent event) {
        updateAfterSwipe(event.swiperId(), event.swipedUserId(), event.action(), event.matchScore());
    }

    /**
     * Update the swiper's behavioural profile based on a single swipe action.
     *
     * <p>This method is intentionally <em>not</em> {@code @Async} — async dispatch is
     * handled by the {@link #onSwipeRecorded} listener so that the update is guaranteed
     * to run only after the outer swipe transaction commits.
     *
     * <p>Retry orchestrator: attempts up to 3 times to handle concurrent updates on the
     * same profile.  Each attempt delegates to {@link #doUpdateAfterSwipe} which runs in
     * its own {@code @Transactional} boundary.  If two threads read the same version of
     * the {@code UserBehavioralProfile} row and both try to commit, the second commit
     * throws {@link OptimisticLockingFailureException}; this method catches that, re-reads
     * the fresh row, and retries the mutation.  Behavioural updates are non-critical: if
     * all retries fail, the update is dropped and logged at WARN level.
     *
     * @param swiperId     ID of the user who swiped
     * @param swipedUserId ID of the user who was swiped on
     * @param action       "like", "super_like", or "pass"
     * @param matchScore   overall match score at the time of the swipe (may be null)
     */
    public void updateAfterSwipe(String swiperId, String swipedUserId, String action, Double matchScore) {
        int maxAttempts = 3;
        BehavioralProfileService proxy = applicationContext.getBean(BehavioralProfileService.class);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                proxy.doUpdateAfterSwipe(swiperId, swipedUserId, action, matchScore);
                return;
            } catch (OptimisticLockingFailureException e) {
                if (attempt == maxAttempts) {
                    log.warn("Behavioral profile update failed after {} attempts for swiper {} " +
                            "(optimistic lock conflict) — dropping update", maxAttempts, swiperId);
                    return;
                }
                log.debug("Optimistic lock conflict on behavioral profile for swiper {} " +
                        "(attempt {}/{}), retrying...", swiperId, attempt, maxAttempts);
                try {
                    Thread.sleep(20L * (1 << attempt)); // 40 ms, 80 ms
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } catch (Exception e) {
                // Any other non-transient exception: log and abandon — update is non-critical
                log.warn("Failed to update behavioural profile for swiper {}: {}", swiperId, e.getMessage());
                return;
            }
        }
    }

    /**
     * Single transactional attempt to update the behavioural profile.
     *
     * <p>Must be called through the Spring proxy (e.g. via {@code ApplicationContext.getBean})
     * so that {@code @Transactional} AOP advice is active.  Direct {@code this.doUpdateAfterSwipe()}
     * calls would bypass the proxy and lose transaction semantics.
     *
     * <p>{@code @Transactional} with default propagation ({@code REQUIRED}): when called from
     * {@link #updateAfterSwipe} (which has no active transaction), a new transaction is started
     * for each attempt, ensuring each retry reads a fresh snapshot of the profile row.
     */
    @Transactional
    public void doUpdateAfterSwipe(String swiperId, String swipedUserId, String action, Double matchScore) {
        UserBehavioralProfile profile = behavioralProfileRepository.findByUserId(swiperId)
                .orElseGet(() -> createEmptyProfile(swiperId));

        // Apply inactivity confidence decay before processing
        applyInactivityDecay(profile);

        if ("pass".equalsIgnoreCase(action)) {
            profile.setTotalPasses(profile.getTotalPasses() + 1);
            profile.setConfidenceLevel(computeConfidence(profile.getTotalLikes()));
            profile.setLastUpdatedAt(LocalDateTime.now());
            profile = behavioralProfileRepository.save(profile);
            behavioralScoreCalculator.invalidateCache(profile.getId());
            // Bump UserEntity.updatedAt so cached match scores are correctly invalidated.
            userRepository.touchUpdatedAt(swiperId);
            return;
        }

        if (!"like".equalsIgnoreCase(action) && !"super_like".equalsIgnoreCase(action)) {
            return; // ignore unknown actions (block is handled separately)
        }

        // --- LIKE / SUPER_LIKE path ---
        UserEntity likedUser = userRepository.findById(swipedUserId).orElse(null);
        if (likedUser == null) {
            log.warn("BehavioralProfileService: liked user {} not found", swipedUserId);
            return;
        }

        int n = profile.getTotalLikes(); // current count before this like

        // 1. Update learned genre weights (EMA with decay for absent genres)
        // JOIN FETCH ensures genre name is accessible inside this transaction and after it returns
        List<UserGenrePreference> likedGenres =
                genrePreferenceRepository.findByUserIdWithGenreOrderByWeightDesc(swipedUserId);

        Map<String, Double> genreMap = deserializeDoubleMap(profile.getLearnedGenreWeights());

        // Collect genres present in liked user's profile
        java.util.Set<String> presentGenres = new java.util.HashSet<>();
        double alpha = Math.max(0.20, 1.0 / (n + 1)); // high early, converges to 0.20

        for (UserGenrePreference pref : likedGenres) {
            String genre = pref.getGenre().getName();
            presentGenres.add(genre);
            double current = genreMap.getOrDefault(genre, 0.0);
            genreMap.put(genre, current * (1 - alpha) + pref.getWeight() * alpha);
        }

        // Gentle decay for genres NOT present in liked user's profile
        genreMap.entrySet().removeIf(entry -> {
            if (!presentGenres.contains(entry.getKey())) {
                entry.setValue(entry.getValue() * GENRE_ABSENT_DECAY);
                return entry.getValue() < GENRE_WEIGHT_FLOOR;
            }
            return false;
        });

        profile.setLearnedGenreWeights(serializeToJson(genreMap));

        // 2. Update average liked age
        if (likedUser.getDateOfBirth() != null) {
            int likedAge = Period.between(likedUser.getDateOfBirth(), LocalDate.now()).getYears();
            double currentAvgAge = profile.getAvgLikedAge() != null ? profile.getAvgLikedAge() : likedAge;
            profile.setAvgLikedAge((currentAvgAge * n + likedAge) / (n + 1));
        }

        // 3. Update relationship goal frequency map
        if (likedUser.getDatingPreferences() != null
                && likedUser.getDatingPreferences().getRelationshipGoal() != null) {
            String goal = likedUser.getDatingPreferences().getRelationshipGoal().name();
            Map<String, Integer> goalMap = deserializeIntMap(profile.getTopLikedRelationshipGoals());
            goalMap.merge(goal, 1, Integer::sum);
            profile.setTopLikedRelationshipGoals(serializeToJson(goalMap));
        }

        // 4. Update effective score threshold (EMA)
        if (matchScore != null) {
            double current = profile.getEffectiveScoreThreshold() != null
                    ? profile.getEffectiveScoreThreshold()
                    : matchScore;
            profile.setEffectiveScoreThreshold(current * (1 - EMA_ALPHA) + matchScore * EMA_ALPHA);
        }

        // 5. Increment likes and recompute confidence
        profile.setTotalLikes(n + 1);
        profile.setConfidenceLevel(computeConfidence(n + 1));
        profile.setLastUpdatedAt(LocalDateTime.now());

        profile = behavioralProfileRepository.save(profile);
        behavioralScoreCalculator.invalidateCache(profile.getId());
        // Bump UserEntity.updatedAt so cached match scores are correctly invalidated.
        userRepository.touchUpdatedAt(swiperId);
    }

    /**
     * Apply inactivity confidence decay if the user hasn't swiped in more than 14 days.
     * Reduces confidence by 2% per day beyond the grace period.
     */
    private void applyInactivityDecay(UserBehavioralProfile profile) {
        if (profile.getLastUpdatedAt() == null || profile.getConfidenceLevel() == null
                || profile.getConfidenceLevel() <= 0.0) {
            return;
        }

        long daysSinceLastSwipe = java.time.temporal.ChronoUnit.DAYS.between(
                profile.getLastUpdatedAt().toLocalDate(), LocalDate.now());

        if (daysSinceLastSwipe > INACTIVITY_GRACE_DAYS) {
            double decayFactor = Math.pow(INACTIVITY_DECAY_RATE, daysSinceLastSwipe - INACTIVITY_GRACE_DAYS);
            double decayed = profile.getConfidenceLevel() * decayFactor;
            profile.setConfidenceLevel(Math.max(0.0, decayed));
            log.debug("Applied inactivity decay: {} days since last swipe, confidence now {}",
                    daysSinceLastSwipe, profile.getConfidenceLevel());
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private UserBehavioralProfile createEmptyProfile(String userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        return UserBehavioralProfile.builder()
                .user(user)
                .totalLikes(0)
                .totalPasses(0)
                .confidenceLevel(0.0)
                .build();
    }

    private double computeConfidence(int totalLikes) {
        return Math.min(1.0, totalLikes / 50.0);
    }

    private Map<String, Double> deserializeDoubleMap(String json) {
        if (json == null || json.isBlank()) return new HashMap<>();
        try {
            return objectMapper.readValue(json, DOUBLE_MAP_TYPE);
        } catch (Exception e) {
            log.error("Failed to deserialize behavioral genre weights — aborting update to preserve existing data: {}", e.getMessage());
            throw new RuntimeException("Behavioral profile deserialization failed", e);
        }
    }

    private Map<String, Integer> deserializeIntMap(String json) {
        if (json == null || json.isBlank()) return new HashMap<>();
        try {
            return objectMapper.readValue(json, INT_MAP_TYPE);
        } catch (Exception e) {
            log.error("Failed to deserialize behavioral int map — aborting update to preserve existing data: {}", e.getMessage());
            throw new RuntimeException("Behavioral profile deserialization failed", e);
        }
    }

    private String serializeToJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.error("Failed to serialize behavioral data to JSON — preserving existing value: {}", e.getMessage());
            throw new RuntimeException("Behavioral profile serialization failed", e);
        }
    }
}
