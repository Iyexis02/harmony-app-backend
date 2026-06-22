# Backend Stability Fix Roadmap — Master System-Level Audit

## Purpose

This document is the **system-level audit roadmap** for the dating app Spring Boot backend. It captures every **cross-component architectural risk** identified during a deep end-to-end trace of all critical user flows as of 2026-03-20.

Previous roadmaps (8 documents, 63+ batches) addressed individual-component issues: data integrity, error handling, security, scalability, concurrency, cross-module integrity, JPA correctness, and final architecture. **All previous batches are complete.** This roadmap covers what those audits could not see — issues that only emerge when tracing data flow across multiple components simultaneously.

**This file is the single source of truth for implementation order.** Each agent session must read this file before starting work and follow the batch sequence strictly.

## Implementation Rules

1. Only implement **one batch per session**
2. Follow the **dependency order** strictly — never start a batch whose dependencies are unchecked
3. Do not modify code outside the current batch scope
4. After completing a batch, update its checkbox to `[x]` and fill in the completion date
5. Always read this file at the start of every session before implementing the next batch
6. Run `./mvnw compile` after each batch and confirm zero errors before marking complete
7. If a batch cannot be completed, add a `**Blocked:**` note under it and move on only if the next batch has no dependency on it

---

## Pre-Audit Baseline (2026-03-20)

### Completed Roadmaps
| Roadmap | File | Batches | Status |
|---------|------|---------|--------|
| Core Stability | `backend-stability-roadmap.md` | A–J (10) | Complete |
| Error Handling | `backend-stability-roadmap-error-handle-audit.md` | A–F (6) | Complete |
| Security | `backend-stability-roadmap-security-audit.md` | A–G (7) | Complete |
| Scalability | `backend-stability-roadmap-scalability-audit.md` | A–H (8) | Complete |
| Concurrency | `backend-stability-roadmap-concurrency-audit.md` | A–E (5) | Complete |
| Cross-Module Integrity | `backend-stability-roadmap-integrity-audit.md` | A–H (8) | Complete |
| JPA & Repository | `backend-stability-roadmap-jpa-repository-audit.md` | A–H (8) | Complete |
| Final Architecture | `backend-stability-roadmap-final-audit.md` | A–J (10) | Complete |

### System Architecture Snapshot

**Core domains and data flow:**
```
User ──┬── UserEntity (@Version, JPA)
       ├── Sub-entities: photos, lifestyle, personality, datingPrefs, privacy [CascadeType.ALL]
       └── Matching entities (string ID refs, NO JPA FK):
           ├── UserSwipe ──→ Match (optional FK)
           ├── UserMatchScore (DB score cache)
           ├── UserGenrePreference
           └── UserBehavioralProfile
```

**Transaction boundaries:**
- `SwipeService.recordSwipe()` — single @Transactional: swipe insert + match creation + event registration
- `BehavioralProfileService.doUpdateAfterSwipe()` — separate @Transactional, @Async + AFTER_COMMIT
- `SpotifyGenreSyncService.sync*()` — @Transactional wrapping lock + HTTP calls + DB writes
- `MatchRecommendationService.fetchCandidateData()` — @Transactional(readOnly=true); scoring runs outside TX
- `AccountDeletionServiceImpl.deleteAccount()` — single @Transactional with PESSIMISTIC_WRITE lock

**Async boundaries:**
- `@TransactionalEventListener(AFTER_COMMIT) + @Async` for: behavioral updates, match lifecycle events, block→unmatch chain
- Thread pool: core=10, max=50, queue=200, CallerRunsPolicy

**Caching layers:**
- DB-level: `user_match_scores` table (staleness via `updatedAt` comparison)
- In-memory: Caffeine in `BehavioralScoreCalculator` (1000 entries, 10min TTL)
- In-memory: Caffeine for JWT token version validation (30s TTL, 10K entries)

**External APIs:**
- Spotify Web API — circuit breaker `"spotify"` (shared across ALL Spotify operations)
- Google Maps/Nominatim — circuit breaker `"geocoding"`

---

## Dependency Graph

```
Phase 1 — Critical Fixes (P0, production blockers)
  A (independent — transaction boundary fix in Spotify sync)
  A2 (independent — transaction boundary fix in Spotify token refresh)
  B (independent — distributed lock ownership)
  B2 (independent — re-match after unmatch permanently broken)

Phase 2 — Data Integrity Fixes (P1)
  A ─► C (exception propagation — needs A's TX fix to avoid masking failures)
  D (independent — pagination determinism)
  E (independent — account deletion FK race)
  E2 (independent — doUnmatch overwrites already-unmatched state)

Phase 3 — UX & Correctness Fixes (P2)
  F (independent — cache breakdown/insights)
  G (independent — auth for deleted users)
  H (independent — block-unmatch reconciliation)

Phase 4 — Resilience Improvements (P3)
  I (independent — circuit breaker separation)
  J (independent — reverse score resilience)
```

## Recommended Implementation Order

```
Phase 1 — Critical Fixes (P0)
  1. Batch A  — Move HTTP calls outside @Transactional in Spotify sync
  2. Batch A2 — Move HTTP calls outside @Transactional in Spotify token refresh
  3. Batch B  — Add lock ownership tracking to DistributedLockService
  4. Batch B2 — Fix re-matching after unmatch (INSERT ON CONFLICT + status)

Phase 2 — Data Integrity Fixes (P1)
  5. Batch C  — Fix exception swallowing in getGenresFromTopArtists    [depends on A]
  6. Batch D  — Stabilize pagination with deterministic candidate ordering
  7. Batch E  — Guard account deletion against concurrent FK inserts
  8. Batch E2 — Guard doUnmatch against already-UNMATCHED matches

Phase 3 — UX & Correctness Fixes (P2)
  9. Batch F  — Preserve match breakdown/insights in score cache
 10. Batch G  — Return 401 for deleted users' JWTs
 11. Batch H  — Add reconciliation for failed block-unmatch operations

Phase 4 — Resilience Improvements (P3)
 12. Batch I  — Separate Spotify circuit breakers by operation type
 13. Batch J  — Harden reverse score computation on mutual match path
```

---

## Phase 1 — Critical Fixes (P0)

### - [x] Batch A — Move HTTP Calls Outside @Transactional in Spotify Sync (2026-03-20)

**Priority:** P0 | **Risk:** High | **Effort:** 2–3 hours
**Depends on:** Nothing
**Blocking:** Batch C

#### Problem

`SpotifyGenreSyncService.syncUserGenrePreferences()` and `quickSyncUserGenrePreferences()` are annotated with `@Transactional`. The transaction opens before the distributed lock acquisition and holds a DB connection through 3 parallel HTTP calls to Spotify (each with a 15-second timeout). Worst case: **15+ seconds holding a connection pool slot with zero DB work happening**.

