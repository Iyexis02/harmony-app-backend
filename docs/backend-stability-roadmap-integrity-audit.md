# Backend Stability Fix Roadmap — Cross-Module Integrity Audit

## Purpose

This document tracks the cross-module integrity refactor for the dating app Spring Boot backend. It addresses issues identified during a full audit covering cross-service coupling, hidden side effects, incorrect event ordering, missing notifications, stale cache propagation, and matching logic integrity risks.

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

## Verified Cross-Module Interaction State (Pre-Audit)

### Service Dependency Map

| Service | Direct Dependencies | Transaction Boundary |
|---|---|---|
| `SwipeService` | `UserSwipeRepository`, `UserJpaRepository`, `MatchService`, `SwipePersistenceHelper`, `ApplicationEventPublisher`, `MatchRecommendationService` | `@Transactional` on `recordSwipe()` |
| `MatchService` | `MatchRepository`, `UserJpaRepository`, `ApplicationContext` | `@Transactional` per method; retry wrappers on `unmatch()`, `markConversationStarted()` |
| `MatchRecommendationService` | `MatchScoringService`, `UserJpaRepository`, `UserSwipeRepository`, `UserMapper`, `UserBehavioralProfileRepository`, `UserMatchScoreRepository`, `UserGenrePreferenceRepository`, `GenrePrefetchContext`, self (`@Lazy`) | Two-phase: `@Transactional(readOnly=true)` fetch, then scoring outside tx |
| `MatchScoringService` | `MatchScoreCalculator`, `LifestyleScoreCalculator`, `InterestsScoreCalculator`, `UserMatchScoreRepository`, `UserMapper`, `UserBehavioralProfileRepository`, `BehavioralScoreCalculator` (`@Lazy`) | No tx on `calculateScore()`; `@Transactional` on `persistScoreCache()` |
| `BehavioralProfileService` | `UserBehavioralProfileRepository`, `UserGenrePreferenceRepository`, `UserJpaRepository`, `ObjectMapper`, `ApplicationContext`, `BehavioralScoreCalculator` | `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`; retry orchestrator + `@Transactional` on `doUpdateAfterSwipe()` |
| `SpotifyGenreSyncService` | `SpotifyService`, `GenreExtractionService`, `UserRepository`, `Executor` | `@Transactional` on both sync methods; parallel Spotify calls via `CompletableFuture` |
| `UserServiceImpl` | `UserRepository`, `EncryptionService`, `UserMapper`, `JwtService`, `PasswordEncoder`, `UserSwipeRepository`, `MatchRepository`, `UserMatchScoreRepository`, `UserGenrePreferenceRepository`, `UserBehavioralProfileRepository` | `@Transactional` on mutating methods; per-user `synchronized` lock on token refresh |

### Event Flow

| Event | Publisher | Listener | Transport |
|---|---|---|---|
| `SwipeRecordedEvent` | `SwipeService.recordSwipe()` | `BehavioralProfileService.onSwipeRecorded()` | `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` |
| *(none)* | Match creation | *(no listener)* | N/A — other user not notified |
| *(none)* | Unmatch | *(no listener)* | N/A — other user not notified |
| *(none)* | Genre sync completion | *(no listener)* | N/A — cached scores not invalidated |

### Cache Invalidation Paths

| Cache | Populated By | Invalidated By | Gap |
|---|---|---|---|
| `user_match_scores` table | `MatchScoringService.persistScoreCache()` | Stale check: `computedAt > max(userA.updatedAt, userB.updatedAt)` | Behavioral profile changes do NOT update `UserEntity.updatedAt` — scores stay "fresh" despite changed behavioral weights |
| Caffeine `genreWeightCache` in `BehavioralScoreCalculator` | `calculate()` on cache miss | `invalidateCache(profileId)` called by `BehavioralProfileService` after save | Window between old value read by concurrent thread and invalidation call |
| `GenrePrefetchContext` (ThreadLocal) | `MatchRecommendationService.fetchCandidateData()` | `finally` block in scoring loop and `asyncScoreAndCache()` | None — correctly managed |

---

## Dependency Graph

```
A (independent — fix mutual match race condition by removing REQUIRES_NEW from swipe persistence)
B (independent — add match notification events)
A ─► C (score computation ordering depends on swipe atomicity being correct)
D (independent — behavioral profile cache invalidation via UserEntity.updatedAt)
C ─► E (genre sync staleness fix depends on score ordering being correct)
B ─► F (unmatch/block notifications depend on event infrastructure from B)
D ─► G (account deletion atomicity depends on cache invalidation being correct)
```

## Recommended Implementation Order

```
Phase 1 — Core Atomicity Fixes (P0)
  1. Batch A — Fix mutual match race by inlining swipe persistence         [independent]
  2. Batch B — Add match lifecycle event infrastructure                     [independent]

Phase 2 — Scoring Integrity (P0)
  3. Batch C — Reorder score computation and duplicate check in SwipeService [depends on A]
  4. Batch D — Propagate behavioral profile changes to score cache staleness [independent]

Phase 3 — Data Consistency (P1)
  5. Batch E — Fix quickSync genre accumulation + genre sync cache staleness [depends on C]
  6. Batch F — Add unmatch and block notification events                     [depends on B]

Phase 4 — Robustness (P1)
  7. Batch G — Atomic account deletion with exclusive lock                   [depends on D]
  8. Batch H — Store bidirectional match scores + randomize candidate pool   [independent]
```

---

## Phase 1 — Core Atomicity Fixes (P0)

### - [x] Batch A — Fix Mutual Match Race by Inlining Swipe Persistence (completed 2026-03-17)

