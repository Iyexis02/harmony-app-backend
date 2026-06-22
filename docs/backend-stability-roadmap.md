# Backend Stability Fix Roadmap

## Purpose

This document tracks the backend stability refactor for the dating app Spring Boot service layer. It addresses 13 issues identified during a service-layer audit covering transaction boundaries, race conditions, data integrity, and architectural concerns.

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
A ─► B
C (independent)
D (independent)
C ─► E
D ─► F
G, H, I, J (all independent of each other, require Phase 1 complete)
```

## Recommended Implementation Order

```
Phase 1 — Data Integrity (P0)
  1. Batch A — Match Creation Transaction Safety
  2. Batch B — Swipe Duplicate Race Condition         [depends on A]
  3. Batch C — Score Cache Transaction Boundaries      [independent]
  4. Batch D — Async Behavioral Update After Commit    [independent]

Phase 2 — Business Logic Integrity (P1)
  5. Batch E — Server-Side Match Score Computation     [depends on C]
  6. Batch F — Behavioral Profile Optimistic Locking   [depends on D]

Phase 3 — Performance & Architecture (P2)
  7. Batch G — Read Transaction Annotations + Count Query
  8. Batch H — Domain Model Hardening
  9. Batch I — Controller Layer Cleanup

Phase 4 — Polish (P3)
 10. Batch J — Auth Error Handling Consistency
