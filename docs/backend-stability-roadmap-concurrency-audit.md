# Backend Stability Fix Roadmap — Concurrency & Race Condition Audit

## Purpose

This document tracks the concurrency hardening refactor for the dating app Spring Boot backend. It addresses issues identified during a full audit covering race conditions, missing locking strategies, non-atomic check-then-act patterns, transaction isolation gaps, and concurrent data corruption risks.

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

## Verified Entity Locking Status (Pre-Audit)

| Entity | `@Version` | DB Unique Constraints |
|---|---|---|
| `UserEntity` | **NO** | `email` (unique), `spotify_id` (unique) |
| `Match` | **NO** | `uq_match_user_pair (user_a_id, user_b_id)` |
| `UserSwipe` | **NO** | `uk_swiper_swiped (swiper_user_id, swiped_user_id)` |
| `UserBehavioralProfile` | **YES** | `user_id` (unique) |
| `UserMatchScore` | **NO** | `uk_user_match (user_id, matched_user_id)` |

---

## Dependency Graph

```
A (independent — optimistic locking: @Version on UserEntity and Match)
B (independent — registration + Spotify ID constraint violation handling)
A ─► C (swipe atomicity fix depends on UserEntity having @Version for token safety)
D (independent — genre sync transaction atomicity)
C ─► E (behavioral retry backoff benefits from swipe atomicity being correct)
F (independent — token version per-request validation)
```

## Recommended Implementation Order

```
Phase 1 — Optimistic Locking Foundation (P0)
  1. Batch A — Add @Version to UserEntity and Match entities               [independent]
  2. Batch B — Constraint violation error handling in AuthServiceImpl       [independent]

Phase 2 — Core Race Condition Fixes (P0)
  3. Batch C — Atomic mutual-match detection in SwipeService               [depends on A]
  4. Batch D — Genre sync transaction atomicity                            [independent]

Phase 3 — Resilience Improvements (P1)
  5. Batch E — Behavioral profile retry backoff + token refresh locking    [depends on C]
  6. Batch F — Per-request token version validation                        [independent]
```

---

## Phase 1 — Optimistic Locking Foundation (P0)

### - [x] Batch A — Add `@Version` to `UserEntity` and `Match` Entities (completed 2026-03-17)

**Priority:** CRITICAL
**Risk:** Neither `UserEntity` nor `Match` has a `@Version` field. Concurrent updates to these entities use last-write-wins semantics, silently discarding changes. For `UserEntity`, this means concurrent Spotify token refreshes can permanently break a user's Spotify connection by overwriting a rotated refresh token. For `Match`, concurrent updates to `status`, `conversationStarted`, or `messageCount` can produce inconsistent state (e.g., unmatched but `conversationStarted = true`).
**Affected files:**
- `models/user/common/dao/UserEntity.java`
- `models/matching/dao/Match.java`

**What to do:**

1. **Add `@Version` field to `UserEntity`.** After the `id` field (near the top of the class), add:
   ```java
   @Version
   @Column(name = "version")
   private Long version;
   ```
   Import `jakarta.persistence.Version`. Do NOT add a getter/setter if Lombok `@Getter`/`@Setter` or `@Data` is already on the class. If using `@Builder`, add `@Builder.Default` with value `0L` or ensure the builder handles it.

2. **Add `@Version` field to `Match`.** After the `id` field (line 53), add:
   ```java
   @Version
   @Column(name = "version")
   @Builder.Default
   private Long version = 0L;
   ```
   `Match` uses `@Builder`, so `@Builder.Default` is required to prevent null values on new instances.

