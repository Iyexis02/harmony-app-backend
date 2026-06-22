# Backend Stability Fix Roadmap — Scalability & Performance Audit

## Purpose

This document tracks the scalability and performance refactor for the dating app Spring Boot backend. It addresses issues identified during a full audit covering connection pool configuration, query efficiency, pagination, blocking I/O, thread pool management, caching, entity loading, and indexing.

**This file is the single source of truth for implementation order.** Each agent session must read this file before starting work and follow the batch sequence strictly.

## Implementation Rules

1. Only implement **one batch per session**
2. Follow the **dependency order** strictly — never start a batch whose dependencies are unchecked
3. Do not modify code outside the current batch scope
4. After completing a batch, update its checkbox to `[x]` and fill in the completion date
5. Always read this file at the start of every session before implementing the next batch
6. Run the full test suite after each batch and confirm green before marking complete
7. If a batch cannot be completed, add a `**Blocked:**` note under it and move on only if the next batch has no dependency on it

---

## Dependency Graph

```
A (independent — infrastructure: connection pool + thread pool + async executor config)
B (independent — database indexes on foreign keys and composite columns)
A ─► C (scoring transaction refactor depends on pool config existing)
B ─► C (scoring refactor benefits from indexes being in place)
C ─► D (pagination refactor depends on scoring being outside the transaction)
D ─► E (query optimization depends on pagination being correct)
F (independent — blocking I/O: Spotify async, no deps)
G (independent — entity loading: BatchSize, JOIN FETCH, projections)
H (independent — rate limiter migration to Redis, no deps)
```

## Recommended Implementation Order

```
Phase 1 — Infrastructure Foundation (P0)
  1. Batch A — Connection pool, thread pool, and async executor configuration  [independent]
  2. Batch B — Missing database indexes                                        [independent]

Phase 2 — Core Scoring Pipeline (P1)
  3. Batch C — Scoring transaction refactor                                    [depends on A, B]
  4. Batch D — Pagination: score-then-paginate → paginate-from-cache           [depends on C]

Phase 3 — Query & I/O Optimization (P2)
  5. Batch E — Repository query optimization (JOIN FETCH, pagination, N+1)     [depends on D]
  6. Batch F — Async Spotify sync and blocking I/O                             [independent]

Phase 4 — Entity Loading & Caching (P3)
  7. Batch G — Entity loading: @BatchSize, projections, lazy-load fixes        [independent]
  8. Batch H — Rate limiter Redis migration + cache key fixes                  [independent]
```

---

## Phase 1 — Infrastructure Foundation (P0)

### - [x] Batch A — Connection Pool, Thread Pool, and Async Executor Configuration (2026-03-16)

**Priority:** CRITICAL
**Risk:** Default HikariCP pool = 10 connections, default Tomcat pool = 200 threads. 200 threads competing for 10 connections causes instant bottleneck under concurrent load. `AsyncConfig` only overrides `getAsyncUncaughtExceptionHandler()` but never `getAsyncExecutor()`, so Spring defaults to `SimpleAsyncTaskExecutor` which creates an unbounded new thread per `@Async` call (email sends, behavioral profile updates on every swipe).
**Affected files:**
- `src/main/resources/application.yml`
- `src/main/java/com/example/dating/config/AsyncConfig.java`

**What to do:**

1. **Add HikariCP connection pool configuration to `application.yml`.**
   ```yaml
   spring:
     datasource:
       hikari:
         maximum-pool-size: 30
         minimum-idle: 10
         idle-timeout: 300000
         connection-timeout: 5000
         max-lifetime: 1800000
         leak-detection-threshold: 30000
   ```
   These values support ~30 concurrent DB-accessing requests. The `leak-detection-threshold` (30s) logs warnings when a connection is held longer than expected — critical for catching long-running transactions (see Batch C).

2. **Add Tomcat thread pool configuration to `application.yml`.**
   ```yaml
   server:
     tomcat:
       threads:
         max: 200
         min-spare: 20
       accept-count: 100
   ```
   Explicitly declare defaults so they are visible and tunable. `accept-count: 100` queues requests when all threads are busy instead of refusing connections.