The `@Transactional` annotation exists because `replaceSpotifyPreferences()` + `touchUpdatedAt()` need transactional atomicity. But the HTTP calls preceding them do not require a transaction.

**How it manifests:**

1. User triggers genre sync → `@Transactional` starts → HikariCP connection acquired
2. Distributed lock acquired → 3 `CompletableFuture.supplyAsync` HTTP calls submitted
3. `future.get(15, TimeUnit.SECONDS)` blocks the calling thread while holding the connection
4. Spotify is slow (10s read timeout) → connection held for 10-15 seconds with no DB interaction
5. With HikariCP default pool size of 10, only 10 concurrent syncs exhaust the pool
6. All other DB operations across the entire application block until a sync completes

**Impact:** Connection pool exhaustion under moderate load. Cascading failure across all endpoints.

#### Files Affected

- `src/main/java/com/example/dating/services/matching/SpotifyGenreSyncService.java` (modify)

#### Implementation Tasks

1. **Remove `@Transactional` from `syncUserGenrePreferences()`**
2. **Extract the DB write phase into a separate `@Transactional` method** on the same class (or on `GenreExtractionService`):
   ```java
   @Transactional
   protected int persistGenreSync(User user, List<String> allGenres) {
       int count = genreExtractionService.replaceSpotifyPreferences(user, allGenres);
       userJpaRepository.touchUpdatedAt(user.getId());
       return count;
   }
   ```
3. **Restructure `syncUserGenrePreferences()`** into two phases:
   - Phase 1 (no TX): Acquire lock → parallel HTTP calls → collect genres
   - Phase 2 (@Transactional): `replaceSpotifyPreferences()` + `touchUpdatedAt()`
4. **Apply the same pattern to `quickSyncUserGenrePreferences()`** — remove `@Transactional`, extract DB write to a transactional method
5. **Ensure the `@Transactional` method is called via proxy** (not `this.method()`) — either use `self.` injection or move the transactional method to a collaborating service (e.g., `GenreExtractionService` already has `@Transactional` methods)
6. **Verify the `@CircuitBreaker` annotation still works** — it must be on the public proxy method, not the inner transactional one

#### Verification

- [ ] `syncUserGenrePreferences()` no longer has `@Transactional` annotation
- [ ] `quickSyncUserGenrePreferences()` no longer has `@Transactional` annotation
- [ ] DB writes (`replaceSpotifyPreferences` + `touchUpdatedAt`) still execute within a single transaction
- [ ] HTTP calls execute outside any transaction boundary
- [ ] Lock acquire/release still wraps the entire operation (both phases)
- [ ] `@CircuitBreaker` annotation remains on the public entry points
- [ ] `./mvnw compile` passes
- [ ] Application starts and genre sync endpoint still works

---

### - [x] Batch A2 — Move HTTP Calls Outside @Transactional in Spotify Token Refresh (2026-03-20)

**Priority:** P0 | **Risk:** High | **Effort:** 2–3 hours
**Depends on:** Nothing

#### Problem

`SpotifyTokenServiceImpl.refreshAndUpdateUserToken()` is `@Transactional`. The transaction opens, reads the user from DB, then calls `jwtService.refreshToken(refreshToken)` — an HTTP call to Spotify with 3 retry attempts and exponential backoff (500ms, 1s, 2s). Each attempt has a 10-second read timeout. **Worst case: ~33.5 seconds holding a DB connection with zero DB work happening.** Additionally, `getValidSpotifyToken()` is also `@Transactional` and calls `refreshAndUpdateUserToken()` with `REQUIRED` propagation, so the outer transaction starts even before the token-expiry check.

This is the **same pattern** as Batch A (genre sync), but in a different service that was missed. It is **worse** because token refresh is triggered more frequently — Spotify tokens expire every 1 hour, and refresh is triggered on every request where the token is within 5 minutes of expiry. A burst of expiring tokens (e.g., tokens issued at the same time for multiple users) causes concurrent refreshes.

**How it manifests:**

1. User makes any Spotify-dependent request → `getValidSpotifyToken()` called
2. `@Transactional` starts → HikariCP connection acquired
3. Token is expiring → `refreshAndUpdateUserToken()` joins the outer transaction
4. `findById` → DB read (fast)
5. `jwtService.refreshToken(refreshToken)` → HTTP call to Spotify
6. Spotify is slow (10s read timeout) → connection held for 10+ seconds
7. With default pool size of 10, only 10 concurrent token refreshes exhaust the pool
8. ALL other DB operations across the entire application block

**Additional compounding factor:** The loser path (lock not acquired, line 68) calls `Thread.sleep(500)` while still inside the `@Transactional` from `getValidSpotifyToken()`, holding a DB connection during the sleep.

**Impact:** Connection pool exhaustion under moderate load when multiple users have expiring tokens. More frequent than genre sync because token refresh happens automatically on every request.

#### Files Affected

- `src/main/java/com/example/dating/services/impl/SpotifyTokenServiceImpl.java` (modify)

#### Implementation Tasks

1. **Remove `@Transactional` from `getValidSpotifyToken()`** — the expiry check does not need a transaction. The `encryptionService.decrypt()` call is stateless.
2. **Remove `@Transactional` from `refreshAndUpdateUserToken()`**
3. **Extract the DB write into a separate `@Transactional` method**:
   ```java
   @Transactional
   protected void persistRefreshedToken(String userId, String encryptedAccessToken,
                                         Instant tokenExpires, String encryptedRefreshToken) {
       UserEntity entity = userJpaRepository.findById(userId)
               .orElseThrow(() -> new UserNotFoundException("User not found"));
       entity.setSpotifyAccessToken(encryptedAccessToken);
       entity.setSpotifyTokenExpires(tokenExpires);
       if (encryptedRefreshToken != null) {
           entity.setSpotifyRefreshToken(encryptedRefreshToken);
       }
       userJpaRepository.save(entity);
   }
   ```
4. **Restructure `refreshAndUpdateUserToken()`** into phases:
   - Phase 1 (no TX): Acquire lock → re-read user → check if already refreshed → decrypt refresh token
   - Phase 2 (no TX): `jwtService.refreshToken()` → HTTP call to Spotify
   - Phase 3 (@Transactional): `persistRefreshedToken()` → DB write
5. **The re-read inside the lock** (`findById` at line 85) needs its own short read transaction. Use a separate `@Transactional(readOnly=true)` method or rely on Spring Data's default per-query transaction.
6. **Move the `Thread.sleep(500)` loser path** outside any transaction boundary — it currently sleeps while holding a connection from `getValidSpotifyToken()`'s `@Transactional`
7. **Ensure the `@Transactional` method is called via proxy** — either use `self.` injection pattern or move to a collaborating service

#### Verification