3. **Verify that `MatchService.createMatch()` still works.** The `insertMatchIfAbsent()` native SQL query (in `MatchRepository`) uses `ON CONFLICT DO NOTHING` and does NOT go through JPA lifecycle, so the `@Version` column must be included in the native INSERT:
   - Open `MatchRepository.java` and find the `insertMatchIfAbsent()` native query.
   - Add `version` to the INSERT column list and VALUES list. Set it to `0`:
     ```sql
     INSERT INTO matches
       (id, user_a_id, user_b_id, match_score, status, conversation_started,
        match_source, matched_at, created_at, updated_at, message_count, version)
     VALUES
       (:id, :userAId, :userBId, :matchScore, 'active', false,
        :matchSource, :matchedAt, :createdAt, :updatedAt, 0, 0)
     ON CONFLICT (user_a_id, user_b_id) DO NOTHING
     ```
   - If this is not done, the native INSERT will fail because the `version` column will be `NOT NULL` (Hibernate's default for `@Version Long`).

4. **Add retry logic to `MatchService.unmatch()` and `MatchService.markConversationStarted()`.** These methods update `Match` fields and will now throw `OptimisticLockingFailureException` on concurrent access. Wrap each in a retry loop (2 attempts):
   ```java
   public void unmatch(String matchId, String userId) {
       for (int attempt = 1; attempt <= 2; attempt++) {
           try {
               doUnmatch(matchId, userId);
               return;
           } catch (OptimisticLockingFailureException e) {
               if (attempt == 2) throw e;
           }
       }
   }
   ```
   Extract the current method body into a private `doUnmatch()` / `doMarkConversationStarted()` method with `@Transactional`.

**Verification:**
- Application starts and Hibernate `ddl-auto: update` adds the `version` column to both tables (check logs for `ALTER TABLE` statements).
- Confirm that `POST /api/v1/matching/swipe` still creates matches correctly (the native INSERT includes the version column).
- Confirm that unmatching a match works and returns the updated match object.
- Run the existing test suite — all tests pass.

---

### - [x] Batch B — Constraint Violation Error Handling in `AuthServiceImpl` (completed 2026-03-17)

**Priority:** HIGH
**Risk:** `register()` (line 48) and `connectSpotify()` (line 217) both use check-then-act patterns that are vulnerable to concurrent requests. The DB has unique constraints on `email` and `spotify_id`, so the second insert throws `DataIntegrityViolationException` — but this exception is **not caught**, resulting in an unhandled 500 error instead of a clean domain-specific error response.
**Affected files:**
- `services/impl/AuthServiceImpl.java`
- `exceptions/GlobalExceptionHandler.java`

**What to do:**

1. **Wrap `userRepository.save(user)` in `register()` (line 72) with a `DataIntegrityViolationException` catch.** The check-then-act on `findByEmail()` (line 50) is an advisory fast path. The unique constraint is the real guard:
   ```java
   try {
       user = userRepository.save(user);
   } catch (DataIntegrityViolationException ex) {
       throw new EmailAlreadyExistsException("Email already registered");
   }
   ```
   Import `org.springframework.dao.DataIntegrityViolationException`.

2. **Wrap `userRepository.save(user)` in `connectSpotify()` (line 244) with a `DataIntegrityViolationException` catch.** The check-then-act on `findBySpotifyId()` (line 233) is also advisory:
   ```java
   try {
       user = userRepository.save(user);
   } catch (DataIntegrityViolationException ex) {
       throw new IllegalArgumentException("This Spotify account is already connected to another user");
   }
   ```

3. **Add `DataIntegrityViolationException` handler to `GlobalExceptionHandler`** as a safety net for any other unique constraint violations across the codebase:
   ```java
   @ExceptionHandler(DataIntegrityViolationException.class)
   public ResponseEntity<Map<String, String>> handleDataIntegrity(DataIntegrityViolationException ex) {
       log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
       return ResponseEntity.status(HttpStatus.CONFLICT)
               .body(Map.of("error", "A conflict occurred. The resource may already exist."));
   }
   ```
   This returns 409 Conflict with a generic safe message (no internal details leaked).

**Verification:**
- Send two simultaneous `POST /api/v1/auth/register` requests with the same email — the second should return 409 or the domain-specific `EmailAlreadyExistsException` response, not 500.
- Send two simultaneous `POST /api/v1/auth/connect-spotify` requests with the same Spotify ID for different users — the second should return 400 with "already connected" message, not 500.
- Run the existing test suite — all tests pass.

---

## Phase 2 — Core Race Condition Fixes (P0)

### - [x] Batch C — Atomic Mutual-Match Detection in `SwipeService` (completed 2026-03-17)

**Priority:** CRITICAL
**Risk:** The current `recordSwipe()` flow persists the swipe in a `REQUIRES_NEW` transaction (`SwipePersistenceHelper.saveSwipe()`, line 100), then checks for a mutual like (`hasUserLiked()`, line 122) in the outer transaction. The `REQUIRES_NEW` boundary means the swipe is committed independently, creating a window where two users liking each other simultaneously can **both miss the mutual match** — or in the best case, only one creates the match while the other sees no mutual like. This is the most dangerous race condition in the codebase because it directly affects the core feature: users who like each other may never be matched.
**Affected files:**
- `services/matching/SwipeService.java`
- `services/matching/SwipePersistenceHelper.java`

**What to do:**

1. **Move the mutual-like check BEFORE persisting the swipe.** The key insight is: check if the other user has already liked us *before* we insert our swipe. This way, the check and the swipe insertion happen in the same transaction (the outer `@Transactional` on `recordSwipe()`):

   Restructure `recordSwipe()` (lines 53–177) as follows:
   ```java
   @Transactional
   public SwipeResult recordSwipe(User swiper, String swipedUserId, String action,
                                   Double matchScore, String platform) {
       // 1. Validate action, load users, compute score (unchanged — lines 55–78)

       // 2. Advisory duplicate check (unchanged — lines 80–83)
       if (swipeRepository.hasUserSwipedOn(swiper.getId(), swipedUserId)) {
           throw new DuplicateSwipeException(swiper.getId(), swipedUserId);
       }

       // 3. CHECK MUTUAL LIKE *BEFORE* SAVING (moved from line 122)
       boolean mutualLike = false;
       MatchSource matchSource = null;
       if ("like".equals(normalizedAction) || "super_like".equals(normalizedAction)) {
           mutualLike = swipeRepository.hasUserLiked(swipedUserId, swiper.getId());
           if (mutualLike) {
               matchSource = "super_like".equals(normalizedAction)
                       ? MatchSource.SUPER_LIKE : MatchSource.MUTUAL_SWIPE;
           }
       }

       // 4. Create and persist swipe (lines 86–103)
       //    Set resultedInMatch based on the check above
       UserSwipe swipe = UserSwipe.builder()
               .swiperUser(swiperEntity)
               .swipedUser(swipedUserEntity)
               .action(normalizedAction)
               .matchScoreAtSwipe(computedMatchScore)
               .platform(platform != null ? platform : "web")
               .swipedAt(LocalDateTime.now())
               .resultedInMatch(mutualLike)
               .build();

       try {
           swipe = swipePersistenceHelper.saveSwipe(swipe);
       } catch (DataIntegrityViolationException ex) {
           throw new DuplicateSwipeException(swiper.getId(), swipedUserId);
       }

       // 5. If block, return early (unchanged — lines 106–114)

       // 6. If mutual like detected, create match and link swipes
       SwipeResult.MatchDetails matchDetails = null;
       if (mutualLike) {
           Match match = matchService.createMatch(swiperEntity, swipedUserEntity,
                   computedMatchScore, matchSource);

           swipe.setMatch(match);
           swipeRepository.save(swipe);

           Optional<UserSwipe> otherSwipe = swipeRepository.findByUserIds(
                   swipedUserId, swiper.getId());
           if (otherSwipe.isPresent()) {
               UserSwipe other = otherSwipe.get();
               other.setResultedInMatch(true);
               other.setMatch(match);
               swipeRepository.save(other);
           }

           matchDetails = SwipeResult.MatchDetails.builder()
                   .matchId(match.getId())
                   .userId(swipedUserEntity.getId())
                   .name(swipedUserEntity.getName())
                   .matchScore(computedMatchScore)
                   .matchedAt(match.getMatchedAt().toString())
                   .build();
       }

       // 7. Publish event and return (unchanged — lines 165–177)
       eventPublisher.publishEvent(new SwipeRecordedEvent(...));

       return SwipeResult.builder()
               .swipeId(swipe.getId())
               .action(normalizedAction)
               .matchScore(computedMatchScore)
               .resultedInMatch(mutualLike)
               .match(matchDetails)
               .build();
   }
   ```

2. **Why this fixes the race:** By checking `hasUserLiked()` *before* persisting our swipe, the check and insert are in the same transaction. If two users swipe simultaneously:
   - Thread A checks → no mutual like yet → saves swipe → commits
   - Thread B checks → sees Thread A's swipe (committed) → mutual like! → creates match
   - OR Thread B's swipe save hits the unique constraint → `DuplicateSwipeException`

   The key is that `hasUserLiked()` now runs when the other user's swipe is either fully committed or not yet visible — never in the partial-commit limbo caused by `REQUIRES_NEW`.

3. **Keep `SwipePersistenceHelper.saveSwipe()` as `REQUIRES_NEW`.** It still serves a purpose: if the unique constraint fires, the `DataIntegrityViolationException` does not poison the outer transaction. This is still needed for the duplicate-swipe error handling path.

4. **Remove the block-action early return before the mutual-like check.** Move the block check to after the swipe persistence (which is fine since blocks don't need mutual detection anyway). The current code already has this structure — just ensure the mutual-like check happens before `saveSwipe()`, not after.

**Verification:**
- Write a test that simulates two users swiping right on each other: assert exactly one `Match` is created and both swipes have `resultedInMatch = true`.
- Confirm that blocking still works (no match created, no behavioral update).
- Confirm that duplicate swipes still throw `DuplicateSwipeException`.
- Run the existing test suite — all tests pass.

---

### - [x] Batch D — Genre Sync Transaction Atomicity (completed 2026-03-17)

**Priority:** CRITICAL
**Risk:** `SpotifyGenreSyncService.syncUserGenrePreferences()` (line 46) calls `genreExtractionService.clearSpotifyPreferences()` (line 109) and `genreExtractionService.extractAndSaveGenrePreferences()` (line 112) as two separate `@Transactional` methods. Each commits independently. If `extractAndSaveGenrePreferences()` throws an exception, the user's genre preferences are **permanently deleted** with no rollback. Additionally, between the two commits, the matching algorithm can read the user as having zero genre preferences, producing incorrect scores.
**Affected files:**
- `services/matching/SpotifyGenreSyncService.java`
- `services/matching/GenreExtractionService.java`

**What to do:**

1. **Create a new atomic method in `GenreExtractionService` that combines clear + save.** Add a method that deletes old preferences and inserts new ones in a single transaction:
   ```java
   @Transactional
   public int replaceSpotifyPreferences(User user, List<String> genres) {
       userGenrePreferenceRepository.deleteByUserIdAndSource(
               user.getId(), GenrePreferenceSource.SPOTIFY_DERIVED);
       return extractAndSaveGenrePreferencesInternal(user, genres,
               GenrePreferenceSource.SPOTIFY_DERIVED);
   }
   ```
   Extract the core logic of `extractAndSaveGenrePreferences()` into a private `extractAndSaveGenrePreferencesInternal()` method (or reuse the existing method if it does not open its own transaction — check if its `@Transactional` annotation would participate in the caller's transaction via `REQUIRED` propagation).

   **Important:** Both `clearSpotifyPreferences()` and `extractAndSaveGenrePreferences()` have `@Transactional` with default propagation (`REQUIRED`). When called from `syncUserGenrePreferences()` which is also `@Transactional`, they will **join** the caller's transaction. This means the two-commit problem only occurs if the caller is NOT `@Transactional`, or if the methods are called on a different bean through a non-proxied reference.

   **Verify the actual behavior:** Check if `syncUserGenrePreferences()` is called through the Spring proxy (i.e., from a controller or another injected bean). If yes, the `@Transactional` on `syncUserGenrePreferences()` already wraps both calls in one transaction, and this batch reduces to adding a verification test. If no (e.g., called via `this.syncUserGenrePreferences()`), the fix is to ensure proxy-based invocation.

2. **Update `SpotifyGenreSyncService.syncUserGenrePreferences()` to call the new atomic method:**
   ```java
   // Replace lines 109-116 with:
   int savedCount = genreExtractionService.replaceSpotifyPreferences(user, allGenres);
   ```

3. **Update `SpotifyGenreSyncService.quickSyncUserGenrePreferences()` similarly** (line 145). It currently calls `extractAndSaveGenrePreferences()` without calling `clearSpotifyPreferences()` first — verify whether this is intentional (additive sync) or a bug (should also clear first).

4. **Add a deduplication guard to prevent concurrent syncs for the same user.** Add a `ConcurrentHashMap<String, Boolean>` as a simple in-memory lock:
   ```java
   private final ConcurrentHashMap<String, Boolean> syncInProgress = new ConcurrentHashMap<>();

   @Transactional
   public int syncUserGenrePreferences(User user, String accessToken) throws JsonProcessingException {
       if (syncInProgress.putIfAbsent(user.getId(), Boolean.TRUE) != null) {
           log.info("Genre sync already in progress for user {}, skipping", user.getId());
           return 0;
       }
       try {
           // ... existing logic ...
       } finally {
           syncInProgress.remove(user.getId());
       }
   }
   ```
   This is a single-instance guard (not distributed), but sufficient for the current deployment model.

**Verification:**
- Trigger a genre sync, then immediately trigger another for the same user — the second should be skipped.
- Verify that if `extractAndSaveGenrePreferences()` throws an exception, the old preferences are NOT deleted (transaction rolls back atomically).
- Run the existing test suite — all tests pass.

---

## Phase 3 — Resilience Improvements (P1)

### - [x] Batch E — Behavioral Profile Retry Backoff + Token Refresh Locking (completed 2026-03-17)

**Priority:** HIGH
**Risk:** Two medium-severity issues. (1) `BehavioralProfileService.updateAfterSwipe()` (line 102) retries 3 times on `OptimisticLockingFailureException` with **no delay** between retries. Under heavy swipe load, all 3 retries can fail instantly because the conflicting thread hasn't committed yet. (2) `UserServiceImpl.refreshAndUpdateUserToken()` (line 170) has no protection against concurrent token refreshes for the same user. Two threads can both call Spotify's token endpoint with the same refresh token; if Spotify rotates the token, the second thread overwrites the new token with the old one.
**Affected files:**
- `services/matching/BehavioralProfileService.java`
- `services/impl/UserServiceImpl.java`

**What to do:**

1. **Add exponential backoff to `BehavioralProfileService.updateAfterSwipe()` (lines 102–123).** Insert a short sleep between retries:
   ```java
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
                   Thread.sleep(20L * (1 << attempt)); // 40ms, 80ms
               } catch (InterruptedException ie) {
                   Thread.currentThread().interrupt();
                   return;
               }
           } catch (Exception e) {
               log.warn("Failed to update behavioural profile for swiper {}: {}", swiperId, e.getMessage());
               return;
           }
       }
   }
   ```

2. **Add a per-user lock to `UserServiceImpl.refreshAndUpdateUserToken()` (line 170).** Use a `ConcurrentHashMap` to ensure only one thread refreshes a given user's token at a time:
   ```java
   private final ConcurrentHashMap<String, Object> tokenRefreshLocks = new ConcurrentHashMap<>();

   @Transactional
   public String refreshAndUpdateUserToken(User user) {
       Object lock = tokenRefreshLocks.computeIfAbsent(user.getId(), k -> new Object());
       synchronized (lock) {
           try {
               // Re-read the user to check if another thread already refreshed
               User freshUser = userRepository.findById(user.getId())
                       .orElseThrow(() -> new UserNotFoundException("User not found"));

               if (!isTokenExpiredOrExpiring(freshUser)) {
                   // Another thread already refreshed — return the current valid token
                   return encryptionService.decrypt(freshUser.getSpotifyAccessToken());
               }

               String refreshToken = encryptionService.decrypt(freshUser.getSpotifyRefreshToken());
               SpotifyTokenResponse tokenResponse = jwtService.refreshToken(refreshToken);

               freshUser.setSpotifyAccessToken(encryptionService.encrypt(tokenResponse.getAccess_token()));
               freshUser.setSpotifyTokenExpires(Instant.now().plusSeconds(
                       Long.parseLong(tokenResponse.getExpires_in())));

               if (tokenResponse.getRefresh_token() != null &&
                       !tokenResponse.getRefresh_token().equals(refreshToken)) {
                   freshUser.setSpotifyRefreshToken(
                           encryptionService.encrypt(tokenResponse.getRefresh_token()));
               }

               userRepository.save(freshUser);
               return tokenResponse.getAccess_token();
           } finally {
               tokenRefreshLocks.remove(user.getId());
           }
       }
   }
   ```
   Key changes: (a) `synchronized` per-user lock prevents concurrent refreshes, (b) re-reads the user inside the lock to check if another thread already refreshed, (c) uses the fresh user entity for all operations.

**Verification:**
- Trigger 5 concurrent `GET /api/v1/preferences/genres/sync` requests for the same user — only one Spotify token refresh should occur (check logs for single "refreshing" message).
- Rapid swipes on the same user should succeed with behavioral profile updates (check logs for retry attempts, not drops).
- Run the existing test suite — all tests pass.

---

### - [x] Batch F — Per-Request Token Version Validation

**Priority:** MEDIUM
**Risk:** After a password reset, `tokenVersion` is incremented (in `AuthServiceImpl.resetPassword()`, line 210), but existing JWTs remain valid until natural expiry (~24 hours). The `tokenVersion` claim is embedded in the JWT at login time but is NOT validated on every request by the `SecurityConfig` JWT decoder. A user who resets their password cannot immediately invalidate all existing sessions.
**Affected files:**
- `security/SecurityConfig.java`
- `services/impl/JwtServiceImpl.java`

**What to do:**

1. **Add a custom JWT validator to `SecurityConfig`.** After the JWT is decoded, compare the `tokenVersion` claim in the JWT against the current `tokenVersion` in the database:
   ```java
   @Bean
   public JwtDecoder jwtDecoder() {
       NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey).build();
       decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
               JwtValidators.createDefault(),
               tokenVersionValidator()
       ));
       return decoder;
   }

   private OAuth2TokenValidator<Jwt> tokenVersionValidator() {
       return jwt -> {
           String userId = jwt.getSubject();
           Integer tokenVersion = jwt.getClaim("tokenVersion");
           if (userId == null || tokenVersion == null) {
               return OAuth2TokenValidatorResult.failure(
                       new OAuth2Error("invalid_token", "Missing token metadata", null));
           }

           // Look up current token version from DB
           Optional<User> userOpt = userRepository.findById(userId);
           if (userOpt.isEmpty()) {
               return OAuth2TokenValidatorResult.failure(
                       new OAuth2Error("invalid_token", "User not found", null));
           }

           Integer currentVersion = userOpt.get().getTokenVersion();
           if (currentVersion != null && !currentVersion.equals(tokenVersion)) {
               return OAuth2TokenValidatorResult.failure(
                       new OAuth2Error("invalid_token", "Token has been revoked", null));
           }

           return OAuth2TokenValidatorResult.success();
       };
   }
   ```

2. **Verify that `JwtServiceImpl.generateToken()` includes `tokenVersion` as a claim.** If it does not, add it:
   ```java
   .claim("tokenVersion", user.getTokenVersion() != null ? user.getTokenVersion() : 0)
   ```

3. **Performance consideration:** This adds a DB query per authenticated request. To mitigate:
   - Use a short-lived cache (e.g., Caffeine, 30-second TTL) keyed by `userId` → `tokenVersion`. This means revocation takes effect within 30 seconds, which is acceptable.
   - Only cache the `tokenVersion` integer, not the full user entity.

**Verification:**
- Login, note the JWT. Reset password. Use the old JWT to call a protected endpoint — should return 401.
- Login again with new password — new JWT should work normally.
- Run the existing test suite — all tests pass. Update any tests that mock JWT validation if needed.

---

## Summary of All Issues Addressed

| Batch | Issue | Severity | Root Cause | Fix |
|---|---|---|---|---|
| A | `UserEntity` + `Match` lack `@Version` | CRITICAL | Last-write-wins on concurrent updates | Add `@Version Long version` |
| B | Registration/Spotify 500 on race | HIGH | `DataIntegrityViolationException` not caught | Catch + map to domain exceptions |
| C | Mutual-match missed on concurrent swipes | CRITICAL | `REQUIRES_NEW` breaks atomicity of check-then-act | Check mutual like before persisting swipe |
| D | Genre preferences deleted without rollback | CRITICAL | Two separate `@Transactional` commits | Combine clear + insert atomically |
| E | Behavioral retry fails instantly; token refresh race | HIGH | No backoff; no per-user locking | Exponential backoff; synchronized refresh |
| F | Stale JWTs valid 24h after password reset | MEDIUM | `tokenVersion` not checked per-request | Custom JWT validator |