3. **Add `ThreadPoolTaskExecutor` to `AsyncConfig`.** Override `getAsyncExecutor()` to provide a bounded thread pool:
   ```java
   @Override
   public Executor getAsyncExecutor() {
       ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
       executor.setCorePoolSize(10);
       executor.setMaxPoolSize(50);
       executor.setQueueCapacity(200);
       executor.setThreadNamePrefix("async-");
       executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
       executor.initialize();
       return executor;
   }
   ```
   `CallerRunsPolicy` ensures that if the queue is full, the calling thread executes the task (backpressure). This prevents silent task drops. The `@Async` methods affected are:
   - `EmailServiceImpl.sendVerificationEmail()`
   - `EmailServiceImpl.sendPasswordResetEmail()`
   - `BehavioralProfileService.onSwipeRecorded()` (if async)

   Import `org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor` and `java.util.concurrent.ThreadPoolExecutor`.

**Verification:**
- Application starts without errors.
- Check HikariCP pool stats in logs at startup (Spring Boot auto-logs pool config at DEBUG level).
- Confirm that `@Async` methods use the `async-` thread name prefix (visible in log output during email send or behavioral update).
- Run the existing test suite — all tests pass.

---

### - [x] Batch B — Missing Database Indexes (2026-03-16)

**Priority:** CRITICAL
**Risk:** Eight entity tables lack indexes on `user_id` foreign key columns that are queried on every profile fetch, onboarding operation, and match scoring calculation. Additionally, `user_match_scores` lacks a composite index on `(user_id, algorithm_version)` which is queried on every match feed request. Match table queries use OR clauses on `user_a_id`/`user_b_id` without composite indexes on `(user_a_id, status)` and `(user_b_id, status)`.
**Affected files:**
- `src/main/java/com/example/dating/models/user/privacy/dao/UserPrivacySettings.java`
- `src/main/java/com/example/dating/models/user/lifestyle/dao/UserLifestyle.java`
- `src/main/java/com/example/dating/models/user/personality/dao/UserPersonality.java`
- `src/main/java/com/example/dating/models/user/preferences/dao/UserMusicPreferences.java`
- `src/main/java/com/example/dating/models/user/dating/dao/UserDatingPreferences.java`
- `src/main/java/com/example/dating/models/matching/dao/UserBehavioralProfile.java`
- `src/main/java/com/example/dating/models/matching/dao/UserMatchScore.java`
- `src/main/java/com/example/dating/models/matching/dao/Match.java`

**What to do:**

1. **Add `@Table(indexes = ...)` to five user sub-entity classes.** Each has a `user_id` column used in `findByUserId()` repository calls. Add an index annotation to the `@Table` declaration:

   - **`UserPrivacySettings`** — add:
     ```java
     @Table(name = "user_privacy_settings", indexes = {
         @Index(name = "idx_privacy_user", columnList = "user_id")
     })
     ```

   - **`UserLifestyle`** — add:
     ```java
     @Table(name = "user_lifestyle", indexes = {
         @Index(name = "idx_lifestyle_user", columnList = "user_id")
     })
     ```

   - **`UserPersonality`** — add:
     ```java
     @Table(name = "user_personality", indexes = {
         @Index(name = "idx_personality_user", columnList = "user_id")
     })
     ```

   - **`UserMusicPreferences`** — add:
     ```java
     @Table(name = "user_music_preferences", indexes = {
         @Index(name = "idx_music_prefs_user", columnList = "user_id")
     })
     ```

   - **`UserDatingPreferences`** — add:
     ```java
     @Table(name = "user_dating_preferences", indexes = {
         @Index(name = "idx_dating_prefs_user", columnList = "user_id")
     })
     ```

2. **Add index to `UserBehavioralProfile`.** The `user_id` column is `unique = true` but has no explicit index. While PostgreSQL creates an implicit unique index, make it explicit for clarity and add it to the `@Table` annotation:
   ```java
   @Table(name = "user_behavioral_profile", indexes = {
       @Index(name = "idx_behavioral_user", columnList = "user_id", unique = true)
   })
   ```

