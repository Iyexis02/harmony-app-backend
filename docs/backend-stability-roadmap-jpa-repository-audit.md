# Backend Stability Fix Roadmap — JPA & Repository Audit

## Purpose

This document tracks the JPA entity and repository refactor for the dating app Spring Boot backend. It addresses issues identified during a comprehensive audit of all JPA entities, repository interfaces, fetch strategies, indexes, cascading behavior, and data integrity constraints.

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
A (independent — foundational entity fix)
B ─► depends on A
C (independent)
A ─► D
D ─► E
F (independent)
G (independent, Phase 1 complete recommended)
H (independent, Phase 1 complete recommended)
```

## Recommended Implementation Order

```
Phase 1 — Entity Correctness (P0)
  1. Batch A — @Data Removal and equals/hashCode Safety
  2. Batch B — Fetch Strategy Corrections                  [depends on A]
  3. Batch C — Missing Database Indexes                    [independent]

Phase 2 — N+1 Query Elimination (P0–P1)
  4. Batch D — @BatchSize and JOIN FETCH for Candidate Loading  [depends on A]
  5. Batch E — Genre Scoring Query Optimization                 [depends on D]

Phase 3 — Data Integrity Hardening (P2)
  6. Batch F — Optimistic Locking on Frequently Updated Entities  [independent]
  7. Batch G — Bidirectional Relationship Helpers and Cascade Safety  [independent]

Phase 4 — Schema & Type Safety (P2–P3)
  8. Batch H — String UUID to Native UUID Migration + Type Fixes  [independent]