- [ ] `getValidSpotifyToken()` no longer has `@Transactional` annotation
- [ ] `refreshAndUpdateUserToken()` no longer has `@Transactional` annotation
- [ ] Token persistence (`save()`) still executes within a transaction
- [ ] HTTP call to Spotify executes outside any transaction boundary
- [ ] `Thread.sleep(500)` in loser path executes outside any transaction
- [ ] Lock acquire/release still wraps the entire operation
- [ ] `./mvnw compile` passes
- [ ] Application starts and token refresh still works

---

### - [x] Batch B — Add Lock Ownership Tracking to DistributedLockService (2026-03-20)

**Priority:** P0 | **Risk:** High | **Effort:** 2–3 hours
**Depends on:** Nothing

#### Problem

`DistributedLockService.tryLock()` stores the value `"locked"` for every SETNX. `unlock()` unconditionally deletes the key. If a lock's TTL expires while the holder is still working (e.g., slow Spotify response), a second instance acquires the same key. When the first instance reaches `finally { unlock(lockKey); }`, it deletes the **second instance's lock**.

**How it manifests (multi-instance deployment):**

1. Instance A acquires `lock:spotify:sync:user1` (60s TTL)
2. Spotify is slow — 61 seconds pass, lock auto-expires
3. Instance B acquires the same key (SETNX succeeds, new 60s TTL)
4. Instance A finishes, calls `unlock()` → deletes Instance B's lock
5. Instance C now acquires the lock — B and C run concurrently
6. Both B and C execute `replaceSpotifyPreferences()` — one overwrites the other's preferences

Same issue applies to the 30s TTL token refresh lock if `refreshToken()` retry loop takes longer than 30s under worst-case conditions (3 attempts × 10s read timeout = 30s + backoff = ~33.5s).

**Impact:** Concurrent genre syncs for the same user. One instance's preferences silently overwrite the other's. Concurrent token refreshes could save conflicting tokens.

#### Files Affected

- `src/main/java/com/example/dating/config/DistributedLockService.java` (modify)

#### Implementation Tasks

1. **Generate a unique owner ID per lock acquisition** — use `UUID.randomUUID().toString()` as the lock value instead of `"locked"`
2. **Modify `tryLock()` to return the owner ID** (or store it internally for verification):
   - Option A: Change return type to `String` (null = not acquired, non-null = owner ID for unlock)
   - Option B: Keep `boolean` return, store `ConcurrentHashMap<String, String>` mapping key→ownerValue internally
   - **Recommended: Option A** — cleaner, caller passes owner ID to `unlock(key, ownerId)`
3. **Modify `unlock()` to accept the owner ID** and use a Redis Lua script for conditional delete:
   ```lua
   if redis.call('get', KEYS[1]) == ARGV[1] then
       return redis.call('del', KEYS[1])
   else
       return 0
   end
   ```
   Execute via `redisTemplate.execute(RedisScript<Long>, ...)`.
4. **Update the in-memory fallback** — store the owner ID in the `localFallback` map (`ConcurrentHashMap<String, String>` instead of `<String, Boolean>`). Only remove if the stored value matches the owner ID.
5. **Update all callers** (`SpotifyGenreSyncService`, `SpotifyTokenServiceImpl`) to:
   - Store the owner ID returned by `tryLock()`
   - Pass it to `unlock(key, ownerId)` in the `finally` block
6. **Log a warning if unlock is called with a mismatched owner** — this indicates the lock expired and was re-acquired by another process

#### Verification

- [ ] `tryLock()` stores a unique UUID as the Redis value
- [ ] `unlock()` uses a Lua script to conditionally delete only if the value matches
- [ ] In-memory fallback also tracks owner IDs
- [ ] `SpotifyGenreSyncService` passes owner ID through `try/finally`
- [ ] `SpotifyTokenServiceImpl` passes owner ID through `try/finally`
- [ ] Log warning emitted when unlock finds a mismatched owner (lock was stolen)
- [ ] `./mvnw compile` passes
- [ ] Application starts and distributed locking still functions

---

### - [x] Batch B2 — Fix Re-Matching After Unmatch (INSERT ON CONFLICT + Status) (2026-03-20)

**Priority:** P0 | **Risk:** Critical | **Effort:** 2–3 hours
**Depends on:** Nothing

#### Problem

The match creation system **permanently prevents re-matching** after any unmatch, through a chain of failures across three components that is only visible when tracing the full lifecycle: match → unmatch → re-swipe → re-match.

**Chain of failures:**

1. **Unique constraint is per-pair, status-unaware.** `matches` table has `UNIQUE(user_a_id, user_b_id)`. Only ONE row can ever exist per user pair, regardless of status (ACTIVE, UNMATCHED, etc.).

2. **`insertMatchIfAbsent` uses `ON CONFLICT DO NOTHING`.** When users re-match after an unmatch, the INSERT silently does nothing because the old UNMATCHED row occupies the unique slot. The method returns `inserted = 0`.

3. **`findMatchBetweenUsers` has no status filter.** It returns the match regardless of status:
   ```sql
   SELECT m FROM Match m WHERE (m.userA.id = :user1Id AND m.userB.id = :user2Id)
   OR (m.userA.id = :user2Id AND m.userB.id = :user1Id)
   ```

4. **`createMatch()` returns the UNMATCHED match to `SwipeService`.** At `MatchService.java:128`, `findMatchBetweenUsers` fetches and returns the old UNMATCHED match. `SwipeService` then links the new swipe to it and publishes `MatchCreatedEvent` — but the match status remains UNMATCHED.

**Currently masked by upstream guards:**
- `findAllSwipedUserIds` excludes previously-swiped users from recommendations → they never appear in feeds
- `hasUserSwipedOn` throws `DuplicateSwipeException` → they can't re-swipe

**The mask is fragile.** The bug manifests if ANY of these become true:
- A "second chance" / rematch feature is added
- An admin API creates matches directly
- Swipe rows are purged (TTL-based cleanup, data migration) while match rows remain
- The 10,000 exclusion cap is exceeded, causing old swiped users to reappear

**How it manifests when the mask breaks:**

1. User A and User B match (status=ACTIVE)
2. User A unmatches → status=UNMATCHED, version=1
3. Time passes. Swipe rows are purged or a rematch feature is used.
4. User B likes User A again, User A likes B → mutual like detected
5. `createMatch()` → `insertMatchIfAbsent()` → `ON CONFLICT DO NOTHING` (UNMATCHED row blocks insert)
6. `findMatchBetweenUsers()` returns the OLD match with **status=UNMATCHED**
7. `SwipeService` sets `resultedInMatch=true`, publishes `MatchCreatedEvent`
8. User sees "It's a match!" UI celebration
9. `getActiveMatches()` filters by status=ACTIVE → **the match never appears in either user's match list**
10. Users are permanently unable to match — silent, irreversible failure

**Impact:** Permanent silent match failure for any user pair that was ever unmatched. Users see "It's a match!" but the match is invisible. No error logged, no recovery path.