3. **Add composite index to `UserMatchScore`.** The query `findAllByUserIdAndVersion()` filters on both `user_id` and `algorithm_version` on every match feed request:
   ```java
   @Table(name = "user_match_scores", indexes = {
       @Index(name = "idx_match_score_user", columnList = "user_id"),
       @Index(name = "idx_match_score_user_version", columnList = "user_id, algorithm_version")
   })
   ```
   Keep the existing single-column index if present. The composite index covers the hot-path query.

4. **Add composite indexes to `Match`.** All match queries use `(user_a_id OR user_b_id) AND status` patterns. Add:
   ```java
   @Table(name = "matches", indexes = {
       // ... keep existing indexes ...
       @Index(name = "idx_match_a_status", columnList = "user_a_id, status"),
       @Index(name = "idx_match_b_status", columnList = "user_b_id, status"),
       @Index(name = "idx_match_a_status_conv", columnList = "user_a_id, status, conversation_started"),
       @Index(name = "idx_match_b_status_conv", columnList = "user_b_id, status, conversation_started")
   })
   ```
   These cover `findMatchesByUserIdAndStatus()`, `findActiveMatchesByUserId()`, `findMatchesWithConversations()`, `findMatchesWithoutConversations()`, and `findRecentMatches()`.

**Verification:**
- Application starts and Hibernate `ddl-auto: update` creates the new indexes (check logs for `CREATE INDEX` statements).
- Run `EXPLAIN ANALYZE` on `SELECT * FROM user_match_scores WHERE user_id = ? AND algorithm_version = ?` — should show Index Scan, not Seq Scan.
- Run the existing test suite — all tests pass.

---

## Phase 2 — Core Scoring Pipeline (P1)

### - [x] Batch C — Scoring Transaction Refactor (2026-03-16)

**Priority:** CRITICAL
**Risk:** `MatchRecommendationService.findPotentialMatches()` wraps the entire candidate fetch + scoring loop in a single `@Transactional(readOnly = true)`. This holds a DB connection for the full duration of scoring up to 500 candidates. Each cache miss calls `MatchScoringService.calculateScore()` which uses `@Transactional(propagation = REQUIRES_NEW)`, acquiring a **second** connection. Under 10 concurrent requests, this exhausts the connection pool and deadlocks.
**Affected files:**
- `src/main/java/com/example/dating/services/matching/MatchRecommendationService.java`
- `src/main/java/com/example/dating/services/matching/MatchScoringService.java`

**What to do:**

1. **Split `findPotentialMatches()` into two phases: data fetch (in transaction) and scoring (outside transaction).**

   Phase A — Fetch candidates and cached scores in a short `@Transactional(readOnly = true)`:
   ```java
   @Transactional(readOnly = true)
   public CandidateData fetchCandidates(User user, int limit, int offset) {
       // 1. Fetch UserEntity for the requesting user
       // 2. Fetch excluded IDs (swiped, blocked, blocked-by)
       // 3. Fetch candidate UserEntity list (up to 500)
       // 4. Filter by gender compatibility and distance
       // 5. Fetch cached scores from user_match_scores
       // 6. Pre-fetch genre preferences for all candidate IDs
       // 7. Return a CandidateData record holding all fetched data
   }
   ```
   This transaction completes quickly (pure SELECTs) and releases the connection.

   Phase B — Score and paginate outside any transaction:
   ```java
   public PotentialMatchPage findPotentialMatches(User user, int limit, int offset) {
       CandidateData data = fetchCandidates(user, limit, offset);
       // Score each candidate using fetched data (no DB connection held)
       // Sort by score descending
       // Paginate (subList)
       // Return PotentialMatchPage
   }
   ```

2. **Pass pre-fetched data into the scoring methods** instead of letting scorers query the DB. Modify `MatchScoringService.calculateScore()` to accept a data context object (or use the existing `GenrePrefetchContext` ThreadLocal). This removes the need for `REQUIRES_NEW` on score calculation since no DB writes happen during scoring — only the cache write at the end needs a transaction.

3. **Isolate cache writes into a separate short transaction.**
   ```java
   @Transactional
   public void persistScoreCache(UserMatchScore score) {
       userMatchScoreRepository.save(score);
   }
   ```
   Call this per-candidate only for cache misses. Each save is a quick INSERT/UPDATE and releases immediately.