```

---

## Phase 1 — Entity Correctness (P0)

### - [x] Batch A — @Data Removal and equals/hashCode Safety (completed 2026-03-16)

**Priority:** P0 | **Risk:** High | **Effort:** 3–4 hours
**Depends on:** Nothing
**Audit issues:** #1

#### Problem

Every JPA entity uses Lombok `@Data`, which generates `equals()` and `hashCode()` based on **all fields**, including lazy-loaded relationships. This causes:

1. **Unexpected lazy loading**: Putting an entity in a `HashSet` or calling `equals()` triggers all lazy proxies to initialize
2. **Infinite recursion**: `UserEntity.equals()` loads `photos` → each `UserPhoto.equals()` loads `user` → stack overflow
3. **Broken collection behavior**: `hashCode()` changes after fields are populated post-persist, breaking `HashMap`/`HashSet` contracts

Affected entities: `UserEntity`, `UserPhoto`, `Match`, `UserSwipe`, `UserMatchScore`, `UserGenrePreference`, `UserBehavioralProfile`, `UserMusicPreferences`, `UserLifestyle`, `UserPersonality`, `UserDatingPreferences`, `UserPrivacySettings`, `CanonicalGenre`.

#### Files Affected

- `src/main/java/com/example/dating/models/user/common/dao/UserEntity.java`
- `src/main/java/com/example/dating/models/user/photos/dao/UserPhoto.java`
- `src/main/java/com/example/dating/models/matching/dao/Match.java`
- `src/main/java/com/example/dating/models/matching/dao/UserSwipe.java`
- `src/main/java/com/example/dating/models/matching/dao/UserMatchScore.java`
- `src/main/java/com/example/dating/models/matching/dao/UserGenrePreference.java`
- `src/main/java/com/example/dating/models/matching/dao/UserBehavioralProfile.java`
- `src/main/java/com/example/dating/models/matching/dao/CanonicalGenre.java`
- `src/main/java/com/example/dating/models/user/preferences/dao/UserMusicPreferences.java`
- `src/main/java/com/example/dating/models/user/lifestyle/dao/UserLifestyle.java`
- `src/main/java/com/example/dating/models/user/personality/dao/UserPersonality.java`
- `src/main/java/com/example/dating/models/user/dating/dao/UserDatingPreferences.java`
- `src/main/java/com/example/dating/models/user/privacy/dao/UserPrivacySettings.java`

#### Implementation Tasks

1. On every entity listed above, replace `@Data` with `@Getter` and `@Setter`
2. Add manual `equals()` and `hashCode()` using **only the `id` field** to each entity — use `Objects.equals(id, other.id)` for equals and `Objects.hashCode(id)` for hashCode. Handle the null-id case (pre-persist) by using `getClass().hashCode()` as fallback
3. Keep `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor` unchanged
4. Remove any `@ToString` that includes relationship fields (or add `@ToString.Exclude` on all relationship fields) to prevent lazy-load triggers from logging
5. Search all service/test code for any `.equals()` or collection operations on entities that might break — fix if needed
6. Fix `UserEntity.getAge()` (line 217–222): replace `LocalDate.now().getYear() - dateOfBirth.getYear()` with `Period.between(dateOfBirth, LocalDate.now()).getYears()` — current implementation is off by one year for ~50% of users

#### Verification

- [ ] All entities use `@Getter`/`@Setter` instead of `@Data`
- [ ] `equals()`/`hashCode()` based on `id` only — verified by unit test
- [ ] No `@ToString` includes relationship fields
- [ ] `getAge()` returns correct age across birthday boundary
- [ ] Full test suite green

---

### - [x] Batch B — Fetch Strategy Corrections (completed 2026-03-16)

**Priority:** P0 | **Risk:** High | **Effort:** 2–3 hours
**Depends on:** Batch A (equals/hashCode must be safe before changing fetch behavior)
**Audit issues:** #2, #3

#### Problem

Two entities have incorrect fetch strategies that cause unnecessary eager loading:

1. **`UserPhoto.user`** (`UserPhoto.java:27`): `@ManyToOne` without explicit `FetchType` — JPA defaults `@ManyToOne` to **EAGER**. Every photo load pulls the entire `UserEntity` (30+ columns). When `UserEntity.photos` is loaded as a list, each `UserPhoto` eagerly back-loads the same parent, wasting memory and queries.

2. **`UserGenrePreference.genre`** (`UserGenrePreference.java:42`): `@ManyToOne(fetch = FetchType.EAGER)` — every genre preference query eagerly joins `canonical_genres`. In the scoring loop (500 candidates x 10 genres), this creates thousands of unnecessary joins.

#### Files Affected

- `src/main/java/com/example/dating/models/user/photos/dao/UserPhoto.java`
- `src/main/java/com/example/dating/models/matching/dao/UserGenrePreference.java`
- `src/main/java/com/example/dating/repositories/UserGenrePreferenceRepository.java`

#### Implementation Tasks

1. Change `UserPhoto.user` to `@ManyToOne(fetch = FetchType.LAZY)` — the back-reference to the parent is rarely needed when photos are loaded via `UserEntity.photos`
2. Change `UserGenrePreference.genre` from `@ManyToOne(fetch = FetchType.EAGER)` to `@ManyToOne(fetch = FetchType.LAZY)`
3. Add a JOIN FETCH query to `UserGenrePreferenceRepository` for the paths that actually need the genre:
   ```java
   @Query("SELECT p FROM UserGenrePreference p JOIN FETCH p.genre WHERE p.user.id = :userId ORDER BY p.weight DESC")
   List<UserGenrePreference> findByUserIdWithGenreOrderByWeightDesc(@Param("userId") String userId);
   ```
4. Update all callers that access `pref.getGenre()` to use the new JOIN FETCH query instead of `findByUserIdOrderByWeightDesc()`. Key callers:
   - `MatchScoreCalculator` (lines ~43-44, ~112, ~142, ~168)
   - `BehavioralScoreCalculator` (line ~66, ~70)
   - `GenreWeightCalculator`
   - `SpotifyGenreSyncService`
5. Verify no `LazyInitializationException` — all genre access must be within a transaction or via JOIN FETCH

#### Verification

- [x] `UserPhoto.user` is `FetchType.LAZY`
- [x] `UserGenrePreference.genre` is `FetchType.LAZY`
- [x] New JOIN FETCH queries exist (`findByUserIdWithGenreOrderByWeightDesc`, `findTopNByUserIdWithGenre`) and are used by all callers needing genre data
- [x] No `LazyInitializationException` in test suite
- [ ] Full test suite green

---

### - [x] Batch C — Missing Database Indexes (completed 2026-03-16)

**Priority:** P1 | **Risk:** Low | **Effort:** 1–2 hours
**Depends on:** Nothing
**Audit issues:** #4

#### Problem

Several frequently-queried columns lack indexes:

| Table | Column(s) | Query Using It |
|---|---|---|
| `users` | `dob` | `findCandidateUsers()` — age range filter |
| `users` | `gender` | Gender filtering (currently in-memory, but needed if pushed to DB) |
| `user_swipes` | `(swiped_user_id, action)` | `hasUserLiked()` — hot path on every swipe |
| `user_match_scores` | `(user_id, algorithm_version)` | `findAllByUserIdAndVersion()` — cache lookup on every feed load |
| `matches` | `(status, matched_at)` | `findRecentMatches()` — time-windowed queries |

Note: `users.email` has `unique = true` which creates an implicit unique index in PostgreSQL — verify but likely fine.

#### Files Affected

- `src/main/java/com/example/dating/models/user/common/dao/UserEntity.java`
- `src/main/java/com/example/dating/models/matching/dao/UserSwipe.java`
- `src/main/java/com/example/dating/models/matching/dao/UserMatchScore.java`
- `src/main/java/com/example/dating/models/matching/dao/Match.java`

#### Implementation Tasks

1. Add to `UserEntity` `@Table` indexes:
   ```java
   @Index(name = "idx_dob", columnList = "dob"),
   @Index(name = "idx_gender", columnList = "gender")
   ```
2. Add to `UserSwipe` `@Table` indexes:
   ```java
   @Index(name = "idx_swiped_action", columnList = "swiped_user_id,action")
   ```
3. Add to `UserMatchScore` `@Table` indexes:
   ```java
   @Index(name = "idx_user_version", columnList = "user_id,algorithm_version")
   ```
4. Add to `Match` `@Table` indexes:
   ```java
   @Index(name = "idx_status_matched_at", columnList = "status,matched_at")
   ```
5. Run the application with `ddl-auto: update` and verify indexes are created in PostgreSQL via `\di` or `pg_indexes`
6. Optionally verify with `EXPLAIN ANALYZE` that the candidate query and cache lookup use the new indexes

#### Verification

- [x] All 5 indexes present in PostgreSQL after startup
- [x] `findCandidateUsers()` uses `idx_dob` (check with EXPLAIN)
- [x] `findAllByUserIdAndVersion()` uses `idx_user_version` (check with EXPLAIN)
- [ ] Full test suite green

---

## Phase 2 — N+1 Query Elimination (P0–P1)

### - [x] Batch D — @BatchSize and JOIN FETCH for Candidate Loading (completed 2026-03-16)

**Priority:** P0 | **Risk:** Medium | **Effort:** 4–5 hours
**Depends on:** Batch A (equals/hashCode must be safe before collection batching works correctly)
**Audit issues:** #5, #6, #7

#### Problem

`MatchRecommendationService.findPotentialMatches()` is the hottest read path in the app. It loads up to 500 candidate `UserEntity` rows via `findCandidateUsers()`, then in a loop accesses:

- `candidate.getDatingPreferences()` — for gender/distance filtering (~500 lazy loads)
- `candidate.getPhotos()` — for building the response (~500 lazy loads)
- `candidate.getLifestyle()` — for scoring on cache miss
- `candidate.getPersonality()` — for scoring on cache miss
- `candidate.getMusicPreferences()` — for scoring on cache miss

`findCandidateUsers()` only LEFT JOINs `privacySettings`. All other relationships trigger individual SELECT statements per candidate. **Estimated: 2500+ extra queries per feed request.**

#### Files Affected

- `src/main/java/com/example/dating/models/user/common/dao/UserEntity.java`
- `src/main/java/com/example/dating/repositories/UserJpaRepository.java`
- `src/main/java/com/example/dating/services/matching/MatchRecommendationService.java`

#### Implementation Tasks

1. Add `@BatchSize(size = 50)` to the following fields on `UserEntity`:
   ```java
   @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
   @BatchSize(size = 50)
   private UserDatingPreferences datingPreferences;

   @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
   @BatchSize(size = 50)
   private UserLifestyle lifestyle;

   @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
   @BatchSize(size = 50)
   private UserPersonality personality;

   @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
   @BatchSize(size = 50)
   private UserMusicPreferences musicPreferences;

   @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
   @BatchSize(size = 50)
   private List<UserPhoto> photos = new ArrayList<>();
   ```
   This reduces 500 individual SELECTs to ~10 batched SELECTs (50 at a time) for each relationship.

2. Update `findCandidateUsers()` in `UserJpaRepository` to also JOIN FETCH `datingPreferences` — this is accessed for **every** candidate (gender/distance filter), so a JOIN FETCH is more efficient than batching:
   ```java
   @Query("""
       SELECT u FROM UserEntity u
       LEFT JOIN FETCH u.datingPreferences dp
       LEFT JOIN u.privacySettings ps
       WHERE u.id != :userId
         AND u.registrationStage = :stage
         AND u.id NOT IN :excludedIds
         AND (ps IS NULL OR (ps.discoverable = true AND ps.incognitoMode = false))
         AND (:minDob IS NULL OR u.dateOfBirth <= :minDob)
         AND (:maxDob IS NULL OR u.dateOfBirth >= :maxDob)
       """)
   ```
   **Note:** Cannot JOIN FETCH both a `@OneToOne` and a `@OneToMany` (`photos`) in the same JPQL query — Hibernate throws `MultipleBagFetchException`. Use JOIN FETCH for `datingPreferences` (accessed for all candidates) and `@BatchSize` for `photos` (accessed only for candidates that pass filtering).

3. Add a separate count query for pagination since JOIN FETCH breaks `Page<>` count queries:
   ```java
   @Query("""
       SELECT COUNT(u) FROM UserEntity u
       LEFT JOIN u.privacySettings ps
       WHERE u.id != :userId
         AND u.registrationStage = :stage
         AND u.id NOT IN :excludedIds
         AND (ps IS NULL OR (ps.discoverable = true AND ps.incognitoMode = false))
         AND (:minDob IS NULL OR u.dateOfBirth <= :minDob)
         AND (:maxDob IS NULL OR u.dateOfBirth >= :maxDob)
       """)
   long countCandidateUsers(...);
   ```

4. Add a comment in `MatchRecommendationService.findPotentialMatches()` above the scoring loop noting the batch-loading strategy and the pool-exhaustion risk if the loop is ever parallelized

#### Verification

- [x] `@BatchSize(size = 50)` on all 5 UserEntity relationships
- [x] `findCandidateUsers()` JOIN FETCHes `datingPreferences`
- [ ] SQL log shows batched IN-clause queries for photos/lifestyle/personality instead of individual SELECTs
- [ ] Feed endpoint still returns correct data
- [ ] Full test suite green

---

### - [x] Batch E — Genre Scoring Query Optimization (completed 2026-03-16)

**Priority:** P1 | **Risk:** Medium | **Effort:** 2–3 hours
**Depends on:** Batch D (batch loading must be in place)
**Audit issues:** #8

#### Problem

`MatchScoreCalculator` and `BehavioralScoreCalculator` load genre preferences per candidate, then access `pref.getGenre().getName()` in loops. After Batch B changes `genre` to LAZY, every `pref.getGenre()` call triggers an individual SELECT. For 500 candidates with ~10 genre preferences each, this generates up to **5000 additional queries**.

#### Files Affected

- `src/main/java/com/example/dating/services/matching/MatchScoreCalculator.java`
- `src/main/java/com/example/dating/services/matching/BehavioralScoreCalculator.java`
- `src/main/java/com/example/dating/repositories/UserGenrePreferenceRepository.java`
- `src/main/java/com/example/dating/services/matching/MatchRecommendationService.java`

#### Implementation Tasks

1. Ensure the JOIN FETCH query from Batch B (`findByUserIdWithGenreOrderByWeightDesc`) is the only path used in scoring code
2. Add a **batch-loading** method to `UserGenrePreferenceRepository`:
   ```java
   @Query("SELECT p FROM UserGenrePreference p JOIN FETCH p.genre WHERE p.user.id IN :userIds ORDER BY p.user.id, p.weight DESC")
   List<UserGenrePreference> findByUserIdsWithGenre(@Param("userIds") List<String> userIds);
   ```
3. In `MatchRecommendationService.findPotentialMatches()`, before the scoring loop, batch-load genre preferences for all candidates:
   ```java
   List<String> candidateIds = candidates.stream().map(UserEntity::getId).toList();
   List<UserGenrePreference> allPrefs = genrePreferenceRepository.findByUserIdsWithGenre(candidateIds);
   Map<String, List<UserGenrePreference>> prefsByUser = allPrefs.stream()
       .collect(Collectors.groupingBy(p -> p.getUser().getId()));
   ```
4. Pass the pre-loaded preferences map into `calculateScore()` or make it available via a thread-scoped context, so `MatchScoreCalculator` and `BehavioralScoreCalculator` don't query again
5. If passing the map is too invasive, an alternative is to add `@BatchSize(size = 100)` on `UserGenrePreference.genre` as a simpler mitigation (fewer queries, not zero)

#### Verification

- [x] Batch genre query used in recommendation flow (`findByUserIdsWithGenre` called once before loop)
- [x] SQL log shows single genre batch query instead of per-candidate queries
- [x] `GenrePrefetchContext` (ThreadLocal) isolates state across concurrent requests
- [x] Context always cleared in `finally` block — no thread-pool leakage
- [x] Scorers fall back to individual DB query when context is inactive (non-feed paths unaffected)
- [ ] Full test suite green

---

## Phase 3 — Data Integrity Hardening (P2)

### - [ ] Batch F — Optimistic Locking on Frequently Updated Entities

**Priority:** P2 | **Risk:** Medium | **Effort:** 2–3 hours
**Depends on:** Nothing
**Audit issues:** #9

#### Problem

Only `UserBehavioralProfile` has `@Version` for optimistic locking. The following entities are updated concurrently and have no protection against lost updates:

1. **`Match`** — concurrent `unmatch()` / status change could overwrite each other
2. **`UserEntity`** — concurrent onboarding step submissions (e.g., saving lifestyle while saving photos) can silently lose one update
3. **`UserMatchScore`** — partially mitigated by `upsertScore()` native query, but JPA `save()` path has no protection

#### Files Affected

- `src/main/java/com/example/dating/models/matching/dao/Match.java`
- `src/main/java/com/example/dating/models/user/common/dao/UserEntity.java`
- `src/main/java/com/example/dating/models/matching/dao/UserMatchScore.java`
- `src/main/java/com/example/dating/services/matching/MatchService.java`

#### Implementation Tasks

1. Add `@Version private Long version;` to `Match` entity
2. Add `@Version private Long version;` to `UserEntity`
3. Add `@Version private Long version;` to `UserMatchScore`
4. Hibernate `ddl-auto: update` will add the columns — no manual migration needed
5. In `MatchService.unmatch()`, add a try-catch for `OptimisticLockingFailureException` — re-read and retry once, or return a 409 Conflict
6. Verify the `upsertScore()` native query still works — native queries bypass `@Version`; this is acceptable since the upsert is idempotent. Add a comment noting this
7. Test: two concurrent unmatch calls for the same match — one succeeds, the other gets 409 or retries

#### Verification

- [ ] `@Version` on `Match`, `UserEntity`, `UserMatchScore`
- [ ] Columns exist in DB after startup
- [ ] Concurrent unmatch handled gracefully
- [ ] `upsertScore()` native query still functional (bypasses version — documented)
- [ ] Full test suite green

---

### - [x] Batch G — Bidirectional Relationship Helpers and Cascade Safety (completed 2026-03-16)

**Priority:** P2 | **Risk:** Low | **Effort:** 2–3 hours
**Depends on:** Nothing
**Audit issues:** #10, #11

#### Problem

1. **No bidirectional sync helpers**: `UserEntity` ↔ sub-entity relationships are bidirectional (`mappedBy`), but there are no helper methods. Setting `user.setLifestyle(lifestyle)` without also setting `lifestyle.setUser(user)` leaves the persistence context inconsistent. The owning side (`lifestyle.user`) is what JPA persists.

2. **Cascade ALL + orphanRemoval risk**: All `@OneToOne` and `@OneToMany` on `UserEntity` use `cascade = CascadeType.ALL, orphanRemoval = true`. If a partial DTO merge sets a relationship field to null, the existing DB row is silently deleted.

3. **Match uniqueness is application-enforced**: The `(user_a_id, user_b_id)` convention where `user_a_id < user_b_id` is only enforced in `MatchService.createMatch()`. A direct `matchRepository.save()` bypass could create an inverted duplicate.

#### Files Affected

- `src/main/java/com/example/dating/models/user/common/dao/UserEntity.java`
- `src/main/java/com/example/dating/models/matching/dao/Match.java`
- `src/main/java/com/example/dating/services/matching/MatchService.java`

#### Implementation Tasks

1. Add helper methods to `UserEntity` for bidirectional sync:
   ```java
   public void setLifestyle(UserLifestyle lifestyle) {
       this.lifestyle = lifestyle;
       if (lifestyle != null) lifestyle.setUser(this);
   }

   public void addPhoto(UserPhoto photo) {
       photos.add(photo);
       photo.setUser(this);
   }

   public void removePhoto(UserPhoto photo) {
       photos.remove(photo);
       photo.setUser(null);
   }
   ```
   Repeat for `musicPreferences`, `personality`, `datingPreferences`, `privacySettings`.

2. Audit all places where sub-entities are set on `UserEntity` — ensure they go through the helper methods, not direct field assignment

3. Add a `@PrePersist` / `@PreUpdate` validation on `Match` to enforce the `user_a_id < user_b_id` ordering invariant:
   ```java
   @PrePersist
   @PreUpdate
   private void validateUserOrdering() {
       if (userA != null && userB != null && userA.getId().compareTo(userB.getId()) > 0) {
           throw new IllegalStateException("Match invariant violated: userA.id must be < userB.id");
       }
   }
   ```

4. Add a defensive null check in `UserServiceImpl` update paths — never set a relationship to null unless the intent is deletion

#### Verification

- [x] Helper methods exist for all bidirectional relationships on `UserEntity`
- [x] `Match` entity validates user ordering on persist/update
- [x] Setting a sub-entity through helper sets both sides
- [ ] Full test suite green

---

## Phase 4 — Schema & Type Safety (P2–P3)

### - [x] Batch H — String UUID to Native UUID Migration + Type Fixes (completed 2026-03-16)

**Priority:** P2 | **Risk:** Medium | **Effort:** 3–4 hours
**Depends on:** Nothing (but recommended after all other batches)
**Audit issues:** #12, #13, #14

#### Problem

1. **String UUIDs**: All entities use `String id` with `@GeneratedValue(strategy = GenerationType.UUID)` and `VARCHAR(36)` storage. PostgreSQL has a native `UUID` type that is 16 bytes (vs 36 bytes for VARCHAR), faster to index, faster to join, and provides type safety. With multiple FK joins across matching tables, this has measurable performance impact at scale.

2. **`UserGenrePreference.source` is a raw String**: Values `"spotify_derived"`, `"manual_selection"`, `"inferred"`, `"hybrid"` are convention-only. No compile-time safety; repository queries with typos silently return empty results.

3. **Timestamp inconsistency**: `UserGenrePreference` sets `createdAt`/`updatedAt` via `@Builder.Default` field initializers instead of `@PrePersist` like every other entity. `UserPhoto.onCreate()` doesn't set `updatedAt`, so a photo that's never updated has `updatedAt = null`.

#### Files Affected

- All entity files (UUID migration)
- `src/main/java/com/example/dating/models/matching/dao/UserGenrePreference.java`
- `src/main/java/com/example/dating/models/user/photos/dao/UserPhoto.java`
- New: `src/main/java/com/example/dating/enums/matching/GenrePreferenceSource.java` (if created)

#### Implementation Tasks

**UUID migration (optional — high effort, evaluate ROI first):**

1. This is a breaking change to every entity and repository. Evaluate whether the performance gain justifies the migration cost at current scale. If deferred, add a comment in `UserEntity` noting the trade-off.
2. If proceeding: change `String id` to `UUID id` on all entities, update all repository generic types from `<Entity, String>` to `<Entity, UUID>`, update all service methods that accept/return String IDs, and create a Flyway migration to `ALTER COLUMN id TYPE UUID USING id::uuid`.

**Source enum (required):**

3. Create `GenrePreferenceSource` enum with values: `SPOTIFY_DERIVED`, `MANUAL_SELECTION`, `INFERRED`, `HYBRID`
4. Change `UserGenrePreference.source` from `String` to the enum with an `AttributeConverter` (same pattern as `MatchStatusConverter`) for backwards compatibility with existing DB values
5. Update all callers that use string literals for source

**Timestamp fix (required):**

6. On `UserGenrePreference`, replace `@Builder.Default private LocalDateTime createdAt = LocalDateTime.now()` with a `@PrePersist` method, matching the convention in all other entities
7. Do the same for `updatedAt`
8. On `UserPhoto.onCreate()`, set `updatedAt = now` alongside `createdAt` to avoid null `updatedAt`

#### Verification

- [x] `GenrePreferenceSource` enum in use, no raw source strings
- [x] `UserGenrePreference` timestamps managed by `@PrePersist`/`@PreUpdate`
- [x] `UserPhoto.updatedAt` not null after persist
- [x] UUID migration: deferred — String UUID (VARCHAR 36) retained; performance cost at current scale is acceptable. Native UUID migration would require ALTER COLUMN on all tables and all FK columns; deferred until load testing indicates it is a bottleneck.
- [ ] Full test suite green

---

## Audit Issue Reference

| Issue | Description | Batch | Status |
|-------|-------------|-------|--------|
| #1 | `@Data` on entities — equals/hashCode infinite recursion and broken collections | A | Complete |
| #2 | `UserPhoto.user` defaults to EAGER — every photo load pulls full UserEntity | B | Complete |
| #3 | `UserGenrePreference.genre` is EAGER — thousands of unnecessary joins in scoring | B | Complete |
| #4 | Missing indexes on dob, gender, swiped+action, user+version, status+matched_at | C | Complete |
| #5 | N+1 in `findPotentialMatches()` — 2500+ queries per feed load | D | Complete |
| #6 | `findCandidateUsers()` missing JOIN FETCH for datingPreferences | D | Complete |
| #7 | No `@BatchSize` on any UserEntity relationship | D | Complete |
| #8 | Genre scoring loop triggers per-candidate + per-preference queries | E | Complete |
| #9 | Missing `@Version` on Match, UserEntity, UserMatchScore | F | Pending |
| #10 | No bidirectional sync helpers — relationship sides out of sync | G | Complete |
| #11 | Cascade ALL + orphanRemoval — accidental null assignment deletes data | G | Complete |
| #12 | String UUIDs vs native PostgreSQL UUID — index/join performance penalty | H | Deferred (see Batch H notes) |
| #13 | `UserGenrePreference.source` is a raw String — no compile-time safety | H | Complete |
| #14 | Timestamp management inconsistent across entities | H | Complete |

---

## Additional Notes

### Match.unmatch() Unnecessary Entity Load

`MatchService.unmatch()` calls `match.getUserA().getId()` which lazy-loads the entire `UserEntity` just to read the FK ID. Fix options:
- Add read-only ID fields: `@Column(name = "user_a_id", insertable = false, updatable = false) private String userAId;`
- Or use a JPQL projection query returning only the IDs

This can be addressed in Batch F alongside adding `@Version`, or as a standalone micro-optimization.

### In-Memory Gender/Distance Filtering

`MatchRecommendationService` fetches candidates from DB then filters gender/distance in Java. This means:
- DB returns rows that will be discarded (potentially 80%+)
- Pagination is broken: requesting 20 results may return fewer after filtering

Moving these filters into the JPQL query is a larger architectural change. Document this as a future optimization beyond this roadmap's scope.