#### Files Affected

- `src/main/java/com/example/dating/repositories/MatchRepository.java` (modify native query)
- `src/main/java/com/example/dating/services/matching/MatchService.java` (modify)

#### Implementation Tasks

1. **Change `insertMatchIfAbsent` from `DO NOTHING` to `DO UPDATE`** — reactivate UNMATCHED matches:
   ```sql
   INSERT INTO matches (id, user_a_id, user_b_id, match_score, match_score_b, status,
      conversation_started, match_source, matched_at, created_at, updated_at, message_count, version)
   VALUES (:id, :userAId, :userBId, :matchScoreA, :matchScoreB, 'active', false,
      :matchSource, :matchedAt, :createdAt, :updatedAt, 0, 0)
   ON CONFLICT (user_a_id, user_b_id) DO UPDATE SET
      status = 'active',
      match_score = EXCLUDED.match_score,
      match_score_b = EXCLUDED.match_score_b,
      match_source = EXCLUDED.match_source,
      matched_at = EXCLUDED.matched_at,
      updated_at = EXCLUDED.updated_at,
      unmatched_at = NULL,
      unmatched_by = NULL,
      conversation_started = false,
      message_count = 0,
      version = matches.version + 1
   WHERE matches.status != 'active'
   ```
   The `WHERE matches.status != 'active'` clause ensures already-ACTIVE matches are not modified (preserving the idempotency for concurrent swipe races).
2. **Update the return value check** in `MatchService.createMatch()`: `inserted` is now 1 for both new inserts AND reactivations. Log differently: check if the fetched match's `matchedAt` equals the `now` value passed to the query.
3. **Add `findActiveMatchBetweenUsers`** as a separate query with `AND m.status = 'ACTIVE'` for use cases that specifically need only active matches (e.g., `MatchLifecycleListener.onUserBlocked`).
4. **Update `MatchLifecycleListener.onUserBlocked()`** to use the new `findActiveMatchBetweenUsers` query instead of `findMatchBetweenUsers`, to avoid false matches on UNMATCHED rows.
5. **Consider whether swipe rows should be cleaned up on unmatch** to allow re-swiping — this is a product decision (if re-matching is desired, both users' swipe rows for the pair should be deleted on unmatch so they can appear in each other's feed again).

#### Verification

- [ ] `insertMatchIfAbsent` uses `ON CONFLICT DO UPDATE ... WHERE matches.status != 'active'`
- [ ] Re-matching after unmatch creates an ACTIVE match (status reactivated)
- [ ] Already-ACTIVE matches are not modified by concurrent `createMatch` calls
- [ ] Reactivated matches have cleared `unmatched_at`, `unmatched_by`, `conversation_started`, `message_count`
- [ ] `MatchLifecycleListener.onUserBlocked()` only processes ACTIVE matches
- [ ] `./mvnw compile` passes

---

## Phase 2 — Data Integrity Fixes (P1)

### - [x] Batch C — Fix Exception Swallowing in getGenresFromTopArtists