4. **Set `GenrePrefetchContext` before Phase B scoring and clear in a `finally` block** to prevent memory leaks if an exception occurs mid-scoring.

**Verification:**
- Enable HikariCP leak detection (`leak-detection-threshold: 5000` temporarily) — confirm no warnings during concurrent match feed requests.
- Run 10 concurrent requests to `GET /api/v1/matching/potential` — all should complete without connection timeout.
- Run the existing test suite — all tests pass.
- Compare match feed response payloads before and after — scores should be identical.

---

### - [x] Batch D — Pagination: Score-Then-Paginate → Paginate-From-Cache (2026-03-16)

**Priority:** HIGH
**Risk:** `findPotentialMatches()` scores ALL 500 candidates, sorts them, then takes `subList(offset, offset+limit)`. For `limit=20`, 480 scores are computed and discarded every request. This wastes CPU and memory linearly with candidate count.
**Affected files:**
- `src/main/java/com/example/dating/services/matching/MatchRecommendationService.java`

**What to do:**

1. **Implement a two-tier scoring strategy:**

   **Tier 1 — Serve from cache:** For candidates with fresh cached scores, skip recalculation entirely. Sort cached scores in-memory and serve the page immediately.

   **Tier 2 — Background refresh:** For candidates with stale or missing cached scores, compute fresh scores asynchronously and persist to cache. The user sees slightly stale data but gets an instant response.

   Implementation:
   ```java
   public PotentialMatchPage findPotentialMatches(User user, int limit, int offset) {
       CandidateData data = fetchCandidates(user, limit, offset);
       List<UserEntity> candidates = data.candidates();
       Map<String, UserMatchScore> cachedScores = data.cachedScores();
       LocalDateTime staleAfter = LocalDateTime.now().minusHours(24);

       // Separate candidates into cached (fresh) and uncached
       List<ScoredCandidate> freshCached = new ArrayList<>();
       List<UserEntity> needsScoring = new ArrayList<>();

       for (UserEntity candidate : candidates) {
           UserMatchScore cached = cachedScores.get(candidate.getId());
           if (cached != null && isCacheFresh(cached, staleAfter, candidate)) {
               freshCached.add(new ScoredCandidate(candidate, buildMatchScoreFromCache(cached)));
           } else {
               needsScoring.add(candidate);
           }
       }

       // Score uncached candidates synchronously (only those needed for the current page)
       // Sort freshCached by score descending
       // If freshCached.size() >= offset + limit, serve entirely from cache
       // Otherwise, score enough from needsScoring to fill the page

       // Queue remaining uncached candidates for background scoring
       if (!needsScoring.isEmpty()) {
           asyncScoreAndCache(user, needsScoring, data.genrePrefs());
       }
   }
   ```

2. **Add an `@Async` method for background cache warming:**
   ```java
   @Async
   public void asyncScoreAndCache(User user, List<UserEntity> candidates,
                                   Map<String, List<UserGenrePreference>> genrePrefs) {
       genrePrefetchContext.set(genrePrefs);
       try {
           for (UserEntity candidate : candidates) {
               MatchScore score = matchScoringService.calculateScore(user, candidate);
               persistScoreCache(/* build UserMatchScore from score */);
           }
       } finally {
           genrePrefetchContext.clear();
       }
   }
   ```

3. **For the first-ever request (empty cache),** fall back to synchronous scoring of only `offset + limit` candidates (not all 500). Sort the scored subset and return. Cache all scored results for subsequent pages.

**Verification:**
- First request for a new user completes in <2s (scores only ~20 candidates, not 500).
- Second request with `offset=20` returns instantly from cache.
- Background scoring populates cache entries — verify with `SELECT count(*) FROM user_match_scores WHERE user_id = ?`.
- Scores match between synchronous and cache-served responses.
- Run the existing test suite — all tests pass.

---

## Phase 3 — Query & I/O Optimization (P2)

### - [x] Batch E — Repository Query Optimization (2026-03-17)