**Priority:** CRITICAL
**Risk:** The current `recordSwipe()` flow checks `hasUserLiked()` in the outer `@Transactional` (line 93 of `SwipeService.java`) but persists the swipe via `SwipePersistenceHelper.saveSwipe()` in a `REQUIRES_NEW` transaction (line 116). Because `REQUIRES_NEW` suspends the outer transaction, the INSERT commits independently. Under concurrent simultaneous likes (A likes B while B likes A), both threads can read `hasUserLiked() = false` before either's INSERT is visible, causing **neither thread to detect the mutual match**. This is the most critical bug in the codebase — users who like each other may never be matched.

The Batch C concurrency fix (moving `hasUserLiked` before INSERT) does not fully solve this because the `REQUIRES_NEW` breaks the transactional visibility guarantee the comment on line 86-89 claims to provide.

**Affected files:**
- `services/matching/SwipeService.java`
- `services/matching/SwipePersistenceHelper.java`

**What to do:**

1. **Remove `REQUIRES_NEW` from `SwipePersistenceHelper.saveSwipe()`.** Change the propagation to `REQUIRED` (default) so the swipe INSERT participates in the outer `recordSwipe()` transaction:
   ```java
   @Transactional(propagation = Propagation.REQUIRED)
   public UserSwipe saveSwipe(UserSwipe swipe) {
       return swipeRepository.save(swipe);
   }
   ```
   This means the `hasUserLiked()` check and the swipe INSERT now share the same transaction — the check sees a consistent snapshot and the INSERT is not visible to other transactions until `recordSwipe()` commits.

2. **Handle `DataIntegrityViolationException` differently.** With `REQUIRED` propagation, a duplicate constraint violation will now mark the outer transaction as rollback-only. Change `SwipeService.recordSwipe()` to handle this by catching `DataIntegrityViolationException` from the `saveSwipe()` call and explicitly checking for duplicate before any write:

   Replace the try-catch block at lines 115-119 with:
   ```java
   // The advisory hasUserSwipedOn check (line 81) handles the common case.
   // For the rare concurrent-duplicate race, the uk_swiper_swiped constraint
   // will cause a DataIntegrityViolationException at flush/commit time.
   // With REQUIRED propagation, this marks the outer tx rollback-only,
   // which is acceptable — DuplicateSwipeException will propagate and
   // GlobalExceptionHandler returns 409 without side effects.
   swipe = swipePersistenceHelper.saveSwipe(swipe);
   swipeRepository.flush(); // Force constraint check now, not at commit
   ```

   Wrap the entire body of `recordSwipe()` (after the advisory duplicate check) in a try-catch for `DataIntegrityViolationException`:
   ```java
   try {
       swipe = swipePersistenceHelper.saveSwipe(swipe);
       swipeRepository.flush();
   } catch (DataIntegrityViolationException ex) {
       throw new DuplicateSwipeException(swiper.getId(), swipedUserId);
   }
   ```

   **Note:** Because the tx is now rollback-only after a `DataIntegrityViolationException`, no match or behavioral update can proceed — this is correct behavior for a duplicate swipe.

