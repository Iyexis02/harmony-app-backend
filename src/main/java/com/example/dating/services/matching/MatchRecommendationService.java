package com.example.dating.services.matching;

import com.example.dating.exceptions.UserNotFoundException;
import com.example.dating.enums.user.Gender;
import com.example.dating.enums.user.RegistrationStage;
import com.example.dating.mappers.UserMapper;
import com.example.dating.models.matching.dao.UserBehavioralProfile;
import com.example.dating.models.matching.dao.UserGenrePreference;
import com.example.dating.models.matching.dao.UserMatchScore;
import com.example.dating.models.matching.dto.MatchScore;
import com.example.dating.models.matching.dto.PotentialMatch;
import com.example.dating.models.matching.dto.PotentialMatchPage;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.dating.dao.UserDatingPreferences;
import com.example.dating.models.user.domain.User;
import com.example.dating.models.user.photos.dao.UserPhoto;
import com.example.dating.repositories.UserBehavioralProfileRepository;
import com.example.dating.repositories.UserGenrePreferenceRepository;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.repositories.UserMatchScoreRepository;
import com.example.dating.repositories.UserSwipeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Service for finding and recommending potential matches.
 *
 * <p><b>Batch C — Scoring Transaction Refactor:</b>
 * {@link #findPotentialMatches} is split into two phases:
 * <ol>
 *   <li><b>Phase A</b> — {@link #fetchCandidateData}: all DB reads (candidates, cached scores,
 *       genre preferences, behavioural profile) in a single short
 *       {@code @Transactional(readOnly = true)} that <em>releases the DB connection before
 *       scoring begins</em>.  All lazy associations are force-initialized here via
 *       {@code Hibernate.initialize()} while the session is still open.</li>
 *   <li><b>Phase B</b> — scoring loop in {@link #findPotentialMatches} outside any transaction.
 *       Each cache miss calls {@link MatchScoringService#persistScoreCache} which opens its own
 *       short write transaction per candidate and releases immediately after the upsert.</li>
 * </ol>
 *
 * <p>This eliminates the connection-pool deadlock where the outer {@code readOnly} transaction
 * held one connection while {@code calculateScore(REQUIRES_NEW)} needed a second.  Under
 * 15 concurrent requests the old design saturated a 30-connection pool; each request now
 * holds a connection only for the short fetch window.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MatchRecommendationService {

    private static final String ALGORITHM_VERSION = "v2.0";

    private final MatchScoringService matchScoringService;
    private final UserJpaRepository userRepository;
    private final UserSwipeRepository swipeRepository;
    private final UserMapper userMapper;
    private final UserBehavioralProfileRepository behavioralProfileRepository;
    private final UserMatchScoreRepository userMatchScoreRepository;
    private final UserGenrePreferenceRepository genrePreferenceRepository;
    private final GenrePrefetchContext genrePrefetchContext;

    /**
     * Candidate pool cache — keyed by userId, stores the ordered list of candidate IDs
     * returned by the last {@code ORDER BY RANDOM()} draw for that user.
     *
     * <p>Consecutive page requests reuse the same ordered pool, eliminating duplicates and
     * gaps between pages that would otherwise occur when each request re-runs the random
     * query.  On cache MISS the query reruns and a fresh random pool is stored.
     *
     * <p>TTL of 5 minutes balances freshness against pagination stability.  Newly swiped
     * or blocked users are excluded dynamically: {@link #fetchCandidateData} always filters
     * the cached pool against the current {@code allExcludedSet} before returning candidates,
     * so no explicit per-swipe invalidation is required.
     *
     * <p>Caffeine is thread-safe; concurrent requests for the same userId see a consistent
     * pool because {@link Cache#get} (compute-if-absent) is atomic.
     */
    private final Cache<String, List<String>> candidatePoolCache = Caffeine.newBuilder()
            .maximumSize(2_000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();

    /**
     * Self-injection via Spring proxy — required so that {@link #fetchCandidateData}'s
     * {@code @Transactional(readOnly = true)} is intercepted by CGLIB.
     * A direct {@code this.fetchCandidateData()} call would bypass the proxy and start
     * no transaction at all.
     */
    @Autowired
    @Lazy
    private MatchRecommendationService self;

    // -------------------------------------------------------------------------
    // Internal transfer object: all DB data needed for one scoring pass
    // -------------------------------------------------------------------------

    record CandidateData(
            UserEntity userEntity,
            List<UserEntity> candidates,
            Map<String, UserMatchScore> cachedScores,
            Map<String, List<UserGenrePreference>> genrePrefs,
            double effectiveMinScore,
            LocalDateTime staleAfter,
            Map<String, Double> distanceCache,
            Set<String> userGenderTokens
    ) {}

    /** Pairs a candidate entity with its already-computed or cache-read score. */
    private record ScoredCandidate(UserEntity entity, MatchScore score) {}

    // -------------------------------------------------------------------------
    // Phase A — short read-only transaction (releases connection before scoring)
    // -------------------------------------------------------------------------

    /**
     * Fetch and filter candidates, batch-load cached scores and genre preferences,
     * all within a single short read-only transaction.  The DB connection is released
     * when this method returns.
     *
     * <p>All lazy associations needed for Phase B scoring are force-initialized here
     * via {@link Hibernate#initialize} so they survive entity detachment after
     * the transaction commits.  {@code @BatchSize(50)} on each association means
     * Hibernate issues one IN-clause SELECT per 50 entities rather than N+1 queries.
     *
     * <p><b>Must be called via {@code self.fetchCandidateData()} (proxy), not
     * {@code this.fetchCandidateData()} (direct), so Spring intercepts
     * the {@code @Transactional} annotation.</b>
     */
    @Transactional(readOnly = true)
    public CandidateData fetchCandidateData(User user, double minScore, boolean excludeSwiped) {
        UserEntity userEntity = userRepository.findById(user.getId())
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        UserDatingPreferences prefs = userEntity.getDatingPreferences();

        List<String> excludedIds = excludeSwiped
                ? swipeRepository.findAllSwipedUserIds(user.getId(), PageRequest.of(0, 10_000))
                : new ArrayList<>();

        List<String> blockedByIds = swipeRepository.findBlockedByUserIds(user.getId(), PageRequest.of(0, 10_000));
        List<String> blockedIds   = swipeRepository.findBlockedUserIds(user.getId(), PageRequest.of(0, 10_000));
        Set<String> allExcludedSet = new HashSet<>(excludedIds);
        allExcludedSet.addAll(blockedByIds);
        allExcludedSet.addAll(blockedIds);

        // JPQL requires a non-empty IN list — use sentinel value when list is empty
        List<String> safeExcludedIds = allExcludedSet.isEmpty()
                ? List.of("__none__")
                : new ArrayList<>(allExcludedSet);

        // Compute age bounds as date-of-birth cutoffs for the DB query
        LocalDate minDob = (prefs != null && prefs.getMaxAge() != null)
                ? LocalDate.now().minusYears(prefs.getMaxAge()) : null;
        LocalDate maxDob = (prefs != null && prefs.getMinAge() != null)
                ? LocalDate.now().minusYears(prefs.getMinAge()) : null;

        // Pre-parse requesting user's gender preference once — amortises Set allocation across all candidates
        Set<String> userGenderTokens = parseGenderTokens(userEntity.getDatingPreferences());
        // Distance cache: populated during filtering, reused in buildPotentialMatch (avoids 3rd haversine call)
        Map<String, Double> distanceCache = new HashMap<>();

        // Candidate pool cache: reuse the same random draw order so consecutive page requests
        // see a stable, non-overlapping pool.  On cache HIT we skip the RANDOM() query and load
        // entities by ID instead — the ordering is preserved and newly-excluded users are removed
        // dynamically.  On cache MISS we run the query and cache the ordered ID list.
        List<String> cachedPoolIds = candidatePoolCache.getIfPresent(user.getId());
        List<UserEntity> candidates;
        if (cachedPoolIds != null) {
            // Cache HIT — reconstruct the pool, minus any IDs excluded since the draw.
            List<String> eligibleIds = cachedPoolIds.stream()
                    .filter(id -> !allExcludedSet.contains(id))
                    .collect(Collectors.toList());
            Map<String, UserEntity> byId = userRepository.findAllById(eligibleIds)
                    .stream()
                    .collect(Collectors.toMap(UserEntity::getId, Function.identity()));
            List<UserEntity> raw = eligibleIds.stream()
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            candidates = raw.stream()
                    .filter(c -> isGenderCompatibleFast(userGenderTokens, userEntity.getGender(), c))
                    .filter(c -> isWithinDistance(userEntity, c, distanceCache))
                    .collect(Collectors.toList());
            log.debug("Candidate pool cache HIT for user {}: {} eligible from {} cached IDs",
                    user.getId(), candidates.size(), cachedPoolIds.size());
        } else {
            // Cache MISS — run the randomised query and store the ordered ID list for subsequent pages.
            Page<UserEntity> candidatePage = userRepository.findCandidateUsers(
                    user.getId(), RegistrationStage.FINISHED, safeExcludedIds,
                    minDob, maxDob, PageRequest.of(0, 500));
            List<String> poolIds = candidatePage.getContent().stream()
                    .map(UserEntity::getId)
                    .collect(Collectors.toList());
            candidatePoolCache.put(user.getId(), poolIds);
            candidates = candidatePage.getContent().stream()
                    .filter(c -> isGenderCompatibleFast(userGenderTokens, userEntity.getGender(), c))
                    .filter(c -> isWithinDistance(userEntity, c, distanceCache))
                    .collect(Collectors.toList());
            log.debug("Candidate pool cache MISS for user {}: cached {} IDs, {} passed in-memory filters",
                    user.getId(), poolIds.size(), candidates.size());
        }

        log.info("Candidate pool returned {} candidates for user {}", candidates.size(), user.getId());

        // Determine effective min-score from behavioural profile (Phase 3 calibration)
        double effectiveMinScore = minScore;
        Optional<UserBehavioralProfile> behavioralOpt =
                behavioralProfileRepository.findByUserId(user.getId());
        if (behavioralOpt.isPresent()) {
            UserBehavioralProfile profile = behavioralOpt.get();
            if (profile.getConfidenceLevel() != null && profile.getConfidenceLevel() > 0.3
                    && profile.getEffectiveScoreThreshold() != null) {
                effectiveMinScore = profile.getEffectiveScoreThreshold() * 0.85;
                log.debug("Behavioural threshold active: {}", effectiveMinScore);
            }
        }

        // Batch-fetch all cached scores for this user before the scoring loop
        Map<String, UserMatchScore> cachedScores = userMatchScoreRepository
                .findAllByUserIdAndVersion(user.getId(), ALGORITHM_VERSION)
                .stream()
                .collect(Collectors.toMap(
                        ums -> ums.getMatchedUser().getId(),
                        Function.identity(),
                        (a, b) -> a));

        // Pre-fetch genre preferences for all candidates + current user in one query.
        // MatchScoreCalculator and BehavioralScoreCalculator read from GenrePrefetchContext
        // instead of issuing per-candidate queries — collapses ~1 500 SELECTs to one.
        List<String> prefetchIds = new ArrayList<>(candidates.size() + 1);
        candidates.forEach(c -> prefetchIds.add(c.getId()));
        prefetchIds.add(user.getId());

        Map<String, List<UserGenrePreference>> genrePrefs =
                genrePreferenceRepository.findByUserIdsWithGenre(prefetchIds)
                        .stream()
                        .collect(Collectors.groupingBy(p -> p.getUser().getId()));

        // Force-initialize all lazy associations while the Hibernate session is still open.
        // After this transaction commits, entities become detached. Any uninitialized proxy
        // accessed on a detached entity would throw LazyInitializationException in Phase B.
        // @BatchSize(50) on each association batches these into ~10 queries for 500 candidates
        // instead of 2 500 individual SELECTs.
        initializeLazyAssociations(userEntity);
        candidates.forEach(this::initializeLazyAssociations);

        return new CandidateData(
                userEntity, candidates, cachedScores, genrePrefs,
                effectiveMinScore, userEntity.getUpdatedAt(),
                distanceCache, userGenderTokens);
    }

    // -------------------------------------------------------------------------
    // Phase B — scoring outside any transaction
    // -------------------------------------------------------------------------

    /**
     * Find potential matches for a user with pagination support.
     * Returns a {@link PotentialMatchPage} with embedded total count.
     *
     * <p><b>Batch D — Cache-first pagination (two-tier strategy):</b>
     * <ol>
     *   <li><b>Tier 1 — Serve from cache:</b> candidates with a fresh cached score are sorted
     *       in-memory and served immediately.  If the cache covers the requested page
     *       ({@code freshCached.size() >= offset + limit}) the response is instant.
     *       Uncached candidates are queued for background scoring via
     *       {@link #asyncScoreAndCache}.</li>
     *   <li><b>Tier 2 — Sync fallback:</b> when the cache cannot fill the page (cold start or
     *       first request for a new user), only {@code offset + limit} candidates are scored
     *       synchronously — never all 500.  The remainder are still queued for background
     *       warming so subsequent pages are served from cache.</li>
     * </ol>
     *
     * <p>No {@code @Transactional} here — the DB connection is held only for the short
     * fetch window inside {@link #fetchCandidateData}, not for the scoring loop.
     */
    public PotentialMatchPage findPotentialMatches(
            User user,
            int limit,
            int offset,
            double minScore,
            boolean excludeSwiped) {

        log.info("Finding potential matches for user {}: limit={}, offset={}, minScore={}, excludeSwiped={}",
                user.getId(), limit, offset, minScore, excludeSwiped);

        // Phase A: all DB reads in a short transaction that commits here.
        CandidateData data = self.fetchCandidateData(user, minScore, excludeSwiped);
        UserEntity userEntity = data.userEntity();

        // --- Split candidates into fresh-cache hits and those that need scoring ---
        List<ScoredCandidate> freshCached  = new ArrayList<>();
        List<UserEntity>      needsScoring = new ArrayList<>();

        for (UserEntity candidate : data.candidates()) {
            UserMatchScore cached = data.cachedScores().get(candidate.getId());
            if (cached != null && isCacheFresh(cached, data.staleAfter(), candidate)) {
                MatchScore score = matchScoringService.buildMatchScoreFromCache(
                        cached, user.getId(), candidate.getId());
                freshCached.add(new ScoredCandidate(candidate, score));
            } else {
                needsScoring.add(candidate);
            }
        }

        // Sort cache hits by overall score so we know which page they cover.
        freshCached.sort((a, b) -> Double.compare(b.score().getOverallScore(), a.score().getOverallScore()));

        List<ScoredCandidate> allScored;

        if (freshCached.size() >= offset + limit || needsScoring.isEmpty()) {
            // Tier 1: cache covers the full requested page (or there is nothing left to score).
            // Serve immediately; queue uncached candidates for background warm-up.
            allScored = freshCached;
            if (!needsScoring.isEmpty()) {
                log.debug("Cache covers page — queuing {} candidates for background scoring", needsScoring.size());
                self.asyncScoreAndCache(user, userEntity, needsScoring, data.genrePrefs());
            }
        } else {
            // Tier 2: cache cannot fill the page.
            // Score synchronously only as many candidates as needed to fill offset + limit.
            // This caps first-ever-request scoring at ~page-size, not all 500 candidates.
            int syncCount   = Math.min(needsScoring.size(), offset + limit);
            List<UserEntity> scoreNow   = needsScoring.subList(0, syncCount);
            List<UserEntity> scoreLater = needsScoring.subList(syncCount, needsScoring.size());

            log.debug("Scoring {} candidates synchronously, queuing {} for background",
                    scoreNow.size(), scoreLater.size());

            genrePrefetchContext.set(data.genrePrefs());
            List<ScoredCandidate> freshlyScored = new ArrayList<>();
            try {
                for (UserEntity candidate : scoreNow) {
                    MatchScore score = matchScoringService.calculateScore(userEntity, candidate);
                    matchScoringService.persistScoreCache(userEntity, candidate, score);
                    freshlyScored.add(new ScoredCandidate(candidate, score));
                }
            } finally {
                genrePrefetchContext.clear();
            }

            allScored = new ArrayList<>(freshCached);
            allScored.addAll(freshlyScored);
            allScored.sort((a, b) -> Double.compare(b.score().getOverallScore(), a.score().getOverallScore()));

            if (!scoreLater.isEmpty()) {
                self.asyncScoreAndCache(user, userEntity, scoreLater, data.genrePrefs());
            }
        }

        // Apply min-score filter and build DTOs.
        List<PotentialMatch> potentialMatches = new ArrayList<>();
        for (ScoredCandidate sc : allScored) {
            if (sc.score().getOverallScore() < data.effectiveMinScore()) {
                continue;
            }
            User candidate = userMapper.toDomain(sc.entity());
            potentialMatches.add(buildPotentialMatch(candidate, sc.entity(), sc.score(), user, data.distanceCache()));
        }

        int total     = potentialMatches.size();
        int fromIndex = Math.min(offset, total);
        int toIndex   = Math.min(offset + limit, total);
        List<PotentialMatch> page = new ArrayList<>(potentialMatches.subList(fromIndex, toIndex));

        log.info("Returning {} matches (page offset={}, total={}) for user {}",
                page.size(), offset, total, user.getId());

        return PotentialMatchPage.builder()
                .matches(page)
                .total(total)
                .limit(limit)
                .offset(offset)
                .hasMore((offset + limit) < total)
                .build();
    }

    /**
     * Background cache-warming: scores candidates asynchronously and persists results.
     *
     * <p>Called when the current page can be served from existing cache entries but
     * unscored candidates remain.  Subsequent pages for the same user will be served
     * from the cache entries written here.
     *
     * <p>Runs on the {@code taskExecutor} thread pool (configured in Batch A).
     * Sets {@link GenrePrefetchContext} on the async thread and always clears it in a
     * {@code finally} block to prevent ThreadLocal leaks across pooled-thread reuse.
     * Each individual failure is logged and swallowed so one bad candidate cannot abort
     * warming for the rest.
     *
     * <p><b>Must be called via {@code self.asyncScoreAndCache()} (proxy) so that
     * Spring intercepts the {@code @Async} annotation.</b>
     */
    @Async("taskExecutor")
    public void asyncScoreAndCache(
            User user,
            UserEntity userEntity,
            List<UserEntity> candidates,
            Map<String, List<UserGenrePreference>> genrePrefs) {

        log.debug("Background scoring {} candidates for user {}", candidates.size(), user.getId());
        genrePrefetchContext.set(genrePrefs);
        try {
            for (UserEntity candidate : candidates) {
                try {
                    MatchScore score = matchScoringService.calculateScore(userEntity, candidate);
                    matchScoringService.persistScoreCache(userEntity, candidate, score);
                } catch (Exception e) {
                    // A failed async score does not affect the current user response.
                    // The next feed request will re-attempt scoring for this candidate.
                    log.warn("Background scoring failed for candidate {}: {}",
                            candidate.getId(), e.getMessage());
                }
            }
        } finally {
            genrePrefetchContext.clear();
        }
        log.debug("Background scoring complete for user {}", user.getId());
    }

    /**
     * @deprecated Use findPotentialMatches() which now returns PotentialMatchPage including total count.
     */
    @Deprecated
    public int countPotentialMatches(User user, double minScore, boolean excludeSwiped) {
        return findPotentialMatches(user, Integer.MAX_VALUE, 0, minScore, excludeSwiped).getTotal();
    }

    /**
     * Evicts the candidate pool cache entry for the given user, forcing the next
     * {@link #findPotentialMatches} call to re-run the {@code ORDER BY RANDOM()} query.
     *
     * <p>Call this after a user's profile changes that affect the eligible candidate pool
     * (e.g. location update, gender preference change).  Swipes do not require explicit
     * eviction — the cache HIT path always filters against the current excluded-ID set.
     */
    public void invalidateCandidateCache(String userId) {
        candidatePoolCache.invalidate(userId);
    }

    /**
     * Calculate match score with a specific user.
     */
    public MatchScore getMatchScore(User currentUser, String otherUserId) {
        UserEntity currentUserEntity = userRepository.findById(currentUser.getId())
                .orElseThrow(() -> new UserNotFoundException("User not found"));
        UserEntity otherUserEntity = userRepository.findById(otherUserId)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        return matchScoringService.calculateScore(currentUserEntity, otherUserEntity);
    }

    // -------------------------------------------------------------------------
    // Cache freshness check
    // -------------------------------------------------------------------------

    private boolean isCacheFresh(UserMatchScore cached, LocalDateTime userUpdatedAt, UserEntity candidate) {
        LocalDateTime latestUpdate = java.util.stream.Stream.of(userUpdatedAt, candidate.getUpdatedAt())
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);

        if (latestUpdate == null) return true;
        return cached.getComputedAt().isAfter(latestUpdate);
    }

    // -------------------------------------------------------------------------
    // Hard filter helpers
    // -------------------------------------------------------------------------

    /**
     * Both users must mutually satisfy each other's gender preference.
     * interestedInGenders is stored as comma-separated enum names, e.g. "MALE,FEMALE".
     * We use exact token matching (NOT String.contains) to avoid "MALE" matching "FEMALE".
     */
    private boolean isGenderCompatible(UserEntity user, UserEntity candidate) {
        boolean userAcceptsCandidate = interestedIn(user, candidate.getGender());
        boolean candidateAcceptsUser = interestedIn(candidate, user.getGender());
        return userAcceptsCandidate && candidateAcceptsUser;
    }

    private boolean interestedIn(UserEntity viewer, Gender targetGender) {
        if (viewer.getDatingPreferences() == null) return true;
        String raw = viewer.getDatingPreferences().getInterestedInGenders();
        if (raw == null || raw.isBlank()) return true;
        if (targetGender == null) return false;

        Set<String> tokens = Arrays.stream(raw.split(","))
                .map(String::trim)
                .collect(Collectors.toSet());

        return tokens.contains(targetGender.name());
    }

    /**
     * Parses a user's {@code interestedInGenders} preference string into a token set.
     * Returns an empty set when there is no preference (no restriction — accepts all genders).
     * Called once per recommendation request for the requesting user, not once per candidate.
     */
    private static Set<String> parseGenderTokens(UserDatingPreferences prefs) {
        if (prefs == null) return Set.of();
        String raw = prefs.getInterestedInGenders();
        if (raw == null || raw.isBlank()) return Set.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /**
     * Fast gender-compatibility check: requesting user's gender tokens are pre-parsed once
     * before the filter loop (via {@link #parseGenderTokens}).
     * Each candidate's tokens are still parsed per-candidate (they differ by definition).
     */
    private boolean isGenderCompatibleFast(
            Set<String> userGenderTokens, Gender userGender, UserEntity candidate) {
        boolean userAcceptsCandidate = userGenderTokens.isEmpty()
                || (candidate.getGender() != null
                    && userGenderTokens.contains(candidate.getGender().name()));
        if (!userAcceptsCandidate) return false;
        return interestedIn(candidate, userGender);
    }

    /**
     * Bidirectional distance check: returns true only if the distance between the users
     * is within BOTH users' maxDistanceKm preference.
     * Returns true if either party has no coordinates (can't filter).
     *
     * <p>The computed distance is stored in {@code distanceCache} keyed by candidate ID so
     * {@link #buildPotentialMatch} can reuse it without a third haversine calculation.
     */
    private boolean isWithinDistance(UserEntity user, UserEntity candidate, Map<String, Double> distanceCache) {
        if (user.getLocationLat() == null || user.getLocationLon() == null
                || candidate.getLocationLat() == null || candidate.getLocationLon() == null) {
            return true;
        }

        double distKm = distanceCache.computeIfAbsent(candidate.getId(),
                id -> MatchScoringService.haversineKm(
                        user.getLocationLat().doubleValue(), user.getLocationLon().doubleValue(),
                        candidate.getLocationLat().doubleValue(), candidate.getLocationLon().doubleValue()));

        UserDatingPreferences userPrefs = user.getDatingPreferences();
        if (userPrefs != null && userPrefs.getMaxDistanceKm() != null
                && distKm > userPrefs.getMaxDistanceKm()) {
            return false;
        }

        UserDatingPreferences candidatePrefs = candidate.getDatingPreferences();
        if (candidatePrefs != null && candidatePrefs.getMaxDistanceKm() != null
                && distKm > candidatePrefs.getMaxDistanceKm()) {
            return false;
        }

        return true;
    }

    // -------------------------------------------------------------------------
    // Lazy association initializer
    // -------------------------------------------------------------------------

    /**
     * Force-initializes all lazy associations on a {@link UserEntity} that are accessed
     * during Phase B scoring or result-building.  Must be called while the Hibernate
     * session is open (i.e. inside a {@code @Transactional} method).
     *
     * <p>The {@code @BatchSize(50)} annotations on these fields mean Hibernate issues
     * one IN-clause SELECT per 50 entities rather than one SELECT per entity.  For 500
     * candidates this reduces ~2 500 lazy queries to ~50 batched queries across all five
     * associations.
     */
    private void initializeLazyAssociations(UserEntity entity) {
        Hibernate.initialize(entity.getMusicPreferences());
        Hibernate.initialize(entity.getLifestyle());
        Hibernate.initialize(entity.getPersonality());
        Hibernate.initialize(entity.getDatingPreferences());
        Hibernate.initialize(entity.getPhotos());
    }

    // -------------------------------------------------------------------------
    // PotentialMatch builder
    // -------------------------------------------------------------------------

    private PotentialMatch buildPotentialMatch(
            User candidate,
            UserEntity candidateEntity,
            MatchScore matchScore,
            User currentUser,
            Map<String, Double> distanceCache) {

        List<String> topSharedGenres = matchScore.getBreakdown() != null
                && matchScore.getBreakdown().getSharedGenres() != null
                ? matchScore.getBreakdown().getSharedGenres().stream()
                        .limit(3)
                        .map(sg -> sg.getGenreDisplayName())
                        .collect(Collectors.toList())
                : List.of();

        String previewInsight = (matchScore.getInsights() == null || matchScore.getInsights().isEmpty())
                ? "Check out your compatibility!"
                : matchScore.getInsights().get(0);

        Integer age = candidateEntity.getDateOfBirth() != null ? candidateEntity.getAge() : null;

        // Include all photos sorted by displayOrder
        List<String> photos;
        if (candidateEntity.getPhotos() != null && !candidateEntity.getPhotos().isEmpty()) {
            photos = candidateEntity.getPhotos().stream()
                    .sorted(Comparator.comparingInt(p -> p.getDisplayOrder() != null ? p.getDisplayOrder() : 0))
                    .map(UserPhoto::getImageUrl)
                    .toList();
        } else if (candidate.getImageUrl() != null) {
            photos = List.of(candidate.getImageUrl());
        } else {
            photos = List.of();
        }

        // Reuse the haversine distance already computed during Phase A filtering — avoids a third trig calculation
        Double rawDist = distanceCache.get(candidateEntity.getId());
        Double distance = rawDist != null
                ? Math.round(rawDist * 10.0) / 10.0
                : calculateDistanceKm(currentUser, candidate);

        return PotentialMatch.builder()
                .userId(candidate.getId())
                .name(candidate.getName())
                .age(age)
                .matchScore(matchScore.getOverallScore())
                .topSharedGenres(topSharedGenres)
                .previewInsight(previewInsight)
                .sharedGenreCount(matchScore.getBreakdown() != null
                        ? matchScore.getBreakdown().getSharedGenreCount() : 0)
                .compatibilityLevel(matchScore.getCompatibilityLevel())
                .photos(photos)
                .distance(distance)
                .build();
    }

    /**
     * Haversine formula for User domain objects (used in PotentialMatch distance display).
     */
    private Double calculateDistanceKm(User userA, User userB) {
        if (userA.getLocationLat() == null || userA.getLocationLon() == null
                || userB.getLocationLat() == null || userB.getLocationLon() == null) {
            return null;
        }

        double distKm = MatchScoringService.haversineKm(
                userA.getLocationLat().doubleValue(), userA.getLocationLon().doubleValue(),
                userB.getLocationLat().doubleValue(), userB.getLocationLon().doubleValue());

        return Math.round(distKm * 10.0) / 10.0;
    }
}