**Priority:** HIGH
**Risk:** 15+ repository methods return unbounded `List<>` without pagination. Key hot-path methods (`findAllSwipedUserIds()`, `findBlockedUserIds()`, `findBlockedByUserIds()`) are called on every recommendation request. UserSwipe queries lack `JOIN FETCH` for user relationships, triggering N+1 lazy loads. `GenreExtractionService` issues up to 3 queries per Spotify genre in a loop (600 queries for 200 genres).
**Affected files:**
- `src/main/java/com/example/dating/repositories/UserSwipeRepository.java`
- `src/main/java/com/example/dating/repositories/MatchRepository.java`
- `src/main/java/com/example/dating/repositories/UserMatchScoreRepository.java`
- `src/main/java/com/example/dating/repositories/CanonicalGenreRepository.java`
- `src/main/java/com/example/dating/services/matching/GenreExtractionService.java`

**What to do:**

1. **Add pagination to unbounded `MatchRepository` methods.** Change these methods to accept `Pageable`:
   - `findActiveMatchesByUserId()` → add `Pageable pageable` parameter
   - `findAllMatchesByUserId()` → add `Pageable pageable` parameter
   - `findMatchesWithConversations()` → add `Pageable pageable` parameter
   - `findMatchesWithoutConversations()` → add `Pageable pageable` parameter
   - `findRecentMatches()` → add `Pageable pageable` parameter

   Update all callers to pass `PageRequest.of(0, 100)` as a sensible default. Adjust the return type to `Page<Match>` or keep `List<Match>` with Pageable — Spring Data handles both.

2. **Add pagination to unbounded `UserSwipeRepository` methods:**
   - `findLikesByUserId()` → add `Pageable pageable`
   - `findPassesByUserId()` → add `Pageable pageable`
   - `findUsersWhoLiked()` → add `Pageable pageable`
   - `findHighScoringPasses()` → add `Pageable pageable`
   - `findLowScoringLikes()` → add `Pageable pageable`

3. **Add `JOIN FETCH` to UserSwipe queries that access user relationships.** For queries where callers access `swiperUser` or `swipedUser` properties:
   ```java
   @Query("SELECT us FROM UserSwipe us " +
          "JOIN FETCH us.swipedUser " +
          "WHERE us.swiperUser.id = :userId AND us.action = 'like' " +
          "ORDER BY us.swipedAt DESC")
   List<UserSwipe> findLikesByUserId(@Param("userId") String userId, Pageable pageable);
   ```
   Apply to: `findLikesByUserId`, `findPassesByUserId`, `findHighScoringPasses`, `findLowScoringLikes`, `findSwipesThatMatched`.

   **Note:** `JOIN FETCH` with `Pageable` in JPQL requires a `countQuery` parameter to avoid the HHH000104 warning:
   ```java
   @Query(value = "SELECT us FROM UserSwipe us JOIN FETCH us.swipedUser WHERE ...",
          countQuery = "SELECT count(us) FROM UserSwipe us WHERE ...")
   ```

4. **Batch-load canonical genres in `GenreExtractionService`.** Replace the per-genre loop that calls `findByName()`, `findBySpotifyAlias()`, and `searchByName()` with a single upfront load:
   ```java
   // Load all canonical genres once at method start
   List<CanonicalGenre> allGenres = canonicalGenreRepository.findAll();
   Map<String, CanonicalGenre> byName = allGenres.stream()
       .collect(Collectors.toMap(g -> g.getName().toLowerCase(), g -> g, (a, b) -> a));
   // Build alias map from spotifyAliases field
   Map<String, List<CanonicalGenre>> byAlias = new HashMap<>();
   for (CanonicalGenre g : allGenres) {
       if (g.getSpotifyAliases() != null) {
           for (String alias : g.getSpotifyAliases().split(",")) {
               byAlias.computeIfAbsent(alias.trim().toLowerCase(), k -> new ArrayList<>()).add(g);
           }
       }
   }
   // Then match in-memory instead of querying per genre
   ```
   This reduces 600 queries to 1 query. The canonical genre table is small (~200 rows) and rarely changes.

5. **Add a `LIMIT` safeguard to `findAllSwipedUserIds()` and `findBlockedUserIds()`/`findBlockedByUserIds()`.** These are called on every recommendation request. For power users with 10,000+ swipes, the ID list becomes enormous. Two options:
   - **(Preferred)** Push the exclusion into the candidate query as a subquery instead of loading IDs into memory.
   - **(Simpler)** Accept the current approach but add a hardcoded `LIMIT 10000` to prevent unbounded growth.