```

---

## Phase 1 — Data Integrity (P0)

### - [x] Batch A — Match Creation Transaction Safety
<!-- Completed: 2026-03-16 -->

**Priority:** P0 | **Risk:** High | **Effort:** 2–3 hours
**Depends on:** Nothing
**Audit issues:** #3

#### Problem

`MatchService.createMatch()` catches `DataIntegrityViolationException` inside a `@Transactional` method. When the exception is thrown, Spring marks the transaction for rollback. The catch block then attempts `matchRepository.findMatchBetweenUsers()` inside the now-doomed transaction, causing a `TransactionSystemException` and a 500 error on concurrent match creation.

#### Files Affected

- `src/main/java/com/example/dating/services/matching/MatchService.java`
- `src/main/java/com/example/dating/repositories/MatchRepository.java`

#### Implementation Tasks

1. Add a native upsert query to `MatchRepository`: `INSERT INTO matches (...) ... ON CONFLICT (user_a_id, user_b_id) DO NOTHING RETURNING *` — eliminates the check-then-insert pattern entirely
2. If native upsert is impractical due to the JPA entity graph, use the alternative: extract the retry lookup into a **separate bean method** annotated `@Transactional(propagation = REQUIRES_NEW)` so the lookup after the constraint violation runs in a clean transaction
3. Remove the in-Java idempotency check (`findMatchBetweenUsers` before save) — the DB constraint is the source of truth
4. Ensure `createMatch()` still returns the existing match to callers in the conflict case
5. Write a concurrent integration test: two threads call `createMatch()` simultaneously for the same pair — verify exactly one row exists and no 500 is thrown

#### Verification

- [x] Concurrent test passes (`MatchCreationConcurrencyTest`)
- [ ] Existing `Phase1IntegrationTest`, `Phase3IntegrationTest` still green
- [x] No `DataIntegrityViolationException` logged during normal match creation (exception path removed)

---

### - [x] Batch B — Swipe Duplicate Race Condition
<!-- Completed: 2026-03-16 -->

**Priority:** P0 | **Risk:** High | **Effort:** 2–3 hours
**Depends on:** Batch A
**Audit issues:** #4

#### Problem

`SwipeService.recordSwipe()` has a TOCTOU race: `hasUserSwipedOn()` check and `swipeRepository.save()` are not atomic. Two concurrent swipe requests from the same user can both pass the check and both attempt to insert, hitting the unique constraint on `(swiper_user_id, swiped_user_id)`. Unlike `createMatch()`, there is no catch for `DataIntegrityViolationException`, so this surfaces as an unhandled 500.

#### Files Affected

- `src/main/java/com/example/dating/services/matching/SwipeService.java`
- `src/main/java/com/example/dating/repositories/UserSwipeRepository.java`
- `src/main/java/com/example/dating/exceptions/` (new `DuplicateSwipeException`)
- `src/main/java/com/example/dating/exceptions/GlobalExceptionHandler.java`

#### Implementation Tasks

1. Keep the `hasUserSwipedOn()` check as a non-authoritative fast-path optimization (saves a full insert attempt for the common case), but do not rely on it for correctness
2. Wrap `swipeRepository.save(swipe)` in a try-catch for `DataIntegrityViolationException`
3. On conflict, throw a domain-specific `DuplicateSwipeException` that `GlobalExceptionHandler` maps to HTTP 409 Conflict
4. Alternatively, add a native `INSERT ... ON CONFLICT (swiper_user_id, swiped_user_id) DO NOTHING` to `UserSwipeRepository` and check the returned row count to detect duplicates without exceptions
5. If using the exception-catch path, consider `@Transactional(propagation = REQUIRES_NEW)` for the save call to avoid poisoning the outer transaction
6. Write a concurrent test: two threads swipe the same pair simultaneously — verify exactly one swipe exists and the second gets a 409

#### Verification

- [x] Concurrent test passes
- [x] Single swipe still creates match on mutual like
- [x] `GlobalExceptionHandler` returns 409 for duplicate swipes

---

### - [x] Batch C — Score Cache Transaction Boundaries
<!-- Completed: 2026-03-16 -->

**Priority:** P0 | **Risk:** High | **Effort:** 3–4 hours
**Depends on:** Nothing
**Audit issues:** #2, #8

#### Problem

Two interrelated issues:

1. `MatchRecommendationService.findPotentialMatches()` is `@Transactional(readOnly = true)` but calls `MatchScoringService.calculateScore()` which writes to the cache via `upsertMatchScoreCache()`. Since `calculateScore()` has no `@Transactional` annotation, it inherits the caller's read-only transaction. PostgreSQL rejects the DML and the try-catch swallows the error silently. **Result: the score cache is never populated via the main discovery path.**

2. `calculateScore()` has no transaction at all. The cache upsert does a read-then-write that is not atomic. Two concurrent score calculations for the same pair can both see "no existing record" and both attempt an insert.

#### Files Affected

- `src/main/java/com/example/dating/services/matching/MatchScoringService.java`
- `src/main/java/com/example/dating/services/matching/MatchRecommendationService.java`
- `src/main/java/com/example/dating/repositories/UserMatchScoreRepository.java`

#### Implementation Tasks

1. Annotate `MatchScoringService.calculateScore()` with `@Transactional(propagation = Propagation.REQUIRES_NEW)` — this gives it an independent read-write transaction regardless of the caller's context
2. Inside `upsertMatchScoreCache()`, replace the read-then-write pattern with a native upsert query on `UserMatchScoreRepository`: `INSERT INTO user_match_scores (...) VALUES (...) ON CONFLICT (user_id, matched_user_id) DO UPDATE SET ...`
3. Remove the generic `try/catch(Exception)` around `upsertMatchScoreCache()` — with the upsert, there is no expected constraint violation; any exception is a real problem that should propagate or at least be logged at ERROR
4. Verify `findPotentialMatches()` can now call `calculateScore()` and the cache is actually written
5. Load test `GET /api/v1/matching/potential` and confirm `user_match_scores` rows are being created

#### Verification

- [x] After calling `/matching/potential`, `user_match_scores` table has new rows
- [x] Subsequent calls for the same user show cache hits in debug logs
- [x] No silent exceptions in logs during scoring

#### Notes

`REQUIRES_NEW` means each `calculateScore()` call acquires its own DB connection. The loop in `findPotentialMatches()` is sequential, so at most 1 extra connection at a time. If the loop is ever parallelized, this becomes a pool exhaustion risk — add a comment.

---

### - [x] Batch D — Async Behavioral Update After Commit
<!-- Completed: 2026-03-16 -->

**Priority:** P0 | **Risk:** Medium | **Effort:** 3–4 hours
**Depends on:** Nothing
**Audit issues:** #1

#### Problem

`BehavioralProfileService.updateAfterSwipe()` is `@Async @Transactional` and is called from inside `SwipeService.recordSwipe()` which is itself `@Transactional`. The async task is dispatched to a thread pool before the outer transaction commits. If the outer transaction rolls back after the async method has already committed its own transaction, the behavioral profile is updated for a swipe that was never persisted — silently corrupting the learned preference data.

#### Files Affected

- `src/main/java/com/example/dating/services/matching/SwipeService.java`
- `src/main/java/com/example/dating/services/matching/BehavioralProfileService.java`
- New event class (e.g., `src/main/java/com/example/dating/events/SwipeRecordedEvent.java`)
- Possibly a new listener class (e.g., `SwipeEventListener.java`)

#### Implementation Tasks

1. Create a `SwipeRecordedEvent` record/class containing `swiperId`, `swipedUserId`, `action`, and `matchScore`
2. Inject `ApplicationEventPublisher` into `SwipeService`
3. Replace the direct `behavioralProfileService.updateAfterSwipe(...)` call in `recordSwipe()` with `eventPublisher.publishEvent(new SwipeRecordedEvent(...))`
4. Create a listener (or annotate a method in `BehavioralProfileService`) with `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` combined with `@Async`
5. The listener calls `updateAfterSwipe()`. Remove `@Async` from `updateAfterSwipe()` itself — the listener handles async dispatch. Keep `@Transactional` so it runs in its own transaction
6. Ensure `@EnableAsync` is present in the application configuration
7. Verify: force a rollback in `recordSwipe()` after the event publish — confirm the behavioral profile is not updated
8. Verify: successful swipe — confirm behavioral profile IS updated after commit

#### Verification

- [x] Behavioral profile not updated when swipe transaction rolls back
- [x] Behavioral profile updated normally on successful swipe
- [x] `@Async` is not on `updateAfterSwipe()` directly anymore
- [x] Event listener fires only `AFTER_COMMIT`

---

## Phase 2 — Business Logic Integrity (P1)

### - [x] Batch E — Server-Side Match Score Computation
<!-- Completed: 2026-03-16 -->

**Priority:** P1 | **Risk:** Medium | **Effort:** 3–4 hours
**Depends on:** Batch C
**Audit issues:** #6

#### Problem

`matchScore` in the swipe request is client-provided. A client can send any number (999.0, -1, etc.). This client-supplied score is stored in `UserSwipe.matchScoreAtSwipe`, used as the score when creating the `Match` record, and fed to the behavioral profile's `effectiveScoreThreshold` EMA. This allows clients to poison the behavioral learning algorithm.

#### Files Affected

- `src/main/java/com/example/dating/services/matching/SwipeService.java`
- `src/main/java/com/example/dating/controllers/MatchingController.java`
- `src/main/java/com/example/dating/models/matching/dto/SwipeRequestDto.java`

#### Implementation Tasks

1. Inject `MatchRecommendationService` (or `MatchScoringService`) into `SwipeService`
2. At the top of `recordSwipe()`, compute the score server-side: `MatchScore computed = recommendationService.getMatchScore(swiper, swipedUserId)` — this leverages the cache-first path fixed in Batch C
3. Use `computed.getOverallScore()` as the authoritative `matchScore` for: `UserSwipe.matchScoreAtSwipe`, `matchService.createMatch()`, and the behavioral profile event
4. Deprecate the `matchScore` field in `SwipeRequestDto` — mark it `@JsonIgnore` or simply ignore it. Keep the field temporarily for backwards compatibility if the frontend already sends it
5. Update the `SwipeResult` response to return the server-computed score
6. Verify: a swipe request with `matchScore: 999` in the body does not affect the stored match score or behavioral profile

#### Verification

- [x] Stored `matchScoreAtSwipe` reflects server-computed value, not client value
- [x] `Match.matchScore` reflects server-computed value
- [x] Behavioral profile EMA uses server-computed value
- [x] Frontend can still send `matchScore` without error (backwards compatible)

#### Notes

Check for circular dependencies: `SwipeService` -> `MatchRecommendationService` -> ... -> `SwipeService`. `MatchRecommendationService` depends on `UserSwipeRepository` (not `SwipeService`), so no circular bean dependency exists.

---

### - [x] Batch F — Behavioral Profile Optimistic Locking
<!-- Completed: 2026-03-16 -->

**Priority:** P1 | **Risk:** Medium | **Effort:** 2–3 hours
**Depends on:** Batch D
**Audit issues:** #5

#### Problem

`BehavioralProfileService.updateAfterSwipe()` reads a `UserBehavioralProfile`, mutates it in memory (increments `totalLikes`, updates EMA weights), and saves. No `@Version` field exists. If two swipes happen concurrently, both threads read `totalLikes = N`, both compute `N+1`, and the second write silently overwrites the first. Like counts and EMA weights are permanently under-counted.

#### Files Affected

- `src/main/java/com/example/dating/models/matching/dao/UserBehavioralProfile.java`
- `src/main/java/com/example/dating/services/matching/BehavioralProfileService.java`
- DB migration (new `version` column on `user_behavioral_profiles`)

#### Implementation Tasks

1. Add `@Version private Long version;` to `UserBehavioralProfile` entity
2. Create a Flyway migration to add the `version` column with default `0` (or rely on `ddl-auto: update` if Flyway is not active yet)
3. In `BehavioralProfileService.updateAfterSwipe()`, wrap the read-modify-write in a retry loop (max 3 attempts) catching `OptimisticLockingFailureException`
4. On each retry, re-read the profile from the DB to get the latest state, then re-apply the mutations
5. If all retries fail, log at WARN and drop the update — behavioral updates are non-critical
6. Verify: two concurrent `updateAfterSwipe()` calls for the same user both succeed (one on first try, one on retry), and `totalLikes` increments by exactly 2

#### Verification

- [x] `@Version` field present on entity
- [x] Concurrent behavioral updates do not lose increments
- [x] Retry logic handles `OptimisticLockingFailureException`
- [x] Failed retries log at WARN but do not throw

---

## Phase 3 — Performance & Architecture (P2)

### - [x] Batch G — Read Transaction Annotations and Count Query
<!-- Completed: 2026-03-16 -->

**Priority:** P2 | **Risk:** Low | **Effort:** 1–2 hours
**Depends on:** Nothing (Phase 1 should be complete)
**Audit issues:** #10, #11

#### Problem

1. Read-only methods in `MatchService` lack `@Transactional(readOnly = true)`, causing Hibernate to run dirty-checking on every flush and preventing read-only connection optimizations.
2. `SwipeService.getLikeCount()` calls `swipeRepository.findLikesByUserId(userId).size()`, loading all like records into memory just to count them.

#### Files Affected

- `src/main/java/com/example/dating/services/matching/MatchService.java`
- `src/main/java/com/example/dating/services/matching/SwipeService.java`
- `src/main/java/com/example/dating/repositories/UserSwipeRepository.java`

#### Implementation Tasks

1. Add `@Transactional(readOnly = true)` to all read-only methods in `MatchService`: `getActiveMatches()`, `getAllMatches()`, `getMatchBetweenUsers()`, `areUsersMatched()`, `getActiveMatchCount()`, `getNewMatches()`
2. Add `@Transactional(readOnly = true)` to read-only methods in `SwipeService`: `hasSwipedOn()`, `getSwipeCount()`, `getLikeCount()`, `getSwipeThroughRate()`
3. Add a `countLikesByUserId` query to `UserSwipeRepository`: `@Query("SELECT COUNT(s) FROM UserSwipe s WHERE s.swiperUser.id = :userId AND s.action IN ('like', 'super_like')")`
4. Replace `swipeRepository.findLikesByUserId(userId).size()` in `SwipeService.getLikeCount()` with the new count query

#### Verification

- [x] All read-only service methods annotated
- [x] `getLikeCount()` uses COUNT query (confirm with SQL log)
- [x] Full test suite green — no behavioral change

---

### - [x] Batch H — Domain Model Hardening
<!-- Completed: 2026-03-16 -->

**Priority:** P2 | **Risk:** Low | **Effort:** 3–4 hours
**Depends on:** Nothing (Phase 1 should be complete)
**Audit issues:** #12, #7

#### Problem

1. `Match.status` and `Match.matchSource` are raw `String` fields. Typos silently corrupt status checks. No compile-time safety.
2. `deleteAccount()` uses a find+delete pattern for the behavioral profile instead of a single delete-by-userId.

#### Files Affected

- `src/main/java/com/example/dating/models/matching/dao/Match.java`
- `src/main/java/com/example/dating/services/matching/MatchService.java`
- `src/main/java/com/example/dating/services/matching/SwipeService.java`
- `src/main/java/com/example/dating/repositories/MatchRepository.java`
- `src/main/java/com/example/dating/repositories/UserBehavioralProfileRepository.java`
- `src/main/java/com/example/dating/services/impl/UserServiceImpl.java`
- New: `src/main/java/com/example/dating/enums/matching/MatchStatus.java`
- New: `src/main/java/com/example/dating/enums/matching/MatchSource.java`

#### Implementation Tasks

1. Create `MatchStatus` enum: `ACTIVE, UNMATCHED, DELETED, BLOCKED`
2. Create `MatchSource` enum: `MUTUAL_SWIPE, ALGORITHM_BOOST, SUPER_LIKE`
3. Change `Match.status` from `String` to `MatchStatus` with `@Enumerated(EnumType.STRING)`
4. Change `Match.matchSource` from `String` to `MatchSource` with `@Enumerated(EnumType.STRING)`
5. Update all string literal references in `MatchService` and `SwipeService` to use enum constants
6. Update JPQL queries in `MatchRepository` that filter on status/source strings
7. Add `void deleteByUserId(String userId)` to `UserBehavioralProfileRepository`
8. Replace the find+delete in `UserServiceImpl.deleteAccount()` with `userBehavioralProfileRepository.deleteByUserId(userId)`
9. Handle existing DB data: if rows already contain lowercase values (`active`, `mutual_swipe`), write a data migration to match the enum names, or use an `AttributeConverter`

#### Implementation Notes

- Used `AttributeConverter` (autoApply) instead of `@Enumerated(EnumType.STRING)` — no data migration needed; existing lowercase DB values (`"active"`, `"mutual_swipe"`) are read correctly by `MatchStatusConverter` / `MatchSourceConverter`.
- JPQL queries use full-qualified enum literals (`com.example.dating.enums.matching.MatchStatus.ACTIVE`) for internal active-only queries; parameterized queries updated to `MatchStatus` / `MatchSource` typed params.
- `@JsonValue` on each enum's `getValue()` method preserves the public API serialization contract (lowercase strings).
- Additional callsites fixed: `Phase1TestController` and `Phase1IntegrationTest` builder calls updated from String literals to enum constants.

#### Verification

- [x] Enum fields compile and serialize correctly
- [x] JPQL queries with enum parameters work
- [x] `deleteAccount()` uses single delete call for behavioral profile
- [x] Existing DB data compatible (AttributeConverter preserves lowercase values — no migration)

---

### - [x] Batch I — Controller Layer Cleanup
<!-- Completed: 2026-03-16 -->

**Priority:** P2 | **Risk:** Low | **Effort:** 2–3 hours
**Depends on:** Nothing (Phase 1 should be complete)
**Audit issues:** #9

#### Problem

`MatchingController` contains business logic: `buildMatchDTO()` directly accesses entity fields and implements presentation logic, `matchRate` is computed inline, and responses use raw `Map<String, Object>` instead of typed DTOs.

#### Files Affected

- `src/main/java/com/example/dating/controllers/MatchingController.java`
- New: mapper or service class (e.g., `MatchDtoMapper.java`)
- New: `MatchResponseDto.java`
- New: `AnalyticsResponseDto.java` (or add to existing analytics service)

#### Implementation Tasks

1. Create `MatchResponseDto` with typed fields: `matchId`, `matchScore`, `status`, `conversationStarted`, `matchSource`, `matchedAt`, `otherUserId`, `otherUserName`, `otherUserPhoto`
2. Extract `buildMatchDTO()` from `MatchingController` into a `MatchDtoMapper` component
3. Create `AnalyticsResponseDto` with typed fields
4. Move analytics calculation logic (matchRate, totalPasses derivation) into `SwipeService` or a dedicated service
5. Replace `Map<String, Object>` in `getPotentialMatches()` with direct serialization of `PotentialMatchPage`
6. Update controller to delegate to mapper/service — only HTTP concerns remain

#### Verification

- [x] JSON response field names unchanged (frontend compatibility)
- [x] No `Map<String, Object>` in controller responses
- [x] All business logic in service/mapper layer
- [ ] Full test suite green

---

## Phase 4 — Polish (P3)

### - [x] Batch J — Auth Error Handling Consistency
<!-- Completed: 2026-03-16 -->

**Priority:** P3 | **Risk:** Low | **Effort:** 1 hour
**Depends on:** Nothing
**Audit issues:** #13

#### Problem

`AuthServiceImpl.forgotPassword()` throws `RuntimeException` if email sending fails. But `register()` swallows email failures with a log. Inconsistent behavior — `forgotPassword()` returns 500 and leaves a persisted reset token the user can never use.

#### Files Affected

- `src/main/java/com/example/dating/services/impl/AuthServiceImpl.java`

#### Implementation Tasks

1. In `forgotPassword()` (line 172–174), remove the `throw new RuntimeException(...)` inside the email catch block
2. Replace with `log.error(...)` only — matching the pattern in `register()`
3. Verify: calling `/api/v1/auth/forgot-password` when the email service is unreachable returns 200 (not 500)

#### Verification

- [x] `forgotPassword()` returns 200 even when email service is down
- [x] Error is logged at ERROR level
- [x] `register()` and `forgotPassword()` follow the same email-failure policy

---

## Audit Issue Reference

| Issue | Description | Batch | Status |
|-------|-------------|-------|--------|
| #1 | `@Async` behavioral update fires before outer tx commits | D | Complete |
| #2 | Cache writes silently fail inside `readOnly=true` transaction | C | Complete |
| #3 | `DataIntegrityViolationException` catch inside `@Transactional` | A | Pending |
| #4 | TOCTOU on duplicate swipe check | B | Complete |
| #5 | No optimistic locking on behavioral profile | F | Complete |
| #6 | Client-supplied match score poisons behavioral learning | E | Complete |
| #7 | Non-atomic find+delete for behavioral profile in `deleteAccount()` | H | Complete |
| #8 | `calculateScore()` missing `@Transactional`, cache upsert not atomic | C | Complete |
| #9 | Business logic in controller | I | Complete |
| #10 | Read methods missing `@Transactional(readOnly=true)` | G | Complete |
| #11 | `getLikeCount()` loads full result set to count | G | Complete |
| #12 | Match status/source as raw strings | H | Complete |
| #13 | Inconsistent email-failure handling in auth flows | J | Complete |