**Priority:** P1 | **Risk:** Medium | **Effort:** 1–2 hours
**Depends on:** Batch A (Batch A's TX fix is needed so that exception propagation does not roll back a long-held transaction)

#### Problem

`SpotifyServiceImpl.getGenresFromTopArtists()` catches ALL exceptions and returns `Collections.emptyList()`. The `getTopArtists()` call inside it is `@CircuitBreaker`-protected, so Spotify 5xx errors are recorded by the circuit breaker. However, the exception is swallowed before it reaches `SpotifyGenreSyncService.syncUserGenrePreferences()`.

**How it manifests:**

1. Spotify returns HTTP 500 for all 3 time ranges
2. `getTopArtists()` throws `SpotifyApiException` — circuit breaker records the failure
3. `getGenresFromTopArtists()` catches the exception → returns empty list
4. The `CompletableFuture` in `syncUserGenrePreferences` completes normally with an empty list
5. `failedRanges` counter never increments (no exception reached `future.get()`)
6. All 3 futures return empty lists → `allGenres` is empty → `allGenres.isEmpty()` → returns 0
7. `replaceSpotifyPreferences` is NOT called (early return before delete), so no data loss
8. But the method returns `0` (success with 0 genres) instead of throwing an error
9. The `failedRanges == 3` error path, which throws `SpotifyApiException`, is **dead code** for Spotify HTTP errors

**Impact:** Silent false success. Callers (controllers, UIs) believe the sync completed normally when Spotify was actually down. The circuit breaker still opens eventually, but the feedback loop from sync → caller is broken.

#### Files Affected

- `src/main/java/com/example/dating/services/impl/SpotifyServiceImpl.java` (modify)

#### Implementation Tasks

1. **Remove the catch-all `try/catch` in `getGenresFromTopArtists()`** — let exceptions from `getTopArtists()` propagate naturally
2. **Keep the `JsonProcessingException` handling** if parsing the response fails (this is a data issue, not an API issue) — either rethrow as `SpotifyApiException` or let it propagate
3. **Verify `SpotifyGenreSyncService.fetchGenresWithWeight()`** correctly handles the propagated exception — it already declares `throws JsonProcessingException` and the lambda wraps in `RuntimeException`
4. **Verify the `failedRanges` counter** now increments correctly when `future.get()` throws `ExecutionException` (wrapping the propagated `SpotifyApiException`)
5. **Verify the `failedRanges == 3` path** now fires when all 3 ranges fail, throwing `SpotifyApiException` to the caller
6. **Verify partial failures** (1 or 2 ranges fail) still produce a valid genre list from the successful range(s) — the existing `try/catch` around each `future.get()` in `syncUserGenrePreferences` handles this

#### Verification

- [ ] `getGenresFromTopArtists()` no longer has a catch-all exception handler
- [ ] When all 3 Spotify ranges return 500, `syncUserGenrePreferences` throws `SpotifyApiException`
- [ ] When 1 or 2 ranges fail, the method succeeds with genres from remaining range(s)
- [ ] Circuit breaker still records failures from `getTopArtists()` (unchanged behavior)
- [ ] `./mvnw compile` passes

---

### - [x] Batch D — Stabilize Pagination with Deterministic Candidate Ordering (2026-03-21)

**Priority:** P1 | **Risk:** Medium | **Effort:** 3–4 hours
**Depends on:** Nothing

#### Problem

`UserJpaRepository.findCandidateUsers()` uses `ORDER BY FUNCTION('RANDOM')` with `PageRequest.of(0, 500)`. Every call to `findPotentialMatches` re-executes `fetchCandidateData`, which re-runs this random query. Between page 1 (`offset=0`) and page 2 (`offset=20`), the 500 candidates returned can be **completely different**.

**How it manifests:**

1. User opens feed → page 1 request → `fetchCandidateData()` runs → 500 random candidates selected → first 20 returned
2. User scrolls → page 2 request → `fetchCandidateData()` runs again → **different** 500 random candidates
3. A high-scoring candidate in page 1's draw may not appear in page 2's draw → **user never sees them**
4. A candidate appearing in both draws gets shown twice → **duplicate cards in feed**
5. `total` count in `PotentialMatchPage` changes between requests → **UI shows flickering count**

**Impact:** Broken pagination contract. Users see inconsistent feeds with duplicates and missing candidates. Affects all users with more than 500 eligible candidates.

#### Files Affected

- `src/main/java/com/example/dating/repositories/UserJpaRepository.java` (modify JPQL query)
- `src/main/java/com/example/dating/services/matching/MatchRecommendationService.java` (modify to pass session seed)
- `src/main/java/com/example/dating/controllers/MatchingController.java` (accept/generate session seed)

#### Implementation Tasks

1. **Add a `sessionSeed` parameter** to the `findPotentialMatches` call chain:
   - Controller generates a random `long` seed on the first page request and returns it in the response
   - Client passes the same seed back for subsequent pages
   - If no seed provided, generate a new one (first page)
2. **Replace `ORDER BY FUNCTION('RANDOM')` in the JPQL query** with a deterministic-but-varied ordering:
   - Option A (PostgreSQL-specific): `ORDER BY hashtext(CONCAT(u.id, :seed))`
   - Option B (portable): `ORDER BY MOD(ABS(CAST(SUBSTRING(u.id, 1, 8) AS INTEGER) + :seed), 2147483647)`
   - Option C (simplest): Keep `RANDOM()` but cache the candidate list per-user with a short TTL (e.g., 5 minutes) so consecutive page requests use the same candidate pool
   - **Recommended: Option C** — least invasive, caches the 500 candidate IDs in Caffeine keyed by `userId`, expires after 5 minutes or on explicit invalidation
3. **If using Option C**: Add a `Caffeine<String, List<String>> candidateCache` to `MatchRecommendationService`. On cache hit, skip `fetchCandidateData()` candidate query and use cached IDs. On cache miss, execute query and populate cache.
4. **Add `sessionSeed` (or `feedSessionId`)** to `PotentialMatchPage` response DTO so the client can pass it back
5. **Invalidate candidate cache** when the user swipes (the swiped candidate should be excluded from future pages)

#### Verification

- [ ] Consecutive page requests return non-overlapping candidates (no duplicates)
- [ ] A candidate shown in page 1 does not appear again in page 2
- [ ] `total` count is consistent across page requests within the same session
- [ ] Swiped candidates are excluded from subsequent pages
- [ ] Cache expires after TTL — new session gets fresh candidates
- [ ] `./mvnw compile` passes

---

### - [ ] Batch E — Guard Account Deletion Against Concurrent FK Inserts

**Priority:** P1 | **Risk:** Medium | **Effort:** 2–3 hours
**Depends on:** Nothing

#### Problem

`AccountDeletionServiceImpl.deleteAccount()` acquires `SELECT FOR UPDATE` on the `UserEntity` row, then deletes swipes, matches, scores, preferences, profile, and finally the user entity. However, `FOR UPDATE` only blocks other `SELECT FOR UPDATE` on the same row — it does NOT prevent other transactions from **inserting** rows into `user_swipes` or `matches` that reference the user via string ID columns.

**How it manifests:**

1. Deletion thread: `findByIdForUpdate(userId)` → acquires `FOR UPDATE` lock
2. Deletion thread: `deleteAllSwipesInvolvingUser(userId)` → deletes all existing swipes
3. Swipe thread (concurrent): `SwipeService.recordSwipe()` → reads user entity (plain SELECT, NOT blocked by FOR UPDATE) → inserts new swipe referencing `userId`
4. Deletion thread: deletes user entity
5. If matching tables use JPA `@ManyToOne` FK constraints: deletion fails with FK violation, rolls back
6. If matching tables use string ID columns (no DB-level FK): orphaned swipe row remains, referencing a non-existent user
7. Current schema: matching entities reference users by string ID fields (`swiperId`, `swipedUserId`, `userAId`, `userBId`), not JPA relationships — so orphaned records are the likely outcome

**Impact:** Orphaned records in matching tables. Low probability (narrow race window), but no automatic cleanup exists.

#### Files Affected

- `src/main/java/com/example/dating/services/impl/AccountDeletionServiceImpl.java` (modify)
- `src/main/java/com/example/dating/services/matching/SwipeService.java` (modify — add deleted-user check)

#### Implementation Tasks

1. **Add a `deleted` boolean flag to `UserEntity`** (soft-delete marker):
   ```java
   @Column(name = "deleted", nullable = false)
   private boolean deleted = false;
   ```
2. **In `deleteAccount()`**: Set `deleted = true` and `flush()` BEFORE deleting matching entities. This ensures any concurrent swipe that reads the user entity will see the `deleted` flag.
3. **In `SwipeService.recordSwipe()`**: After loading both user entities, check `if (swipedUser.isDeleted()) throw new UserNotFoundException(...)`. The user was loaded in the same transaction, so the check is consistent.
4. **In `findCandidateUsers()` JPQL**: Add `AND u.deleted = false` to the WHERE clause to exclude soft-deleted users from recommendations.
5. **Keep the hard delete** at the end of `deleteAccount()` — the soft-delete flag is only a guard during the deletion window, not a permanent state. The user row is still physically deleted at the end of the transaction.
6. **Add a DB migration** (`V6__Add_Deleted_Flag.sql`) adding the `deleted` column with default `false`

#### Verification

- [ ] `UserEntity` has a `deleted` boolean column
- [ ] `deleteAccount()` sets `deleted = true` before cleaning up matching entities
- [ ] `SwipeService.recordSwipe()` rejects swipes targeting deleted users
- [ ] `findCandidateUsers()` excludes deleted users
- [ ] The user row is still physically deleted at the end of the `deleteAccount()` transaction
- [ ] Flyway migration adds the column correctly
- [ ] `./mvnw compile` passes

---

### - [ ] Batch E2 — Guard doUnmatch Against Already-UNMATCHED Matches

**Priority:** P1 | **Risk:** Medium | **Effort:** 1 hour
**Depends on:** Nothing

#### Problemr

`MatchService.doUnmatch()` unconditionally overwrites match state without checking the current status. It sets `status = UNMATCHED`, `unmatchedAt = now()`, `unmatchedBy = requestingUserId`, and publishes `MatchUnmatchedEvent` — even if the match is already UNMATCHED.

**How it manifests:**

1. User A and B are matched (status=ACTIVE)
2. User B unmatches through the UI → `doUnmatch(matchId, B)` → status=UNMATCHED, unmatchedBy=B, unmatchedAt=T1, version=1
3. User A blocks User B (doesn't know B already unmatched)
4. Block swipe commits → `UserBlockedEvent` fires asynchronously
5. `MatchLifecycleListener.onUserBlocked()` reads the match via `findMatchBetweenUsers` (returns match regardless of status)
6. The status check `if (match.getStatus() == MatchStatus.ACTIVE)` at `MatchLifecycleListener.java:57` operates on a **detached entity** from a short read-only TX. If the read happens before B's unmatch commits (or the async thread reads a stale snapshot), status appears ACTIVE → proceeds.
7. `doUnmatch(matchId, A)` runs:
   - `unmatchedBy` overwritten from B → A (wrong — B initiated the unmatch)
   - `unmatchedAt` overwritten from T1 → T2 (original timestamp lost)
   - A second `MatchUnmatchedEvent` published (duplicate notification)
   - version incremented unnecessarily (1 → 2)

**Impact:**
- `unmatchedBy` audit trail is corrupted — shows the blocker instead of the user who actually unmatched
- Duplicate `MatchUnmatchedEvent` → duplicate notifications when push notifications are implemented
- Unnecessary version increment could cause `OptimisticLockingFailureException` for other concurrent operations on the match

#### Files Affected

- `src/main/java/com/example/dating/services/matching/MatchService.java` (modify `doUnmatch`)

#### Implementation Tasks

1. **Add a status guard at the top of `doUnmatch()`**:
   ```java
   @Transactional
   public void doUnmatch(String matchId, String requestingUserId) {
       Match match = matchRepository.findById(matchId)
               .orElseThrow(() -> new MatchNotFoundException("Match not found: " + matchId));

       // Guard: already unmatched — skip to avoid overwriting audit fields
       if (match.getStatus() == MatchStatus.UNMATCHED) {
           log.debug("Match {} already unmatched, skipping", matchId);
           return;
       }

       boolean isParticipant = match.getUserA().getId().equals(requestingUserId)
               || match.getUserB().getId().equals(requestingUserId);
       // ... rest unchanged
   }
   ```
2. **No changes needed to `unmatch()` (the retry wrapper)** — it will simply succeed on the first attempt when the guard returns early
3. **No changes needed to `MatchLifecycleListener`** — the guard in `doUnmatch` makes the listener's stale-read harmless

#### Verification

- [ ] `doUnmatch()` returns early without modifying the match if status is already UNMATCHED
- [ ] No `MatchUnmatchedEvent` is published for already-UNMATCHED matches
- [ ] `unmatchedBy` and `unmatchedAt` are preserved from the original unmatch
- [ ] Version is not incremented when skipping
- [ ] `./mvnw compile` passes

---

## Phase 3 — UX & Correctness Fixes (P2)

### - [x] Batch F — Preserve Match Breakdown and Insights in Score Cache (2026-03-21)

**Priority:** P2 | **Risk:** Low | **Effort:** 3–4 hours
**Depends on:** Nothing

#### Problem

When scores are served from the `user_match_scores` cache, `MatchScoringService.buildMatchScoreFromCache()` reconstructs a `MatchScore` with only numeric fields. The `breakdown` (shared genres, overlap details) and `insights` (human-readable text) are always `null` for cache hits. This means:

- `topSharedGenres` in the response is always empty for cached matches
- `previewInsight` always falls back to the generic "Check out your compatibility!" text
- Since most requests serve from cache (scores are computed once and cached), the majority of users see degraded match cards with no personalized information

**How it manifests:**

1. First request: scores are computed live → `breakdown` and `insights` populated → user sees "You both love indie rock, jazz, and electronic"
2. Second request (same candidates): scores served from cache → `breakdown` is null → user sees "Check out your compatibility!"
3. Every subsequent request shows the generic text until cache expires

**Impact:** UX degradation on the core feature. Users lose the most engaging part of match cards (shared interests) after the first view.

#### Files Affected

- `src/main/java/com/example/dating/models/matching/dao/UserMatchScore.java` (add columns)
- `src/main/java/com/example/dating/services/matching/MatchScoringService.java` (modify cache write/read)
- `src/main/resources/db/migration/V7__Score_Cache_Breakdown.sql` (new)

#### Implementation Tasks

1. **Add two columns to `UserMatchScore`**:
   ```java
   @Column(name = "breakdown_json", columnDefinition = "TEXT")
   private String breakdownJson;

   @Column(name = "insights_json", columnDefinition = "TEXT")
   private String insightsJson;
   ```
2. **Serialize breakdown and insights as JSON** when persisting the score cache (in `persistScoreCache` or the upsert path)
3. **Deserialize in `buildMatchScoreFromCache()`** — reconstruct `MatchScore` with full `breakdown` and `insights` from the cached JSON
4. **Handle null gracefully** for pre-existing cache entries that lack JSON — fall back to current behavior (null breakdown/insights)
5. **Update the native upsert query** in `UserMatchScoreRepository` to include the new columns
6. **Add Flyway migration** `V7__Score_Cache_Breakdown.sql` adding the two TEXT columns

#### Verification

- [ ] `UserMatchScore` entity has `breakdownJson` and `insightsJson` columns
- [ ] Score cache writes serialize breakdown and insights
- [ ] `buildMatchScoreFromCache()` returns full `MatchScore` with breakdown and insights
- [ ] Pre-existing cache entries without JSON still work (null fallback)
- [ ] Flyway migration creates the columns
- [ ] `./mvnw compile` passes

---

### - [x] Batch G — Return 401 for Deleted Users' JWTs

**Priority:** P2 | **Risk:** Low | **Effort:** 1 hour
**Depends on:** Nothing

#### Problem

In the JWT decoder (`SecurityConfig`), when `dbVersion` is null (user deleted from DB or never existed), the token version check is skipped and the token is accepted. The request proceeds through the filter chain, and eventually a controller calls `findById(userId)` which throws `UserNotFoundException` → HTTP 404.

**How it manifests:**

1. User A has a valid JWT
2. User A's account is deleted
3. User A's JWT is still not expired
4. User A makes an API request → JWT passes decoder (version check skipped) → passes `EmailVerificationFilter` (user is null, so verification check is false → not blocked)
5. Controller calls `findById(userId)` → `UserNotFoundException` → 404
6. The 404 response leaks information: "User not found" tells an attacker the user existed but was deleted. A proper 401 would reveal nothing.

Additionally, the Caffeine cache for token versions (30s TTL) means that even after a password reset bumps the token version, old tokens remain valid for up to 30 seconds.

**Impact:** Information leakage (404 vs 401), and a 30-second post-password-reset vulnerability window.

#### Files Affected

- `src/main/java/com/example/dating/security/SecurityConfig.java` (modify JWT decoder)

#### Implementation Tasks

1. **In the JWT decoder**, when `dbVersion` is null (user not in DB), throw `BadJwtException("Invalid token")` instead of skipping the version check
2. **This rejects the token at the security filter level** → returns HTTP 401 with no information about whether the user existed
3. **Consider reducing the Caffeine TTL** from 30s to 5-10s for the token version cache to narrow the post-password-reset window (trade-off: more DB reads per request)

#### Verification

- [x] Deleted user's JWT returns 401, not 404
- [x] Response body does not contain "User not found" or any user-existence hint
- [x] Valid user's JWT still works normally
- [x] `./mvnw compile` passes

---

### - [x] Batch H — Add Reconciliation for Failed Block-Unmatch Operations

**Priority:** P2 | **Risk:** Low | **Effort:** 2–3 hours
**Depends on:** Nothing

#### Problem

When User A blocks User B, `SwipeService.recordSwipe()` publishes `UserBlockedEvent`. The `MatchLifecycleListener.onUserBlocked()` handler runs `@Async` + `AFTER_COMMIT`, and calls `matchService.unmatch()` to deactivate any active match between them. `unmatch()` has a 2-attempt retry loop for `OptimisticLockingFailureException`.

If both retry attempts fail (e.g., high contention on the `Match` row's `@Version` column), the exception hits the async thread's uncaught exception handler. The block swipe is already committed, but the match remains ACTIVE.

**How it manifests:**

1. User A blocks User B → swipe with action `"block"` committed
2. `UserBlockedEvent` fires asynchronously
3. `matchService.unmatch()` attempt 1: `OptimisticLockingFailureException` (concurrent modification)
4. `matchService.unmatch()` attempt 2: `OptimisticLockingFailureException` again
5. Exception propagates to `AsyncConfig` uncaught handler → logged as ERROR
6. User A blocked User B, but the match is still ACTIVE in the database
7. User B may still see User A in their matches list

**Impact:** Inconsistent state — blocked user still appears as a match. Requires manual intervention.

#### Files Affected

- New: `src/main/java/com/example/dating/services/matching/MatchReconciliationService.java`
- `src/main/resources/application.yml` (add scheduled task config if using `@Scheduled`)

#### Implementation Tasks

1. **Create `MatchReconciliationService`** with a `@Scheduled` method that runs periodically (e.g., every 5 minutes):
   ```java
   @Scheduled(fixedDelay = 300_000) // 5 minutes
   public void reconcileBlockedMatches() {
       // Find all block swipes where the blocked pair still has an ACTIVE match
       // For each: call matchService.unmatch()
   }
   ```
2. **Add a query to `UserSwipeRepository`** to find block swipes with active matches:
   ```java
   @Query("SELECT s FROM UserSwipe s WHERE s.action = 'block' AND EXISTS (SELECT m FROM Match m WHERE m.status = 'ACTIVE' AND ((m.userAId = s.swiperId AND m.userBId = s.swipedUserId) OR (m.userAId = s.swipedUserId AND m.userBId = s.swiperId)))")
   List<UserSwipe> findBlockSwipesWithActiveMatches();
   ```
3. **Process each result** by calling `matchService.unmatch()` — reuses existing retry logic
4. **Log reconciliation activity** — count of stale matches found and resolved per run
5. **Add `@EnableScheduling`** to the application configuration if not already present
6. **Limit batch size** to prevent long-running transactions (e.g., `Pageable` with limit 100)

#### Verification

- [ ] Scheduled job runs every 5 minutes
- [ ] Finds block swipes where the blocked pair still has an ACTIVE match
- [ ] Successfully unmatches stale active matches
- [ ] No effect when no stale matches exist (no-op)
- [ ] `./mvnw compile` passes

---

## Phase 4 — Resilience Improvements (P3)

### - [x] Batch I — Separate Spotify Circuit Breakers by Operation Type

**Priority:** P3 | **Risk:** Low | **Effort:** 2 hours
**Depends on:** Nothing

#### Problem

All Spotify operations share a single `@CircuitBreaker(name = "spotify")`:
- Token refresh (`JwtServiceImpl.refreshToken`)
- User profile fetch (`SpotifyServiceImpl.getCurrentUserProfile`)
- Top artists fetch (`SpotifyServiceImpl.getTopArtists`)
- Top tracks fetch (`SpotifyServiceImpl.getTopTracks`)
- Genre sync entry points (`SpotifyGenreSyncService.syncUserGenrePreferences`, `quickSyncUserGenrePreferences`)

A burst of failures in genre sync (e.g., 10 failed `getTopArtists` calls during a batch sync) opens the circuit and blocks **token refreshes** for unrelated users. Token refresh is critical infrastructure — blocking it means users can't authenticate for any Spotify-dependent feature during the open window (30s).

**How it manifests:**

1. Multiple users trigger genre sync simultaneously
2. Spotify rate-limits the top-artists endpoint → 10 failures within the sliding window
3. Circuit opens for `"spotify"` (all operations)
4. A different user needs a token refresh → `refreshToken()` short-circuits with `SpotifyApiException`
5. User cannot use any Spotify feature until the circuit transitions to half-open (30s)

**Impact:** Cascading failure from one Spotify operation type to all others. Token refresh (critical) blocked by genre sync (non-critical) failures.

#### Files Affected

- `src/main/resources/application.yml` (add new circuit breaker instances)
- `src/main/java/com/example/dating/services/impl/SpotifyServiceImpl.java` (change CB name)
- `src/main/java/com/example/dating/services/impl/JwtServiceImpl.java` (change CB name)
- `src/main/java/com/example/dating/services/matching/SpotifyGenreSyncService.java` (change CB name)

#### Implementation Tasks

1. **Define three circuit breaker instances** in `application.yml`:
   ```yaml
   resilience4j:
     circuitbreaker:
       instances:
         spotify-token:
           slidingWindowSize: 5
           failureRateThreshold: 60
           waitDurationInOpenState: 15s
           permittedNumberOfCallsInHalfOpenState: 2
           ignoreExceptions:
             - com.example.dating.exceptions.SpotifyTokenRevokedException
         spotify-data:
           slidingWindowSize: 10
           failureRateThreshold: 50
           waitDurationInOpenState: 30s
           permittedNumberOfCallsInHalfOpenState: 3
         spotify-sync:
           slidingWindowSize: 10
           failureRateThreshold: 50
           waitDurationInOpenState: 30s
           permittedNumberOfCallsInHalfOpenState: 3
   ```
2. **Update annotations**:
   - `JwtServiceImpl.refreshToken()` → `@CircuitBreaker(name = "spotify-token")`
   - `SpotifyServiceImpl.getCurrentUserProfile/getTopArtists/getTopTracks` → `@CircuitBreaker(name = "spotify-data")`
   - `SpotifyGenreSyncService.sync*/quickSync*` → `@CircuitBreaker(name = "spotify-sync")`
3. **Update fallback methods** to match the new CB names if needed (fallback signatures are per-method, not per-CB name)
4. **Move `ignoreExceptions` for `SpotifyTokenRevokedException`** to the `spotify-token` instance only (it's irrelevant for data/sync)
5. **Remove the old `spotify` instance** from the configuration

#### Verification

- [ ] Three separate circuit breaker instances configured
- [ ] Token refresh failures do not affect data or sync circuit breakers
- [ ] Genre sync failures do not affect token refresh circuit breaker
- [ ] `SpotifyTokenRevokedException` is still ignored (only on token CB)
- [ ] `./mvnw compile` passes
- [ ] Application starts with no Resilience4j config errors

---

### - [ ] Batch J — Harden Reverse Score Computation on Mutual Match Path

**Priority:** P3 | **Risk:** Low | **Effort:** 1–2 hours
**Depends on:** Nothing

#### Problem

In `SwipeService.recordSwipe()`, when a mutual match is detected, the code computes the reverse-direction score (B→A) via `recommendationService.getMatchScore(userB, aId)`. This call is wrapped in a try/catch — if it fails, `reverseMatchScore` stays null and the match is created with `matchScoreB = null`.

The `MatchDtoMapper.toDto()` falls back to `matchScore` (A→B) when `matchScoreB` is null. This means User B sees User A's directional score instead of their own. Since scores are directional (A→B ≠ B→A due to different `musicMatchImportance` and behavioral profiles), User B sees an incorrect score.

**How it manifests:**

1. User A likes User B → mutual match detected
2. `getMatchScore(B, A)` fails (e.g., cache miss + scoring error, or transient DB issue)
3. Logged at WARN, `reverseMatchScore = null`
4. Match created with `matchScore = 85.0` (A→B) and `matchScoreB = null`
5. User B queries their matches → DTO mapper sees `matchScoreB = null` → returns `matchScore = 85.0`
6. User B sees 85.0% compatibility, but their actual B→A score would have been 62.0%

**Impact:** Incorrect score shown to one user in the match. Misleading UX.

#### Files Affected

- `src/main/java/com/example/dating/services/matching/SwipeService.java` (modify)

#### Implementation Tasks

1. **Add a retry for reverse score computation** — wrap the `getMatchScore(userB, aId)` call in a simple 2-attempt retry (same pattern as `MatchService.unmatch()`):
   ```java
   Double reverseMatchScore = null;
   for (int attempt = 0; attempt < 2; attempt++) {
       try {
           reverseMatchScore = recommendationService.getMatchScore(userBDomain, aId);
           break;
       } catch (Exception e) {
           log.warn("Reverse score attempt {} failed for match {}->{}: {}",
               attempt + 1, bId, aId, e.getMessage());
       }
   }
   ```
2. **If both attempts fail, schedule an async backfill** — publish an event or submit an async task to compute and update `matchScoreB` after the transaction commits:
   ```java
   if (reverseMatchScore == null) {
       // Create match with null matchScoreB (current behavior)
       // After commit, async-fill the reverse score
   }
   ```
3. **Create an async backfill method** that loads the match, computes the reverse score, and updates `matchScoreB` via a native UPDATE query
4. **In `MatchDtoMapper.toDto()`**: When `matchScoreB` is null, instead of falling back to `matchScore`, return null or a sentinel value that the frontend can interpret as "score pending" — this is better than showing an incorrect score

#### Verification

- [ ] Reverse score has 2 retry attempts before giving up
- [ ] Failed reverse score triggers async backfill after commit
- [ ] `MatchDtoMapper` does not silently substitute A→B score for B→A when null
- [ ] Happy path (successful reverse score) is unchanged
- [ ] `./mvnw compile` passes

---

## Appendix: State Integrity Risk Matrix

| Invalid State | Can It Happen? | Cause | Batch |
|---|---|---|---|
| Match exists but one swipe missing | No | Advisory lock serializes pair; swipe precedes match in same TX | — |
| Behavioral profile updated without swipe | No | Event fires AFTER_COMMIT of swipe TX | — |
| Behavioral profile NOT updated after swipe | **Yes** | All 3 retries fail (OptimisticLockingFailureException) → silently dropped | Existing design (fire-and-forget) |
| Score cache appears fresh but based on stale entity | **Yes** | Async background scoring uses detached entity snapshots | Low severity, self-correcting |
| Duplicate match records | No | INSERT ON CONFLICT DO NOTHING + advisory lock | — |
| Orphaned matching records after deletion | **Yes** | Concurrent swipe insert during deletion window | Batch E |
| Match ACTIVE after block | **Yes** | Async unmatch failure (2 retries exhausted) | Batch H |
| Genre prefs from different syncs mixed | No | replaceSpotifyPreferences is atomic DELETE+INSERT in one TX | — |
| Second instance's lock deleted by first | **Yes** | TTL expiry + no owner tracking | Batch B |
| User sees wrong directional score | **Yes** | Reverse score computation failure → fallback to A→B score | Batch J |
| Deleted user's JWT accepted (404 vs 401) | **Yes** | Version check skipped when user not in DB | Batch G |
| Re-match returns UNMATCHED match after unmatch | **Yes** | INSERT ON CONFLICT DO NOTHING keeps old UNMATCHED row; findMatchBetweenUsers has no status filter | Batch B2 |
| unmatchedBy audit trail corrupted | **Yes** | doUnmatch overwrites fields on already-UNMATCHED match without status guard | Batch E2 |
| Connection pool exhausted during token refresh | **Yes** | @Transactional held during Spotify HTTP call in SpotifyTokenServiceImpl | Batch A2 |

## Appendix: "What Happens If…" Quick Reference

| Scenario | Outcome | Correct? |
|---|---|---|
| Two users swipe each other simultaneously | Advisory lock serializes; first creates match, second links to existing | Yes |
| Same user sends 3 rapid swipes (different targets) | Different pair keys → parallel; same target → DuplicateSwipeException | Yes |
| Redis goes down during lock | Falls back to local ConcurrentHashMap; cross-instance locking lost | Acceptable |
| Spotify API down for 1 minute | Circuit breaker opens after threshold; sync returns 0 silently (Batch C fixes) | Partially |
| DB TX fails after event publish | @TransactionalEventListener(AFTER_COMMIT) — events only fire on commit | Yes |
| Cache write fails (persistScoreCache) | Exception propagates; next request re-scores | Yes |
| Lock expires mid-operation | Another instance acquires; first's unlock deletes second's lock (Batch B fixes) | No |
| Account deleted while incoming swipe | Swipe may insert orphan row (Batch E fixes) | No |
| Users unmatch then re-match | INSERT ON CONFLICT DO NOTHING silently fails; UNMATCHED match returned as "new match"; invisible in active matches (Batch B2 fixes) | No |
| 10 users refresh Spotify tokens concurrently | 10 DB connections held for 10+ seconds each during HTTP calls; pool exhausted (Batch A2 fixes) | No |
| Block-triggered auto-unmatch runs on already-unmatched match | unmatchedBy/unmatchedAt overwritten, duplicate event published (Batch E2 fixes) | No |