**Verification:**
- All updated repository methods compile and return correct results with pagination.
- `GenreExtractionService` sync completes with only 1 SELECT on `canonical_genres` (check SQL logs).
- Run the existing test suite — all tests pass.
- Test match list endpoints return paginated results (ch
- eck response has correct count).

---

### - [x] Batch F — Async Spotify Sync and Blocking I/O (2026-03-17)

**Priority:** HIGH
**Risk:** `SpotifyGenreSyncService.syncUserGenrePreferences()` makes 3 sequential HTTP calls to the Spotify API in the Tomcat request thread. At ~500ms per call = 1.5s blocking per sync. 20 concurrent syncs = 30s of thread pool saturation. `UserController.getTopArtists()`, `getTopTracks()`, and `getSuggestedGenres()` also make blocking Spotify HTTP calls with no timeout configuration.
**Affected files:**
- `src/main/java/com/example/dating/services/matching/SpotifyGenreSyncService.java`
- `src/main/java/com/example/dating/controllers/UserController.java`

**What to do:**

1. **Parallelize the 3 Spotify API calls in `syncUserGenrePreferences()`.** Replace the 3 sequential `fetchGenresWithWeight()` calls with `CompletableFuture`:
   ```java
   CompletableFuture<List<String>> shortTerm = CompletableFuture.supplyAsync(
       () -> fetchGenresWithWeight(accessToken, 50, "short_term", 3), asyncExecutor);
   CompletableFuture<List<String>> mediumTerm = CompletableFuture.supplyAsync(
       () -> fetchGenresWithWeight(accessToken, 50, "medium_term", 2), asyncExecutor);
   CompletableFuture<List<String>> longTerm = CompletableFuture.supplyAsync(
       () -> fetchGenresWithWeight(accessToken, 50, "long_term", 1), asyncExecutor);

   CompletableFuture.allOf(shortTerm, mediumTerm, longTerm).join();

   List<String> allGenres = new ArrayList<>();
   try { allGenres.addAll(shortTerm.get()); } catch (Exception e) { failedRanges++; }
   try { allGenres.addAll(mediumTerm.get()); } catch (Exception e) { failedRanges++; }
   try { allGenres.addAll(longTerm.get()); } catch (Exception e) { failedRanges++; }
   ```
   Inject the `TaskExecutor` bean from `AsyncConfig` (Batch A). This reduces sync time from ~1.5s to ~500ms (latency of slowest call).

2. **Add HTTP client timeout configuration.** If using `RestTemplate` for Spotify calls, configure timeouts:
   ```java
   @Bean
   public RestTemplate spotifyRestTemplate() {
       var factory = new SimpleClientHttpRequestFactory();
       factory.setConnectTimeout(Duration.ofSeconds(5));
       factory.setReadTimeout(Duration.ofSeconds(10));
       return new RestTemplate(factory);
   }
   ```
   If using another HTTP client, apply equivalent timeout settings. This prevents a single slow Spotify response from blocking a thread indefinitely.

3. **Add timeout to `UserController` Spotify endpoints.** Wrap `spotifyService.getTopArtists()`, `spotifyService.getTopTracks()`, and `spotifyService.getSuggestedGenres()` in a timeout:
   ```java
   CompletableFuture.supplyAsync(() -> spotifyService.getTopArtists(token, limit, timeRange))
       .orTimeout(10, TimeUnit.SECONDS)
       .join();
   ```
   Or configure at the HTTP client level (preferred — see step 2).

**Verification:**
- Genre sync completes in ~500ms instead of ~1.5s (check logs for timing).
- Simulate Spotify API timeout (>10s) — request fails with timeout error instead of hanging.
- Run the existing test suite — all tests pass.

---

## Phase 4 — Entity Loading & Caching (P3)

### - [x] Batch G — Entity Loading: @BatchSize, Projections, Lazy-Load Fixes (2026-03-17)