3. **Add a `SELECT ... FOR UPDATE` to `hasUserLiked()` for serialization.** To fully close the race window, the mutual-like check must acquire a row-level lock that prevents the other thread's INSERT from completing until this transaction finishes:

   In `UserSwipeRepository.java`, add a new method:
   ```java
   @Query(value = "SELECT CASE WHEN COUNT(*) > 0 THEN true ELSE false END " +
          "FROM user_swipes us WHERE us.swiper_user_id = :swiperId " +
          "AND us.swiped_user_id = :swipedId " +
          "AND us.action IN ('like', 'super_like') FOR UPDATE",
          nativeQuery = true)
   boolean hasUserLikedForUpdate(@Param("swiperId") String swiperId,
                                  @Param("swipedId") String swipedId);
   ```

   Update `SwipeService.recordSwipe()` line 93 to use `hasUserLikedForUpdate()` instead of `hasUserLiked()`.

   **Why this works:** If A and B both call `recordSwipe()` concurrently:
   - Thread A calls `hasUserLikedForUpdate(B, A)` — finds no row, acquires no lock (no existing row to lock)
   - Thread A inserts swipe A→B
   - Thread B calls `hasUserLikedForUpdate(A, B)` — finds A→B (just inserted, visible within Thread A's tx? No — but Thread B has its own transaction and this is READ COMMITTED)

   Actually, at READ COMMITTED isolation, `FOR UPDATE` on a COUNT query will not fully serialize this. The correct approach is to use **advisory locks**:

   In `UserSwipeRepository.java`, add:
   ```java
   @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:pairKey))",
          nativeQuery = true)
   void acquirePairLock(@Param("pairKey") String pairKey);
   ```

   In `SwipeService.recordSwipe()`, before the `hasUserLiked` check (line 93), acquire a deterministic pair lock:
   ```java
   // Deterministic ordering ensures both A→B and B→A acquire the same lock
   String pairKey = Stream.of(swiper.getId(), swipedUserId)
           .sorted()
           .collect(Collectors.joining(":"));
   swipeRepository.acquirePairLock(pairKey);
   ```

   **Why this works:** `pg_advisory_xact_lock` is a PostgreSQL advisory lock tied to the current transaction. It blocks until the lock is available and releases when the transaction commits. By using a deterministic key (sorted user IDs), both A→B and B→A swipes acquire the **same** lock, serializing the entire check-then-insert sequence. The second thread will block until the first completes, then its `hasUserLiked` will correctly see the first thread's committed swipe.

4. **Update `SwipePersistenceHelper` javadoc** to reflect that it no longer uses `REQUIRES_NEW`. It is retained as a separate bean for separation of concerns but no longer provides transaction isolation.

**Test — `IntegrityBatchAMutualMatchRaceTest`:**
Write a test class with 5 tests:
1. **Sequential mutual like creates match** — User A likes B, then B likes A. Verify exactly one `Match` exists.
2. **Concurrent mutual like creates exactly one match** — Use `CountDownLatch` to synchronize two threads. Both call `recordSwipe()` simultaneously. Verify exactly one `Match` exists (not zero, not two).
3. **Concurrent duplicate swipe throws `DuplicateSwipeException`** — Two threads try to record the same A→B swipe simultaneously. Verify exactly one succeeds and the other gets `DuplicateSwipeException`.
4. **Advisory lock prevents zero-match race** — Same as test 2 but with 10 iterations to stress-test. Each iteration uses fresh users. Verify match count is exactly 1 in every iteration.
5. **Block action does not interfere with advisory lock** — A blocks B, then B likes A. Verify no match is created and no deadlock occurs.

**Verification:**
- Two simultaneous mutual likes always produce exactly one match (never zero, never two).
- `DuplicateSwipeException` is thrown cleanly for concurrent duplicate swipes.
- Application starts without errors.
- Run the existing test suite — all tests pass.

---

### - [x] Batch B — Add Match Lifecycle Event Infrastructure (completed 2026-03-17)

**Priority:** HIGH
**Risk:** When a mutual match is created (lines 135-166 of `SwipeService.java`), only the swiping user gets the `SwipeResult` with `MatchDetails` in the HTTP response. The matched-against user has **no notification mechanism** — no event, no push notification, no WebSocket. They only discover the match by polling `/api/v1/matching/matches`. Similarly, when a user unmatches (`MatchService.unmatch()`), no event is published — the other party has no way to know they were unmatched. This is a fundamental product gap that also prevents future features like push notifications and real-time UI updates.

**Affected files:**
- `events/SwipeRecordedEvent.java` (reference — existing event pattern)
- `events/MatchCreatedEvent.java` (**new file**)
- `events/MatchUnmatchedEvent.java` (**new file**)
- `events/UserBlockedEvent.java` (**new file**)
- `services/matching/SwipeService.java`
- `services/matching/MatchService.java`

**What to do:**

1. **Create `MatchCreatedEvent` record** in `events/`:
   ```java
   package com.example.dating.events;

   /**
    * Published after a mutual match is created and the swipe transaction commits.
    * Consumers can use this to trigger push notifications, update real-time feeds, etc.
    *
    * @param matchId     ID of the newly created Match entity
    * @param userAId     ID of the first user in the match (alphabetically first)
    * @param userBId     ID of the second user in the match
    * @param matchScore  Overall match score at time of match creation
    * @param matchSource Source of the match ("mutual_swipe" or "super_like")
    * @param initiatorId ID of the user whose swipe triggered the match
    */
   public record MatchCreatedEvent(
           String matchId,
           String userAId,
           String userBId,
           Double matchScore,
           String matchSource,
           String initiatorId) {
   }
   ```

2. **Create `MatchUnmatchedEvent` record** in `events/`:
   ```java
   package com.example.dating.events;

   /**
    * Published after a user unmatches. The unmatched party can be notified
    * to update their match list in real-time.
    *
    * @param matchId          ID of the Match entity
    * @param unmatchedByUserId ID of the user who initiated the unmatch
    * @param otherUserId       ID of the user who was unmatched
    */
   public record MatchUnmatchedEvent(
           String matchId,
           String unmatchedByUserId,
           String otherUserId) {
   }
   ```

3. **Create `UserBlockedEvent` record** in `events/`:
   ```java
   package com.example.dating.events;

   /**
    * Published after a user blocks another user. Consumers can use this to
    * remove existing matches, clear chat history, etc.
    *
    * @param blockerId  ID of the user who performed the block
    * @param blockedId  ID of the user who was blocked
    */
   public record UserBlockedEvent(
           String blockerId,
           String blockedId) {
   }
   ```

4. **Publish `MatchCreatedEvent` in `SwipeService.recordSwipe()`** after the match is created. Add after line 166 (after `log.info("Match created with ID: {}", match.getId())`):
   ```java
   eventPublisher.publishEvent(new MatchCreatedEvent(
           match.getId(),
           match.getUserA().getId(),
           match.getUserB().getId(),
           computedMatchScore,
           matchSource.getValue(),
           swiper.getId()));
   ```

5. **Publish `UserBlockedEvent` in `SwipeService.recordSwipe()`** in the block action branch. Add before the return statement on line 124:
   ```java
   eventPublisher.publishEvent(new UserBlockedEvent(swiper.getId(), swipedUserId));
   ```

6. **Publish `MatchUnmatchedEvent` in `MatchService.doUnmatch()`** after the save. This requires injecting `ApplicationEventPublisher` into `MatchService`:
   - Add `private final ApplicationEventPublisher eventPublisher;` to the constructor deps
   - After `matchRepository.save(match)` in `doUnmatch()`, add:
     ```java
     String otherUserId = match.getUserA().getId().equals(requestingUserId)
             ? match.getUserB().getId()
             : match.getUserA().getId();
     eventPublisher.publishEvent(new MatchUnmatchedEvent(matchId, requestingUserId, otherUserId));
     ```

7. **Set `unmatchedBy` field on the `Match` entity** in `doUnmatch()`. Currently this field exists on the entity (line 145 of `Match.java`) but is never populated. Add before `matchRepository.save(match)`:
   ```java
   match.setUnmatchedAt(LocalDateTime.now());
   match.setUnmatchedBy(requestingUserId);
   ```

**Test — `IntegrityBatchBMatchEventTest`:**
Write a test class with 5 tests:
1. **`MatchCreatedEvent` published on mutual like** — Record A→B like then B→A like. Capture events via a `@Component` test listener. Verify one `MatchCreatedEvent` is published with correct `matchId`, `userAId`, `userBId`, and `initiatorId`.
2. **`MatchCreatedEvent` includes super_like source** — A super_likes B who already liked A. Verify `matchSource` is `"super_like"`.
3. **`MatchUnmatchedEvent` published on unmatch** — Create a match, then unmatch. Verify `MatchUnmatchedEvent` is published with correct `unmatchedByUserId` and `otherUserId`.
4. **`UserBlockedEvent` published on block** — A blocks B. Verify `UserBlockedEvent` is published with correct `blockerId` and `blockedId`.
5. **`Match.unmatchedBy` and `unmatchedAt` populated** — Unmatch a match and verify the `unmatchedBy` field equals the requesting user's ID and `unmatchedAt` is not null.

**Verification:**
- `MatchCreatedEvent` fires after every mutual match.
- `MatchUnmatchedEvent` fires after every unmatch.
- `UserBlockedEvent` fires after every block.
- `Match.unmatchedBy` and `unmatchedAt` are populated on unmatch.
- Events are published within the transaction (they can use `@TransactionalEventListener(AFTER_COMMIT)` for consumers).
- Run the existing test suite — all tests pass.

---

## Phase 2 — Scoring Integrity (P0)

### - [x] Batch C — Reorder Score Computation and Duplicate Check in `SwipeService`

**Priority:** HIGH
**Risk:** In `SwipeService.recordSwipe()`, the full match score is computed at line 77 (`recommendationService.getMatchScore()`) **before** the duplicate check at line 81 (`swipeRepository.hasUserSwipedOn()`). For rapid-fire duplicate swipes, this wastes a full scoring round-trip (including potential DB reads, cache lookups, and multi-dimensional scoring calculations) per duplicate. Additionally, `getMatchScore()` calls `MatchScoringService.calculateScore()` which may trigger multiple repository reads without the `GenrePrefetchContext` being set, falling back to per-candidate DB queries.

**Depends on:** Batch A (swipe atomicity must be correct before reordering)

**Affected files:**
- `services/matching/SwipeService.java`

**What to do:**

1. **Move the duplicate check before score computation.** Reorder `recordSwipe()` so that the cheap `hasUserSwipedOn()` check (line 81) happens before the expensive `getMatchScore()` call (line 77):

   ```java
   @Transactional
   public SwipeResult recordSwipe(User swiper, String swipedUserId, String action,
                                   Double matchScore, String platform) {
       // 1. Validate action (unchanged)
       String normalizedAction = action != null ? action.toLowerCase() : "";
       if (!VALID_ACTIONS.contains(normalizedAction)) { ... }

       // 2. Load users (unchanged)
       UserEntity swipedUserEntity = userRepository.findById(swipedUserId)...
       UserEntity swiperEntity = userRepository.findById(swiper.getId())...

       // 3. Fast-path duplicate check — BEFORE score computation
       if (swipeRepository.hasUserSwipedOn(swiper.getId(), swipedUserId)) {
           throw new DuplicateSwipeException(swiper.getId(), swipedUserId);
       }

       // 4. Advisory pair lock (from Batch A)
       // ...

       // 5. Compute score server-side — AFTER duplicate check
       MatchScore computed = recommendationService.getMatchScore(swiper, swipedUserId);
       double computedMatchScore = computed.getOverallScore();

       // 6. Mutual like check + swipe persistence (unchanged from Batch A)
       // ...
   }
   ```

2. **Skip score computation for block actions.** If the action is `"block"`, the score is never used (the block branch returns early at line 123 without using `computedMatchScore`). Move the score computation inside a conditional:

   ```java
   double computedMatchScore = 0.0;
   if (!"block".equals(normalizedAction)) {
       MatchScore computed = recommendationService.getMatchScore(swiper, swipedUserId);
       computedMatchScore = computed.getOverallScore();
   }
   ```

   This avoids scoring entirely for blocks, which have no behavioral update and no match creation.

3. **Skip score computation for pass actions when no behavioral profile exists.** The score is stored on the swipe as `matchScoreAtSwipe` and used by `BehavioralProfileService` to update `effectiveScoreThreshold`. If the swiper has fewer than 5 likes (cold start), the behavioral component returns 50.0 regardless and the EMA update has minimal impact. Consider gating the score computation:

   This is an optimization — do NOT implement if it adds complexity. The score on the swipe record is useful for analytics even for passes.

**Test — `IntegrityBatchCScoreOrderingTest`:**
Write a test class with 4 tests:
1. **Duplicate swipe does not trigger score computation** — Mock `MatchRecommendationService.getMatchScore()` and verify it is NOT called when the user has already swiped on the target.
2. **Block action does not trigger score computation** — Record a block swipe and verify `getMatchScore()` is NOT called.
3. **Like action triggers score computation** — Record a like and verify `getMatchScore()` IS called exactly once.
4. **Score is stored on swipe record** — Record a like, fetch the `UserSwipe` entity, and verify `matchScoreAtSwipe` matches the server-computed score.

**Verification:**
- Duplicate swipes return `DuplicateSwipeException` without calling the scoring engine.
- Block actions do not call the scoring engine.
- Likes and super_likes still have correct server-side scores.
- Run the existing test suite — all tests pass.

---

### - [x] Batch D — Propagate Behavioral Profile Changes to Score Cache Staleness

**Priority:** HIGH
**Risk:** The score cache freshness check (`isCacheFresh()` in `MatchRecommendationService`, line 417) compares `computedAt` against `max(userA.updatedAt, userB.updatedAt)`. However, when `BehavioralProfileService.doUpdateAfterSwipe()` modifies a user's behavioral profile (genre weights, average liked age, effective score threshold), it updates `UserBehavioralProfile.updatedAt` but does NOT update `UserEntity.updatedAt`. This means the cached score remains "fresh" despite the behavioral component having changed significantly. Over 50+ swipes, a user's behavioral profile evolves substantially, but cached scores against all candidates remain stale — the behavioral dimension (up to 40% of the final score) is frozen at the values from the first computation.

**Affected files:**
- `services/matching/BehavioralProfileService.java`
- `models/user/common/dao/UserEntity.java`
- `repositories/UserJpaRepository.java`

**What to do:**

1. **Add a `behavioralProfileVersion` counter to `UserEntity`.** This is more targeted than bumping `updatedAt` (which would invalidate ALL cached scores on every profile change, including non-behavioral ones like name edits):
   ```java
   @Column(name = "behavioral_profile_version", nullable = false)
   @Builder.Default
   private Long behavioralProfileVersion = 0L;
   ```

2. **Increment `behavioralProfileVersion` in `BehavioralProfileService.doUpdateAfterSwipe()`.** After the `behavioralProfileRepository.save(profile)` call, bump the version on the user entity:
   ```java
   // Bump the user's behavioral version so that cached match scores
   // (which blend behavioral weights at up to 40%) are correctly
   // invalidated on the next feed request.
   userRepository.incrementBehavioralProfileVersion(swiperId);
   ```

3. **Add `incrementBehavioralProfileVersion()` to `UserJpaRepository`:**
   ```java
   @Modifying
   @Query("UPDATE UserEntity u SET u.behavioralProfileVersion = u.behavioralProfileVersion + 1 WHERE u.id = :userId")
   void incrementBehavioralProfileVersion(@Param("userId") String userId);
   ```

4. **Update `isCacheFresh()` in `MatchRecommendationService`** to also consider the behavioral profile version. Change the `CandidateData` record to include the requesting user's `behavioralProfileVersion`:
   ```java
   record CandidateData(
       UserEntity userEntity,
       List<UserEntity> candidates,
       Map<String, UserMatchScore> cachedScores,
       Map<String, List<UserGenrePreference>> genrePrefs,
       double effectiveMinScore,
       LocalDateTime staleAfter,
       long userBehavioralVersion,  // NEW
       Map<String, Double> distanceCache,
       Set<String> userGenderTokens
   ) {}
   ```

   Update `isCacheFresh()` to check the behavioral version. Add a `behavioralVersion` column to `UserMatchScore` (or reuse the `computedAt` approach by checking if the user's behavioral profile was updated after the cache entry):

   **Simpler approach:** Just bump `UserEntity.updatedAt` when the behavioral profile changes. This invalidates all cached scores for this user, but since behavioral updates happen at most once per swipe (and scores are recomputed lazily on the next feed request), the cost is acceptable:

   Replace steps 1-3 above with a single `@Modifying` query in `UserJpaRepository`:
   ```java
   @Modifying
   @Query("UPDATE UserEntity u SET u.updatedAt = CURRENT_TIMESTAMP WHERE u.id = :userId")
   void touchUpdatedAt(@Param("userId") String userId);
   ```

   Call it from `BehavioralProfileService.doUpdateAfterSwipe()` after saving the profile:
   ```java
   profile = behavioralProfileRepository.save(profile);
   behavioralScoreCalculator.invalidateCache(profile.getId());
   // Bump UserEntity.updatedAt so cached match scores are correctly invalidated.
   userRepository.touchUpdatedAt(swiperId);
   ```

   **Trade-off:** This invalidates ALL cached scores for this user (not just behavioral-sensitive ones). But since the behavioral component can contribute up to 40% of the final score, all cached scores are potentially stale anyway. The next feed request will recompute scores lazily, and the two-tier cache strategy (Batch D scalability) limits synchronous recomputation to one page of candidates.

**Test — `IntegrityBatchDCacheInvalidationTest`:**
Write a test class with 5 tests:
1. **Behavioral profile update bumps `UserEntity.updatedAt`** — Create a user, record the initial `updatedAt`. Process a swipe event. Verify `updatedAt` has advanced.
2. **Cached score is stale after behavioral update** — Compute and cache a score for A→B. Process a swipe event for user A (which updates behavioral profile). Verify the cached score is no longer "fresh" per `isCacheFresh()`.
3. **Score is recomputed on next feed request after behavioral update** — Fetch potential matches (populates cache). Process a swipe event (invalidates cache). Fetch potential matches again. Verify the new score differs from the cached score (behavioral component changed).
4. **`touchUpdatedAt` does not update `@Version`** — Verify that calling `touchUpdatedAt()` does not increment the `@Version` field (it uses a `@Modifying @Query`, not `save()`).
5. **No spurious invalidation for pass swipes** — Process a "pass" swipe event. Verify `UserEntity.updatedAt` IS still bumped (passes update `totalPasses` on the behavioral profile, which changes the score threshold).

**Verification:**
- After a user swipes (like or pass), their `updatedAt` is bumped.
- Cached match scores against this user are correctly identified as stale on the next feed request.
- The next feed request recomputes scores with the updated behavioral profile.
- Run the existing test suite — all tests pass.

---

## Phase 3 — Data Consistency (P1)

### - [x] Batch E — Fix `quickSync` Genre Accumulation and Genre Sync Cache Staleness

**Priority:** MEDIUM
**Risk:** Two issues:

1. **`quickSyncUserGenrePreferences()` (line 149 of `SpotifyGenreSyncService.java`) is additive** — it calls `extractAndSaveGenrePreferences()` without clearing old Spotify-derived preferences first. Over time, this accumulates stale genre preferences that were removed from the user's Spotify listening. A user who listened to K-pop six months ago but no longer does will still have K-pop genre preferences affecting their match scores.

2. **Genre sync completion does not invalidate cached match scores.** When `syncUserGenrePreferences()` or `quickSyncUserGenrePreferences()` updates a user's genre preferences, the change affects the music score dimension (which can be 30-80% of the final score depending on `musicMatchImportance`). But no mechanism bumps `UserEntity.updatedAt`, so cached scores remain "fresh" despite the music input data having changed.

**Depends on:** Batch C (score ordering must be correct before changing genre sync behavior)

**Affected files:**
- `services/matching/SpotifyGenreSyncService.java`
- `services/matching/GenreExtractionService.java`

**What to do:**

1. **Change `quickSyncUserGenrePreferences()` to use `replaceSpotifyPreferences()`** instead of `extractAndSaveGenrePreferences()`. This ensures old genres are cleared before adding new ones:

   Replace lines 166-170 in `SpotifyGenreSyncService.java`:
   ```java
   // Quick sync now replaces (not appends) to prevent genre accumulation.
   // Only uses short_term data for speed, but still atomically replaces
   // all SPOTIFY_DERIVED preferences to avoid stale genre buildup.
   genreExtractionService.replaceSpotifyPreferences(user, genres);
   ```

   Update the return statement to match `replaceSpotifyPreferences()` return type:
   ```java
   return genreExtractionService.replaceSpotifyPreferences(user, genres);
   ```

   Remove the comment on line 165 (`// Quick sync is additive (no clear) — intentional for speed.`) — this was intentional but incorrect as a long-term strategy.

2. **Bump `UserEntity.updatedAt` after genre sync.** In both `syncUserGenrePreferences()` and `quickSyncUserGenrePreferences()`, after the genre extraction call, bump the user's `updatedAt`:

   In `SpotifyGenreSyncService`, inject `UserJpaRepository` (not the custom `UserRepository`):
   ```java
   private final UserJpaRepository userJpaRepository;
   ```

   After the `replaceSpotifyPreferences()` call in `syncUserGenrePreferences()`:
   ```java
   int count = genreExtractionService.replaceSpotifyPreferences(user, allGenres);
   userJpaRepository.touchUpdatedAt(user.getId()); // Invalidate cached match scores
   return count;
   ```

   Same in `quickSyncUserGenrePreferences()`.

   **Note:** `touchUpdatedAt()` was added in Batch D. If Batch D is not yet complete, add it here instead.

**Test — `IntegrityBatchEGenreSyncTest`:**
Write a test class with 5 tests:
1. **`quickSync` replaces genres instead of accumulating** — Sync genres ["rock", "pop"]. Quick-sync with ["jazz"]. Verify user has only "jazz" as Spotify-derived preferences, not "rock", "pop", "jazz".
2. **`quickSync` atomicity on failure** — Mock genre extraction to throw. Verify original preferences are retained (transaction rolled back).
3. **Genre sync bumps `UserEntity.updatedAt`** — Record `updatedAt` before sync. Run `syncUserGenrePreferences()`. Verify `updatedAt` advanced.
4. **Quick sync also bumps `UserEntity.updatedAt`** — Same as test 3 but for `quickSyncUserGenrePreferences()`.
5. **Cached match scores stale after genre sync** — Compute and cache score for A→B. Run genre sync for user A. Verify cached score is stale (via `isCacheFresh()` or by checking `computedAt < updatedAt`).

**Verification:**
- Quick sync no longer accumulates genres over time.
- Both sync methods invalidate cached match scores.
- Run the existing test suite — all tests pass.

---

### - [x] Batch F — Add Unmatch and Block Notification Events (completed 2026-03-18)

**Priority:** MEDIUM
**Risk:** Building on the event infrastructure from Batch B, this batch adds **listeners** that react to match lifecycle events. Without listeners, the events from Batch B are published but not consumed. This batch adds the actual side-effect handlers.

**Depends on:** Batch B (event classes and publication must exist)

**Affected files:**
- `services/matching/MatchService.java`
- `services/matching/SwipeService.java`
- `services/matching/MatchLifecycleListener.java` (**new file**)

**What to do:**

1. **Create `MatchLifecycleListener`** in `services/matching/`:
   ```java
   package com.example.dating.services.matching;

   import com.example.dating.events.MatchCreatedEvent;
   import com.example.dating.events.MatchUnmatchedEvent;
   import com.example.dating.events.UserBlockedEvent;
   import com.example.dating.repositories.MatchRepository;
   import lombok.RequiredArgsConstructor;
   import lombok.extern.slf4j.Slf4j;
   import org.springframework.scheduling.annotation.Async;
   import org.springframework.stereotype.Component;
   import org.springframework.transaction.event.TransactionPhase;
   import org.springframework.transaction.event.TransactionalEventListener;

   /**
    * Reacts to match lifecycle events. Currently logs for future push-notification
    * and real-time feed integration. When a WebSocket or push notification service
    * is added, this listener is the integration point.
    */
   @Component
   @RequiredArgsConstructor
   @Slf4j
   public class MatchLifecycleListener {

       private final MatchRepository matchRepository;

       @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
       @Async
       public void onMatchCreated(MatchCreatedEvent event) {
           log.info("Match created: {} between {} and {} (source: {}, initiator: {})",
                   event.matchId(), event.userAId(), event.userBId(),
                   event.matchSource(), event.initiatorId());
           // TODO: Send push notification to the non-initiator user
           // TODO: Update real-time feed via WebSocket
       }

       @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
       @Async
       public void onMatchUnmatched(MatchUnmatchedEvent event) {
           log.info("Match {} unmatched by user {} (other user: {})",
                   event.matchId(), event.unmatchedByUserId(), event.otherUserId());
           // TODO: Send push notification to the unmatched user
           // TODO: Update real-time feed via WebSocket
       }

       @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
       @Async
       public void onUserBlocked(UserBlockedEvent event) {
           log.info("User {} blocked user {}", event.blockerId(), event.blockedId());
           // TODO: If an active match exists between these users, auto-unmatch
           // TODO: Clear chat history between these users
       }
   }
   ```

2. **Auto-unmatch on block.** In `onUserBlocked()`, check for an existing active match and unmatch it:
   ```java
   @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
   @Async
   public void onUserBlocked(UserBlockedEvent event) {
       log.info("User {} blocked user {}", event.blockerId(), event.blockedId());
       matchRepository.findMatchBetweenUsers(event.blockerId(), event.blockedId())
               .ifPresent(match -> {
                   if (match.getStatus() == MatchStatus.ACTIVE) {
                       // Use MatchService to handle unmatch with proper event publishing
                       matchService.unmatch(match.getId(), event.blockerId());
                   }
               });
   }
   ```

   This requires injecting `MatchService` into `MatchLifecycleListener`. Use `@Lazy` to avoid circular dependencies if needed.

**Test — `IntegrityBatchFLifecycleListenerTest`:**
Write a test class with 4 tests:
1. **`MatchLifecycleListener` receives `MatchCreatedEvent`** — Create a mutual match, verify the listener logs the event (use `@SpyBean` or a test event collector).
2. **Block auto-unmatches active match** — Create a match between A and B. A blocks B. Verify the match status changes to `UNMATCHED`.
3. **Block does not fail if no match exists** — A blocks B (no prior match). Verify no exception is thrown.
4. **`MatchUnmatchedEvent` fires after block-triggered unmatch** — A and B match. A blocks B. Verify both `UserBlockedEvent` and `MatchUnmatchedEvent` fire (the block triggers unmatch which triggers its own event).

**Verification:**
- Match lifecycle events are consumed by the listener.
- Blocking a matched user auto-unmatches them.
- No circular event loops (block → unmatch → event, but no further chain).
- Run the existing test suite — all tests pass.

---

## Phase 4 — Robustness (P1)

### - [x] Batch G — Atomic Account Deletion with Exclusive Lock

**Priority:** MEDIUM
**Risk:** `UserServiceImpl.deleteAccount()` manually deletes matching entities in FK order (swipes → matches → scores → genres → profiles → user) within a single `@Transactional`. But there is no exclusive lock preventing concurrent swipe or match operations targeting this user. A swipe being processed by another thread could insert a new `UserSwipe` or `Match` between the delete steps, causing either:
1. A foreign key violation if the match is inserted after matches are deleted but before the user is deleted
2. An orphaned swipe record if the swipe is inserted after swipes are deleted but before the user is deleted

**Depends on:** Batch D (cache invalidation must be correct so deletion doesn't leave stale cache pointing at deleted user)

**Affected files:**
- `services/impl/UserServiceImpl.java`
- `repositories/UserJpaRepository.java`

**What to do:**

1. **Acquire a `SELECT ... FOR UPDATE` lock on the `UserEntity` row at the start of `deleteAccount()`.** This prevents any concurrent transaction from reading this user's row (for swipe/match creation) until the deletion completes:

   Add to `UserJpaRepository`:
   ```java
   @Lock(LockModeType.PESSIMISTIC_WRITE)
   @Query("SELECT u FROM UserEntity u WHERE u.id = :userId")
   Optional<UserEntity> findByIdForUpdate(@Param("userId") String userId);
   ```

   In `deleteAccount()`, replace the `userRepository.findById(userId)` call with:
   ```java
   UserEntity userEntity = userJpaRepository.findByIdForUpdate(userId)
           .orElseThrow(() -> new UserNotFoundException("User not found"));
   ```

   **Note:** This requires `deleteAccount()` to use `UserJpaRepository` (JPA) instead of the custom `UserRepository` for the initial lookup. The custom repository can still be used for the final `deleteById()`.

2. **Add `UserJpaRepository` injection to `UserServiceImpl`** if not already present. Currently the class uses the custom `UserRepository` (`postgres/UserRepository`). Add:
   ```java
   private final UserJpaRepository userJpaRepository;
   ```

3. **Invalidate all cached scores involving this user** before deleting the score records:
   ```java
   // Delete matching entities in FK-safe order
   userSwipeRepository.deleteAllInvolvingUser(userId);
   matchRepository.deleteAllByUserId(userId);
   userMatchScoreRepository.deleteAllInvolvingUser(userId);
   userGenrePreferenceRepository.deleteByUserId(userId);
   userBehavioralProfileRepository.deleteByUserId(userId);
   ```

   The cached scores in `user_match_scores` are already deleted by `userMatchScoreRepository.deleteAllInvolvingUser(userId)`. The Caffeine cache in `BehavioralScoreCalculator` should also be invalidated:
   ```java
   // Invalidate in-memory behavioral score cache if a profile existed
   userBehavioralProfileRepository.findByUserId(userId)
           .ifPresent(profile -> behavioralScoreCalculator.invalidateCache(profile.getId()));
   userBehavioralProfileRepository.deleteByUserId(userId);
   ```

   This requires injecting `BehavioralScoreCalculator` into `UserServiceImpl`.

**Test — `IntegrityBatchGAccountDeletionTest`:**
Write a test class with 4 tests:
1. **Deletion acquires exclusive lock** — Verify that `findByIdForUpdate` is called (structural test using reflection or `@SpyBean`).
2. **Concurrent swipe during deletion blocks** — Start account deletion in one thread (with a brief sleep after the lock acquisition). Attempt a swipe against the same user in another thread. Verify the swipe either blocks until deletion completes or fails with `UserNotFoundException`.
3. **Deletion cleans all related entities** — Create a user with swipes, matches, scores, genre prefs, and behavioral profile. Delete the account. Verify all related tables have zero rows for this user.
4. **Behavioral cache invalidated after deletion** — Create a user with a behavioral profile. Verify the Caffeine cache is populated. Delete the account. Verify the cache entry is invalidated.

**Verification:**
- Account deletion holds an exclusive row lock for the duration.
- Concurrent operations against a deleted user do not produce orphaned records or FK violations.
- All caches are invalidated.
- Run the existing test suite — all tests pass.

---

### - [x] Batch H — Store Bidirectional Match Scores and Randomize Candidate Pool

**Priority:** LOW
**Risk:** Two independent issues:

1. **Directional score asymmetry not stored on Match.** Score A→B ≠ B→A (because `musicMatchImportance` and behavioral profile are per-user). But `Match.matchScore` (line 89 of `Match.java`) stores only one score — from the perspective of the user whose swipe triggered the match creation. When user B views the match, they see user A's directional score, not their own. This is misleading because B may have a very different compatibility perspective.

2. **500-candidate hard cap with deterministic ordering.** `fetchCandidateData()` caps candidates at 500 via `PageRequest.of(0, 500)` (line 162 of `MatchRecommendationService.java`). The JPQL query returns results in database insertion order (no `ORDER BY`), so the same users beyond position 500 are systematically excluded from every recommendation request. In a dense market, this creates a "buried profile" problem where later registrants never appear in feeds.

**Affected files:**
- `models/matching/dao/Match.java`
- `services/matching/SwipeService.java`
- `services/matching/MatchService.java`
- `services/matching/MatchRecommendationService.java`
- `repositories/UserJpaRepository.java`

**What to do:**

1. **Add `matchScoreB` field to `Match` entity.** This stores the reverse-direction score:
   ```java
   /**
    * Match score from userB's perspective (B→A).
    * Stored separately because scoring is directional (musicMatchImportance differs per user).
    */
   @Column(name = "match_score_b")
   private Double matchScoreB;
   ```

   Rename the existing `matchScore` field to `matchScoreA` for clarity (or keep as `matchScore` and document that it's the A→B direction). If renaming, update all references.

2. **Compute and store the reverse score on match creation.** In `SwipeService.recordSwipe()`, after detecting a mutual like, compute the reverse direction score before creating the match:
   ```java
   if (mutualLike) {
       // Compute reverse direction score (B→A perspective)
       MatchScore reverseScore = recommendationService.getMatchScore(
               userMapper.toDomain(swipedUserEntity), swiper.getId());
       double reverseMatchScore = reverseScore.getOverallScore();

       Match match = matchService.createMatch(
               swiperEntity, swipedUserEntity,
               computedMatchScore, reverseMatchScore,
               matchSource);
       // ...
   }
   ```

   Update `MatchService.createMatch()` to accept both scores:
   ```java
   public Match createMatch(UserEntity userA, UserEntity userB,
                             Double matchScoreA, Double matchScoreB,
                             MatchSource matchSource) {
       // ... existing logic ...
       // Add matchScoreB to the native INSERT
   }
   ```

   Update `MatchRepository.insertMatchIfAbsent()` native query to include `match_score_b`.

3. **Randomize candidate selection.** Add `ORDER BY RANDOM()` to the candidate query to prevent systematic exclusion:

   In `UserJpaRepository`, update the `findCandidateUsers` query to randomize:
   ```java
   @Query("SELECT u FROM UserEntity u " +
          "WHERE u.id <> :userId " +
          "AND u.registrationStage = :stage " +
          "AND u.id NOT IN :excludedIds " +
          "AND (u.privacySettings IS NULL OR u.privacySettings.profileVisibility <> 'HIDDEN') " +
          "AND (:minDob IS NULL OR u.dateOfBirth >= :minDob) " +
          "AND (:maxDob IS NULL OR u.dateOfBirth <= :maxDob) " +
          "ORDER BY FUNCTION('RANDOM')")
   Page<UserEntity> findCandidateUsers(...);
   ```

   **PostgreSQL note:** JPQL `FUNCTION('RANDOM')` maps to PostgreSQL `RANDOM()`. Verify this works with the current JPA provider. If not, use a native query:
   ```java
   @Query(value = "SELECT * FROM users u " +
          "WHERE u.id <> :userId " +
          "AND u.registration_stage = :stage " +
          "AND u.id NOT IN :excludedIds " +
          "AND (:minDob IS NULL OR u.dob >= :minDob) " +
          "AND (:maxDob IS NULL OR u.dob <= :maxDob) " +
          "ORDER BY RANDOM()",
          nativeQuery = true)
   Page<UserEntity> findCandidateUsersRandomized(...);
   ```

   **Performance note:** `ORDER BY RANDOM()` on a large table requires a full sequential scan. For tables with > 100K rows, consider using `TABLESAMPLE BERNOULLI(percentage)` or a pre-computed random column with an index. For the current scale (likely < 50K users), `ORDER BY RANDOM()` is acceptable.

4. **Expose the correct directional score in `MatchDtoMapper`** when building the match response for a specific user. The mapper should return `matchScoreA` if the requesting user is userA, or `matchScoreB` if the requesting user is userB:
   ```java
   public MatchResponseDto toDto(Match match, String requestingUserId) {
       Double myScore = match.getUserA().getId().equals(requestingUserId)
               ? match.getMatchScore()   // A→B
               : match.getMatchScoreB(); // B→A
       // ... build DTO with myScore ...
   }
   ```

**Test — `IntegrityBatchHBidirectionalScoreTest`:**
Write a test class with 5 tests:
1. **Match stores both directional scores** — A likes B, B likes A (creating match). Verify `Match.matchScore` and `Match.matchScoreB` are both non-null and may differ.
2. **`MatchDtoMapper` returns correct directional score** — Create a match. Map it for userA and verify score equals `matchScoreA`. Map it for userB and verify score equals `matchScoreB`.
3. **Native INSERT includes `match_score_b`** — Verify the `insertMatchIfAbsent` query includes the `match_score_b` column (structural test).
4. **Candidate query includes randomization** — Call `findPotentialMatches()` twice for the same user. Verify the candidate order is not identical (may require multiple iterations due to randomness — use 5 iterations and verify at least one differs).
5. **Backward compatibility — existing matches with null `matchScoreB`** — Load a match that was created before this batch (no `matchScoreB`). Verify the DTO mapper handles `null matchScoreB` gracefully (falls back to `matchScore` or returns null).

**Verification:**
- Matches store bidirectional scores.
- Users see their own perspective's score.
- Candidate pool is randomized across requests.
- Existing matches with null `matchScoreB` don't break.
- Run the existing test suite — all tests pass.

---

## Progress Tracker

| Batch | Status | Completion Date | Notes |
|---|---|---|---|
| A — Mutual match race fix | `[ ]` | — | — |
| B — Match lifecycle events | `[ ]` | — | — |
| C — Score computation ordering | `[ ]` | — | — |
| D — Behavioral cache invalidation | `[ ]` | — | — |
| E — Genre sync staleness | `[ ]` | — | — |
| F — Lifecycle event listeners | `[ ]` | — | — |
| G — Account deletion atomicity | `[ ]` | — | — |
| H — Bidirectional scores + randomization | `[x]` | 2026-03-18 | — |