**Priority:** MEDIUM
**Risk:** `UserPrivacySettings` lacks `@BatchSize` on the `UserEntity` side (all other OneToOne sub-entities have `@BatchSize(size = 50)`). Loading 50 candidates triggers 50 individual SELECTs for privacy settings. `UserJpaRepository.findCandidateUsers()` returns full `UserEntity` with all columns including `spotifyAccessToken`, `passwordHash`, etc., when matching only needs a subset. `MatchRecommendationService` recalculates haversine distance 3 times per candidate. Gender string parsing creates a new `Set` per candidate evaluation.
**Affected files:**
- `src/main/java/com/example/dating/models/user/common/dao/UserEntity.java`
- `src/main/java/com/example/dating/services/matching/MatchRecommendationService.java`

**What to do:**

1. **Add `@BatchSize(size = 50)` to `privacySettings` field in `UserEntity`.** This matches the pattern already used for `musicPreferences`, `lifestyle`, `personality`, and `datingPreferences`:
   ```java
   @ToString.Exclude
   @BatchSize(size = 50)
   @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
   private UserPrivacySettings privacySettings;
   ```

2. **Cache haversine distance per candidate in `MatchRecommendationService`.** Currently computed in `isWithinDistance()` (filtering), then again in `calculateLocationScore()` (scoring), then again in `buildPotentialMatch()` (display). Add a `Map<String, Double>` local variable:
   ```java
   Map<String, Double> distanceCache = new HashMap<>();
   // In isWithinDistance():
   double dist = distanceCache.computeIfAbsent(candidate.getId(),
       id -> MatchScoringService.haversineKm(
           user.getLocationLat().doubleValue(), user.getLocationLon().doubleValue(),
           candidate.getLocationLat().doubleValue(), candidate.getLocationLon().doubleValue()));
   ```
   Pass the cache to `buildPotentialMatch()` to avoid the third computation.

3. **Pre-parse gender preference set for the requesting user.** In `findPotentialMatches()`, parse the user's `interestedInGenders` once before the filtering loop:
   ```java
   Set<String> userInterestedIn = parseGenders(userEntity.getDatingPreferences());
   Set<String> userGenders = Set.of(userEntity.getGender().name());
   // Then in the filter:
   candidates.stream()
       .filter(c -> userInterestedIn.contains(c.getGender().name())
                 && parseGenders(c.getDatingPreferences()).contains(userEntity.getGender().name()))
   ```
   Note: each candidate's `interestedInGenders` still needs parsing, but the requesting user's set is parsed once instead of 500 times. For a future optimization, consider storing parsed gender sets in the entity as a `@Transient` field.

4. **(Optional) Create a candidate projection DTO** for `findCandidateUsers()` to avoid loading sensitive columns. This is a larger refactor — only do it if the query is measurably slow due to column count. Profile first.

**Verification:**
- SQL logs show batched SELECT for privacy settings (1-2 queries instead of 50).
- `EXPLAIN ANALYZE` on candidate query shows same plan (projection is optional).
- Run the existing test suite — all tests pass.

---

### - [x] Batch H — Rate Limiter Redis Migration + Cache Key Fixes (2026-03-17)

**Priority:** MEDIUM
**Risk:** `RateLimitFilter` (50k Caffeine entries) and `AuthenticatedRateLimitInterceptor` (100k entries) use in-memory caches. In a multi-instance deployment, each instance has its own buckets — users bypass limits by hitting different instances. Additionally, `BehavioralScoreCalculator.genreWeightCache` uses `profileId + "_" + lastUpdatedAt` as the key, which means every profile update creates a new key, evicting the old one and causing constant cache misses for active users.
**Affected files:**
- `src/main/java/com/example/dating/security/RateLimitFilter.java`
- `src/main/java/com/example/dating/security/AuthenticatedRateLimitInterceptor.java`
- `src/main/java/com/example/dating/services/matching/BehavioralScoreCalculator.java`

**What to do:**

1. **Migrate rate limiting to Redis.** Add `spring-boot-starter-data-redis` dependency to `pom.xml`. Replace Caffeine-backed buckets with Redis-backed counters using a sliding window or token bucket algorithm:
   ```xml
   <dependency>
       <groupId>org.springframework.boot</groupId>
       <artifactId>spring-boot-starter-data-redis</artifactId>
   </dependency>
   ```

   Option A (recommended): Use `bucket4j-redis` for drop-in replacement:
   ```xml
   <dependency>
       <groupId>com.bucket4j</groupId>
       <artifactId>bucket4j-redis</artifactId>
       <version>8.10.1</version>
   </dependency>
   ```

   Option B (simpler): Implement a Redis INCR + EXPIRE based counter:
   ```java
   public boolean tryConsume(String key, int maxRequests, Duration window) {
       String redisKey = "rate:" + key;
       Long count = redisTemplate.opsForValue().increment(redisKey);
       if (count == 1) {
           redisTemplate.expire(redisKey, window);
       }
       return count <= maxRequests;
   }
   ```

   Replace both `RateLimitFilter` and `AuthenticatedRateLimitInterceptor` to use the Redis-backed implementation.

2. **Add Redis connection configuration to `application.yml`:**
   ```yaml
   spring:
     data:
       redis:
         host: ${REDIS_HOST:localhost}
         port: ${REDIS_PORT:6379}
   ```

3. **Fix `BehavioralScoreCalculator.genreWeightCache` key strategy.** Change the cache key from `profileId + "_" + lastUpdatedAt` to just `profileId`. Explicitly invalidate the cache entry when the profile is updated:
   ```java
   // In BehavioralScoreCalculator:
   private final Cache<String, Map<String, Double>> genreWeightCache = Caffeine.newBuilder()
       .maximumSize(1000)
       .expireAfterWrite(10, TimeUnit.MINUTES)
       .build();

   // Key is just profile ID now
   String key = profile.getId();

   // Add invalidation method
   public void invalidateCache(String profileId) {
       genreWeightCache.invalidate(profileId);
   }
   ```

   Call `invalidateCache()` from `BehavioralProfileService` after updating a profile:
   ```java
   // In BehavioralProfileService, after saving the updated profile:
   behavioralScoreCalculator.invalidateCache(profile.getId());
   ```

4. **Make Redis optional for development.** Gate the Redis rate limiter behind a `@ConditionalOnBean(RedisConnectionFactory.class)` or a `@Profile` annotation so that local development still works with in-memory Caffeine when Redis is not available. Register the Caffeine-backed implementation as a fallback:
   ```java
   @Configuration
   public class RateLimitConfig {
       @Bean
       @ConditionalOnBean(RedisConnectionFactory.class)
       public RateLimiter redisRateLimiter(RedisTemplate<String, String> redisTemplate) {
           return new RedisRateLimiter(redisTemplate);
       }

       @Bean
       @ConditionalOnMissingBean(RateLimiter.class)
       public RateLimiter inMemoryRateLimiter() {
           return new CaffeineRateLimiter();
       }
   }
   ```

**Verification:**
- With Redis running: rate limits are shared across instances (test by hitting the same endpoint from two app processes).
- Without Redis: application starts with Caffeine fallback (no errors).
- `BehavioralScoreCalculator` cache shows improved hit rate — same profile ID returns cached value after update + invalidation cycle.
- Run the existing test suite — all tests pass.

---

## Summary

| Batch | Phase | Priority | Scope | Status |
|-------|-------|----------|-------|--------|
| A | 1 | CRITICAL | HikariCP + Tomcat + AsyncExecutor config | `[x]` 2026-03-16 |
| B | 1 | CRITICAL | 8 tables: add missing DB indexes | `[x]` 2026-03-16 |
| C | 2 | CRITICAL | Split scoring out of long-held transaction | `[x]` 2026-03-16 |
| D | 2 | HIGH | Cache-first pagination, async background scoring | `[x]` 2026-03-16 |
| E | 3 | HIGH | Repository pagination, JOIN FETCH, genre batch-load | `[x]` 2026-03-17 |
| F | 3 | HIGH | Async Spotify sync, HTTP timeouts | `[x]` 2026-03-17 |
| G | 4 | MEDIUM | @BatchSize, distance cache, gender pre-parse | `[x]` 2026-03-17 |
| H | 4 | MEDIUM | Redis rate limiting, cache key fix | `[x]` 2026-03-17 |
