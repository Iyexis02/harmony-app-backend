# Matching Algorithm — Thesis Evaluation and Reference

**Project:** Music-aware dating application backend
**Subject:** Hybrid content-based recommender with online behavioural personalisation
**Algorithm version under evaluation:** v2.0
**Document purpose:** Source material and structural reference for a ~30-page master's thesis on the design, implementation, and evaluation of the matching algorithm.

---

## Table of contents

1. [Executive summary](#1-executive-summary)
2. [Algorithm description (mathematical)](#2-algorithm-description-mathematical)
3. [System architecture](#3-system-architecture)
4. [Data model](#4-data-model)
5. [End-to-end pipeline](#5-end-to-end-pipeline)
6. [Caching, freshness and concurrency](#6-caching-freshness-and-concurrency)
7. [Recommended thesis structure](#7-recommended-thesis-structure)
8. [Strengths to defend](#8-strengths-to-defend)
9. [Critical weaknesses to address](#9-critical-weaknesses-to-address)
10. [Evaluation methodology](#10-evaluation-methodology)
11. [Recommended improvements (ranked)](#11-recommended-improvements-ranked)
12. [Related work and citations](#12-related-work-and-citations)
13. [Glossary of variables and constants](#13-glossary-of-variables-and-constants)

---

## 1. Executive summary

The system is a **hybrid recommender**: a content-based scorer combined with an online-learned behavioural personalisation layer, gated by hard categorical filters and served through a directional, versioned cache.

It is **not** a single algorithm. It is a six-stage pipeline:

1. Hard candidate retrieval (SQL filters + in-memory gender/distance filters)
2. Per-dimension content scores (music, lifestyle, interests, location)
3. Profile fusion via user-controlled importance weight
4. Behavioural personalisation via EMA-learned genre centroid and cosine similarity
5. Confidence-weighted blending of profile and behavioural scores
6. Adaptive minimum-score threshold via EMA of accepted scores

Compared to typical academic dating-app recommenders the work has three legitimately novel features:

- **Directional scoring** — A→B ≠ B→A by construction, because both the importance weight and the behavioural profile are per-user.
- **Adaptive online learning rate** — `α = max(0.20, 1/(n+1))`, a floored Robbins–Monro schedule that retains responsiveness to taste drift.
- **Confidence-blended hybridisation** — behavioural contribution is `min(1.0, totalLikes/50) × 0.40`, smoothly interpolating from a pure content-based system at cold start to a 60/40 hybrid at maturity.

The principal academic weaknesses are: the music-overlap formula is hand-engineered and non-monotonic; lifestyle weights have no documented derivation; only positive feedback is used; and the system has not been empirically evaluated on standard recommender metrics.

---

## 2. Algorithm description (mathematical)

This section is written so the formulas can be lifted into Chapter 4 of the thesis with minimal editing.

### 2.1 Notation

| Symbol | Meaning |
|---|---|
| `u, v` | Users; `u` is the *viewing* user, `v` the *candidate* |
| `G_u ⊆ G` | Set of music genres for user `u`, with weights `w_u(g) ∈ [0,1]` |
| `I_u ⊆ I` | Set of normalised interest tags for user `u` |
| `d(u,v)` | Haversine distance in km between user locations |
| `m_u ∈ [0,100]` | `musicMatchImportance` slider value for user `u` |
| `c_u ∈ [0,1]` | Behavioural confidence for user `u` |
| `β_u ∈ ℝ^{|G|}` | Learned genre centroid for user `u` |
| `S_x(u,v) ∈ [0,100]` | Score of dimension `x` between users `u` and `v` |

### 2.2 Music score `S_music(u,v)`

Defined in `MatchScoreCalculator.calculateMatchScore`.

Let `S = G_u ∩ G_v` be the shared genres. For each `g ∈ S`:

```
overlap(g)    = min(w_u(g), w_v(g))
similarity(g) = 1 − |w_u(g) − w_v(g)|
```

Three sub-scores:

```
overlapScore   = (avg_{g∈S} overlap(g)) · 0.7 + coverage · 0.3,
   where coverage = |S| / (|G_u| + |G_v| − |S|)

weightSimScore = avg_{g∈S} similarity(g)

diversityScore = min(|G_u|, |G_v|) / max(|G_u|, |G_v|)
```

Final music score (scaled to `[0,100]`):

```
S_music = 100 · (0.70 · overlapScore + 0.20 · weightSimScore + 0.10 · diversityScore)
```

If either user has no genre preferences, `S_music = 0` and the breakdown is zeroed.

### 2.3 Lifestyle score `S_life(u,v)`

Defined in `LifestyleScoreCalculator.calculate`. A weighted rule-based combination:

```
S_life = 0.40·goalScore + 0.30·kidsScore + 0.15·smokingScore + 0.15·drinkingScore
```

Component scores are looked up from compatibility matrices (full tables in `LifestyleScoreCalculator.java`). Salient values:

- **Goals.** Same goal: 100. Both in the SERIOUS bucket: 85. Both in the CASUAL bucket: 85. Either side flexible: 55. SERIOUS × CASUAL: 10.
- **Kids.** Same: 100. WANTS_KIDS × DOESNT_WANT_KIDS: 5. Flexible side: 65. WANTS_KIDS × HAS_KIDS_WANTS_MORE: 80.
- **Smoking.** Same: 100. NON_SMOKER × REGULAR_SMOKER: 10. Other combinations interpolate.
- **Drinking.** Encoded ordinally (0–3) with score `100 − 25·|Δ|·k`, k chosen by ordinal distance.

### 2.4 Interests score `S_int(u,v)`

Jaccard similarity over normalised interest tag sets:

```
S_int = max(10, 100 · |I_u ∩ I_v| / |I_u ∪ I_v|)
```

Returns 50 if either user has no interests. Normalisation pipeline:

1. Lowercase + trim
2. Apply hand-coded `SYNONYM_MAP` (≈50 entries — `hike → hiking`, `films → movies`, `gym → fitness`, …)
3. Strip a single trailing `s` if `len > 3` and not ending in `ss`

### 2.5 Location score `S_loc(u,v)`

Linear decay over the *minimum* of both users' max-distance preferences:

```
distMax = min(maxDist_u, maxDist_v),  default 100 km if absent
S_loc   = clip(100 · (1 − d(u,v) / distMax), 0, 100)
```

Returns 50 if either user has no coordinates.

### 2.6 Profile fusion (directional, importance-driven)

Defined in `MatchScoringService.calculateScore`.

```
musicWeight     = 0.30 + (m_u / 100) · 0.50              ∈ [0.30, 0.80]
remaining       = 1 − musicWeight
lifestyleWeight = remaining · 0.45
interestsWeight = remaining · 0.30
locationWeight  = remaining · 0.25

S_profile(u,v) = musicWeight·S_music + lifestyleWeight·S_life
              + interestsWeight·S_int + locationWeight·S_loc
```

The dependence on `m_u` (and only `m_u`) is what makes the score directional.

### 2.7 Behavioural component

#### 2.7.1 Centroid update (after a like by `u` on `v`)

Defined in `BehavioralProfileService.doUpdateAfterSwipe`. Let `n` be `totalLikes` *before* this like, `α = max(0.20, 1/(n+1))`, and `P = G_v` the genres present in the liked user.

For each genre `g ∈ P`:
```
β_u(g) ← (1 − α)·β_u(g) + α·w_v(g)
```

For each genre `g ∈ β_u \ P` (present in centroid but not in liked profile):
```
β_u(g) ← 0.98 · β_u(g)
g is pruned if β_u(g) < 0.01
```

Then `n ← n + 1` and confidence is recomputed.

#### 2.7.2 Behavioural similarity `S_beh(u,v)`

`BehavioralScoreCalculator.calculate`. Cosine similarity between `β_u` and `w_v`:

```
S_beh(u,v) = 100 · ⟨β_u, w_v⟩ / (‖β_u‖₂ · ‖w_v‖₂)
```

If `n < 5`, returns 50 (cold start).

#### 2.7.3 Confidence and inactivity decay

```
c_u = min(1.0, n / 50)
```

Every read of the profile applies inactivity decay. With `Δ` days since `lastUpdatedAt`:

```
if Δ > 14:  c_u ← c_u · 0.98^(Δ − 14)
```

#### 2.7.4 Effective-score threshold (EMA, α=0.15)

Defined in `BehavioralProfileService`. After every like with score `S`:

```
T_u ← 0.85 · T_u + 0.15 · S
```

When `c_u > 0.3` and `T_u` is non-null, the feed's `minScore` is overridden by `0.85 · T_u`.

### 2.8 Final score

```
behavioralWeight = c_u · 0.40
profileWeight    = 1 − behavioralWeight
S_final(u,v)     = profileWeight·S_profile(u,v) + behavioralWeight·S_beh(u,v)
```

Cold start (`c_u = 0`) collapses to `S_final = S_profile`. At full confidence the behavioural component contributes 40%.

A categorical compatibility level is then assigned via thresholds on `S_final` (defined in `MatchScore.CompatibilityLevel.fromScore`).

---

## 3. System architecture

### 3.1 Layer overview

```
HTTP layer  ─►  MatchingController  ─►  MatchRecommendationService
                                                │
                                                ▼
        ┌────────────────────────────────────────────────────┐
        │  Phase A (read-only tx, releases connection)      │
        │  fetchCandidateData                                │
        │   ├─ findCandidateUsers (SQL)                      │
        │   ├─ swipe exclusions (paged)                      │
        │   ├─ batch-load cached scores                      │
        │   ├─ batch-load genre prefs (single query)         │
        │   ├─ pre-parse gender tokens                       │
        │   └─ initialize lazy assocs (BatchSize=50)         │
        └────────────────────────────────────────────────────┘
                                                │
                                                ▼
        ┌────────────────────────────────────────────────────┐
        │  Phase B (no transaction)                          │
        │  Score loop with cache-first two-tier strategy     │
        │   ├─ Tier 1: cache covers page → serve immediately │
        │   └─ Tier 2: score offset+limit synchronously,     │
        │              warm rest via @Async                  │
        └────────────────────────────────────────────────────┘
                                                │
                                                ▼
                              MatchScoringService.calculateScore
                              ├─ MatchScoreCalculator (music)
                              ├─ LifestyleScoreCalculator
                              ├─ InterestsScoreCalculator
                              ├─ Haversine location score
                              └─ BehavioralScoreCalculator (cosine)
```

### 3.2 Why two phases

The split exists to release the database connection before the scoring loop runs. Earlier versions held a `readOnly` transaction across the whole loop while a `REQUIRES_NEW` inner transaction in `calculateScore` opened a second connection — under 15 concurrent feed requests this saturated a 30-connection pool.

### 3.3 Asynchronous invariants

- `BehavioralProfileService.onSwipeRecorded` listens via `@TransactionalEventListener(AFTER_COMMIT) + @Async`. The behavioural profile is therefore never updated for a swipe whose transaction rolls back.
- `MatchRecommendationService.asyncScoreAndCache` runs on `taskExecutor`. Failures are swallowed per-candidate so background warming cannot abort the user's response.
- Reverse-direction score backfill (when mutual-like reverse computation fails twice) is event-driven and idempotent (`updateMatchScoreBIfNull`).

---

## 4. Data model

The matching subsystem owns six entities. Field details (`Match.java`, `UserSwipe.java`, `UserBehavioralProfile.java`, `UserGenrePreference.java`, `CanonicalGenre.java`, `UserMatchScore.java`).

### 4.1 `Match`

A mutual-like outcome between two users. Stores **both** directional scores (`matchScore` for A→B, `matchScoreB` for B→A). Invariant: `userA.id < userB.id` (enforced at JPA level via `@PrePersist`/`@PreUpdate`). Status transitions: `ACTIVE → UNMATCHED`. Re-match after unmatch is supported via `ON CONFLICT DO UPDATE` in the native upsert, gated on `status != 'active'`.

### 4.2 `UserSwipe`

Append-only swipe log. Action ∈ {like, super_like, pass, block}. Stores `matchScoreAtSwipe` for offline learning analysis (did high-scoring profiles actually get likes?). `dimensionScores` field is reserved for future per-dimension snapshotting but currently null.

### 4.3 `UserBehavioralProfile`

One per user. Stores the EMA centroid (`learnedGenreWeights` as JSON), age moving average, frequency map of liked relationship goals, the EMA threshold, and the like/pass counters that drive confidence.

### 4.4 `UserGenrePreference` and `CanonicalGenre`

`CanonicalGenre` is the master list (≈600 rows after seeding). `UserGenrePreference` is the join row with weight ∈ [0,1] and confidence ∈ [0,1]. Source is one of `SPOTIFY_DERIVED`, `MANUAL_SELECTION`, `INFERRED`.

### 4.5 `UserMatchScore` (the score cache)

A directional cache row keyed by `(user_id, matched_user_id)` and tagged with `algorithmVersion = "v2.0"`. Stores the five dimension scores, overall, full breakdown JSON, and insights JSON. Fresh if `computedAt > max(updatedAt_A, updatedAt_B)`.

---

## 5. End-to-end pipeline

### 5.1 Feed request flow

```
GET /api/v1/matching/potential?limit=20&offset=0&minScore=0
                       │
                       ▼
   1. Authenticate JWT (SecurityConfig)
   2. Resolve current user
   3. Phase A: fetchCandidateData
      a. Run candidate-pool cache lookup (5 min Caffeine)
         - HIT  → load entities by ID, filter dynamically against new exclusions
         - MISS → ORDER BY RANDOM() LIMIT 500, store IDs
      b. Apply hard filters (gender, distance) in-memory
      c. Batch-fetch all cached scores for this user (one IN query)
      d. Batch-fetch genre prefs for user + all candidates (one IN query, JOIN FETCH)
      e. Force-init lazy associations (BatchSize=50 batches)
      f. Resolve effectiveMinScore from behavioural threshold if c > 0.3
   4. Phase B: scoring
      a. Split candidates into freshCached vs needsScoring
      b. If cache covers offset+limit → Tier 1 (warm rest async)
      c. Else                          → Tier 2 (score offset+limit, async warm rest)
   5. Filter by effectiveMinScore, build PotentialMatch DTOs
   6. Slice [offset, offset+limit) and return PotentialMatchPage
```

### 5.2 Swipe flow

```
POST /api/v1/matching/swipe { swipedUserId, action }
                       │
                       ▼
   1. Validate action ∈ {like, super_like, pass, block}
   2. Reject if target user is deleted
   3. Reject duplicate swipe (uk_swiper_swiped + fast-path check)
   4. Compute *server-side* match score (skip for block)
   5. If like/super_like:
      a. Acquire pg_advisory lock on sorted pair key
      b. Check hasUserLiked(target → me) BEFORE insert
      c. mutualLike = result of step b
   6. INSERT swipe (resultedInMatch = mutualLike), flush
   7. If block → publish UserBlockedEvent (auto-unmatch via listener)
   8. If mutualLike:
      a. Compute reverse score (2 attempts, fallback to async backfill)
      b. createMatch(swiper, target, scoreA, scoreB, source)
      c. Link swipe → match, link other-side swipe → match
      d. Publish MatchCreatedEvent
   9. Publish SwipeRecordedEvent (commits behavioural update post-tx)
   10. Return SwipeResult
```

### 5.3 Spotify ingestion flow

```
SpotifyGenreSyncService.syncUserGenrePreferences
   1. Acquire distributed lock with ownerId (Redis or in-memory fallback)
   2. Phase 1 — HTTP only (no DB connection held):
      ├─ short_term  weight=3 ──┐
      ├─ medium_term weight=2 ──┼─ CompletableFuture.supplyAsync (parallel)
      └─ long_term   weight=1 ──┘
   3. Collect with 15s safety timeouts; tolerate ≤2 failures
   4. Phase 2 — DB write transaction:
      ├─ replaceSpotifyPreferences (delete + extract)
      └─ touchUpdatedAt → invalidates cached match scores
   5. Release lock (Lua conditional delete by ownerId)
```

The weighted duplication (×3, ×2, ×1) is the simplest possible recency model — it boosts genres the user has been listening to *recently* without changing the downstream weight calculation. Treat this as a recency prior in the thesis.

---

## 6. Caching, freshness and concurrency

### 6.1 Cache freshness invariant

A cached `UserMatchScore` row is fresh iff:

```
algorithmVersion == "v2.0" ∧
computedAt > max(userA.updatedAt, userB.updatedAt)
```

Either user updating their profile (or having `touchUpdatedAt` called by the behavioural profile update path) invalidates all cached pairs involving them by violating the inequality. This is a **declarative** freshness condition — no explicit invalidation paths. Worth a section in the thesis.

### 6.2 Caches in use

| Cache | Type | Key | TTL | Purpose |
|---|---|---|---|---|
| `user_match_scores` | Postgres table | (user, matched_user) | until invalidated | Persistent, directional score cache |
| `candidatePoolCache` | Caffeine | userId | 5 min | Stable random ordering across pages |
| `genreWeightCache` | Caffeine | profileId | 10 min | Avoid JSON deserialisation per scoring pass |
| `GenrePrefetchContext` | ThreadLocal | (per request) | request scope | Replace per-candidate genre query with bulk-fetched map |

### 6.3 Concurrency hazards (already solved)

These are worth a paragraph each in Chapter 5:

- **Mutual-match race** — without a pair lock, two simultaneous likes could both insert before either sees the other. Solved via `pg_advisory_xact_lock` on the sorted pair key.
- **Behavioural profile lost-update** — solved with `@Version` optimistic locking and a 3-attempt retry with exponential backoff (40 ms, 80 ms).
- **Account deletion vs incoming swipes** — solved with a `deleted` flag set + flushed before child cleanup, plus a check in `recordSwipe`.
- **Re-match after unmatch** — solved by `ON CONFLICT DO UPDATE WHERE status != 'active'` so an UNMATCHED row can be reactivated.
- **JWTs of deleted users** — `SecurityConfig.jwtDecoder` throws `BadJwtException` when the user no longer exists, returning 401 instead of leaking via 404.

These are engineering, not algorithmic. Mention as a single block ("Implementation considerations") so they don't dominate the thesis.

---

## 7. Recommended thesis structure

A 30-page thesis on a recommender is short. Aim for tight chapters:

| # | Chapter | Pages | Key content |
|---|---|---|---|
| 1 | Introduction | 2 | Problem framing, motivation for music+lifestyle in dating, contributions, outline |
| 2 | Background and related work | 4 | Content-based vs collaborative filtering; cold-start; cosine similarity in TF-IDF; dating-app systems (Tinder ELO, Hinge, OkCupid); music-similarity work (Spotify embeddings, Pandora Music Genome) |
| 3 | System architecture | 5 | Data model diagrams from §4, pipeline diagram from §3.1, Spotify ingestion, candidate retrieval |
| 4 | Algorithm design | 8 | The mathematical specification from §2 with derivations and parameter justifications |
| 5 | Implementation considerations | 3 | Directional scoring, transactional design, the cache freshness invariant, the concurrency block from §6.3 — keep it brief |
| 6 | Evaluation | 5 | The methodology from §10 below — this is the critical chapter |
| 7 | Limitations and future work | 2 | Items from §9 framed as honest limitations; CF and outcome-driven weight learning as future work |
| 8 | Conclusion | 1 | |

### 7.1 Diagrams to draw

- **Pipeline diagram.** §3.1 ASCII can be redrawn as a proper figure.
- **Data model ER diagram.** Six entities from §4 with FKs.
- **Behavioural learning over time.** Plot of `c_u`, `β_u(g)` for two genres, and behavioural-vs-profile contribution to `S_final` over a simulated 50-swipe sequence. **High impact, low effort.**
- **Cache hit-rate curve.** Hit rate vs feed-request number for a cold user.
- **Sensitivity heatmap.** Δ NDCG@10 vs (`α` floor, behavioural cap, `m_u`).

---

## 8. Strengths to defend

These are the parts examiners should respect, framed for an academic audience:

1. **Directional scoring as a structural property.** `S(u,v) ≠ S(v,u)` by construction. Almost no academic dating-recommender literature handles directionality; most assume symmetric similarity. The `musicMatchImportance` weighting and the per-user behavioural profile both make scoring intrinsically directional. Frame it as a contribution.
2. **Hybrid content + behavioural with confidence-weighted blending.** The formula `behavioralWeight = c_u · 0.40` is a clean implementation of cold-start handling via confidence-weighted ensembling. Cite Burke (2002) on hybrid recommenders.
3. **EMA-based online learning with adaptive learning rate.** `α = max(0.20, 1/(n+1))` is a **floored Robbins–Monro schedule**: unbiased early (1/(n+1) is the standard step for an unbiased mean), responsive late (the 0.20 floor preserves drift adaptability). This is a real design choice with a real trade-off — explain it explicitly.
4. **Implicit-feedback decay** of absent genres — a forgetting factor in the sense of Koren (2009).
5. **Adaptive personal threshold** via EMA of accepted match scores. Self-calibrates to picky vs liberal users. Largely original — no published dating-app work I am aware of does this explicitly.
6. **Inactivity confidence decay** prevents stale personalisation from dominating after long absences.
7. **Cache freshness as a declarative invariant** rather than imperative invalidation paths. A defensible engineering pattern.
8. **Versioned algorithm tag** (`algorithmVersion`) supports A/B comparison and migrations.

---

## 9. Critical weaknesses to address

These are real algorithmic flaws, ordered by severity. Address them either by fixing them or, where time precludes, by pre-empting them in your "Limitations" chapter.

### 9.1 Music genre overlap is mathematically inconsistent

`MatchScoreCalculator.calculateGenreOverlapScore` computes:

```
score = 100 · (avgOverlap · 0.7 + coverage · 0.3)
```

`avgOverlap` is averaged **only over shared genres**, so two users sharing one genre at weight 0.9 each get a higher `avgOverlap` than two users sharing five genres at 0.7 each. Coverage compensates only weakly (30%). This is **non-monotonic in shared count** under realistic distributions: more shared genres can produce a *lower* score. **Fix: replace with cosine similarity over the full sparse genre vector** (the same form already used by the behavioural cosine — this gives mathematical consistency between the two scorers).

### 9.2 Weight similarity punishes diversity

`weightSimScore = avg_{g∈S}(1 − |w_u(g) − w_v(g)|)` is averaged over shared genres only. A user who shares 1 genre with weight delta 0.05 scores ≈0.95. A user who shares 10 genres with average delta 0.10 scores ≈0.90. Intuitively the latter is the better match. **Fix: weight the average by `min(w_u(g), w_v(g))` so contributions from low-weight shared genres count less.**

### 9.3 Diversity score is misnamed

`min(|G_u|, |G_v|) / max(|G_u|, |G_v|)` rewards equal *cardinality* of genre lists, not actual diversity. A user with 50 pop genres and a user with 50 metal genres score 100 on this metric. **Fix or drop.** A real diversity-similarity metric would be `|H(w_u) − H(w_v)|` where `H` is Shannon entropy of the genre weight distribution.

### 9.4 Lifestyle weights are unjustified magic numbers

`{100, 85, 80, 70, 65, 55, 40, 10, 5}` in `LifestyleScoreCalculator` have no documented derivation. For a master's thesis this is the single biggest credibility hit.

Two routes:

- **Route A (defensive):** explicitly justify each as expert-elicited priors and cite Finkel et al. (2012) on relationship-goal alignment, and survey work on smoking/drinking compatibility in mate selection.
- **Route B (constructive):** replace with logistic regression weights learned from outcome data (matches → conversations) on the seed users.

### 9.5 Behavioural component ignores negative feedback

`BehavioralProfileService.doUpdateAfterSwipe` updates `learnedGenreWeights` only on like/super_like. Passes increment `totalPasses` but never push the centroid *away* from disliked genres. This is **implicit-positive-only learning**, known to converge slowly and overfit popular categories (Hu, Koren, Volinsky 2008).

**Fix sketch (~10 lines of code):** on pass, for genres `g ∈ G_v` (the *passed* user's genres), apply
```
β_u(g) ← β_u(g) − α_neg · w_v(g),  α_neg = 0.05
```
clamped at 0. Cite this as a contribution to the thesis.

### 9.6 No collaborative-filtering signal

The system is purely content-based. A user who likes 50 indie-rock fans cannot be recommended someone *those 50 also like* (i.e. collaborative information). You don't have to *implement* CF, but you must explicitly acknowledge the absence and explain why content-based was chosen — cold start, explainability, privacy. Without that paragraph examiners will ask why you ignored the dominant paradigm.

### 9.7 Filter-bubble loop in adaptive threshold

`T_u` rises with every like; the feed uses `0.85 · T_u` as `minScore`; future likes therefore have higher scores; `T_u` rises further. This is a **positive feedback loop** that narrows the candidate space over time. **Mitigations:** add a decay term (e.g. `T_u ← (1 − 0.01)·T_u` per day), or cap `T_u` at e.g. 80, or clip `0.85 · T_u` at a fixed ceiling. Worth simulating.

### 9.8 Synonym map is brittle

`InterestsScoreCalculator.normalize` uses a 50-entry hand map plus naive `endsWith("s")` stripping. `"matches"` would be normalised to `"matche"`. **Fix: replace with a Porter stemmer (already in `org.apache.lucene` if added) or explicitly acknowledge as a limitation.**

### 9.9 Importance weighting has a hard floor

`musicWeight ∈ [0.30, 0.80]`. A user who genuinely doesn't care about music cannot express it. Either expose `0.0` as a valid lower bound or document why 30% is a hard floor (e.g. "music is the differentiating dimension of this product").

### 9.10 No fairness or popularity-bias analysis

Random ordering in `findCandidateUsers` partially mitigates this, but high-density genre users systematically rank higher because the genre overlap formula rewards larger genre sets. Worth measuring (Gini coefficient of recommendation frequency).

### 9.11 Spotify time-range weighting is hardcoded

`{short_term: 3, medium_term: 2, long_term: 1}` is unsupported by data. Either justify via a recency literature citation or learn the weights.

### 9.12 No robustness analysis

Many constants are hardcoded: 0.40 behavioural cap, 0.70/0.20/0.10 inside music, 0.45/0.30/0.25 inside profile, 0.20 alpha floor, 50 confidence ceiling, 14-day grace, 0.98 daily decay, 0.85 threshold scaling. Without a sensitivity analysis the parameter choices look arbitrary.

---

## 10. Evaluation methodology

This is the chapter most master's theses on recommenders fail at. Empirical evaluation is non-negotiable.

### 10.1 Offline evaluation on synthetic + seed data

You already have `UserSeedDataLoader`, `ExtendedUserSeedDataLoader`, `MOCK_USERS_GUIDE.md`. Use them.

**Setup:**
- For each seed user with `N` likes, hide the last `K = 5` likes.
- Compute the algorithm's ranking of all candidates.
- Measure whether held-out liked users appear in the top-K of the ranking.

**Required metrics:**

| Metric | What it answers | Reference |
|---|---|---|
| Precision@K (K=5,10,20) | Of the top K, how many are relevant? | Standard IR |
| Recall@K | Of all relevant, how many in top K? | Standard IR |
| NDCG@K | Top-K ranking with position discount | Järvelin & Kekäläinen 2002 |
| MRR | Mean reciprocal rank of first relevant | Standard IR |
| Coverage | % of catalogue ever recommended | Filter bubble |
| Gini coefficient | Inequality of recommendation frequency | Fairness |

**Ablation study.** Run with one dimension disabled at a time. Report ΔNDCG. The single most academically convincing experiment you can run with a week of work.

```
Configurations:
  (a) full algorithm
  (b) − music
  (c) − lifestyle
  (d) − interests
  (e) − location
  (f) − behavioural
  (g) only music (sanity)
```

### 10.2 Behavioural-component evaluation

The behavioural layer is your novel contribution; evaluate it specifically.

- Synthesise a user with a strict genre preference (only metal).
- Simulate 50 swipes (40 likes on metal users, 10 passes on jazz users).
- Plot `c_u`, `β_u(metal)`, `β_u(jazz)`, and behavioural-vs-profile contribution to `S_final` over time.
- Re-run with a drift partway through (user starts liking jazz around swipe 25). Measure swipes-until-shift.

The α floor of 0.20 implies half-decay in roughly `log(0.5)/log(0.80) ≈ 3.1` swipes — verify empirically.

### 10.3 Sensitivity analysis

Vary each weight by ±20% and report ΔNDCG@10:

```
- 0.40 behavioural cap        →  {0.32, 0.40, 0.48}
- music weight floor (0.30)   →  {0.24, 0.30, 0.36}
- music weight ceiling (0.80) →  {0.64, 0.80, 0.96}
- α floor (0.20)              →  {0.16, 0.20, 0.24}
- confidence ceiling (50)     →  {40, 50, 60}
- 0.85 threshold scaling      →  {0.68, 0.85, 1.00}
```

A heatmap of parameter sensitivity is a standard thesis figure.

### 10.4 Latency / scalability evaluation

Benchmark:

- Cold-feed latency (no cache, 500 candidates) vs warm-feed latency
- Score-computation latency per pair
- p50/p95/p99 over 100 simulated users
- Effect of GenrePrefetchContext disabled
- Effect of candidate-pool cache disabled

Use `Phase1TestController`/`Phase2TestController`/`Phase3TestController` to bypass auth for benchmarking.

### 10.5 Optional: small user study

N=20 users for a week, swiping on seed profiles, with a post-survey ("did the matches feel relevant?", 1–5 Likert). Even small N gives you a qualitative section examiners weigh heavily.

### 10.6 Reporting

Each experiment should produce:
- A description of the setup
- A table of metrics
- One figure
- One paragraph of interpretation
- A statement of what would have changed your conclusion

---

## 11. Recommended improvements (ranked)

If you implement nothing else, do these three. They have the highest thesis-defensibility return on time.

### 11.1 Replace music genre overlap with cosine similarity (≈1 day)

Replace `calculateGenreOverlapScore` and `calculateWeightSimilarityScore` with a single cosine score over the full genre vector. Benefits:
- Fixes inconsistencies in §9.1, §9.2, §9.3 simultaneously.
- Aligns the music dimension with the behavioural dimension mathematically (both become inner-product methods).
- Gives you a single citable definition (TF-IDF cosine, Salton & McGill 1983).

Pseudocode:
```java
double dot = 0, magA = 0, magB = 0;
Set<String> allGenres = union(weightsA.keySet(), weightsB.keySet());
for (String g : allGenres) {
    double a = weightsA.getOrDefault(g, 0.0);
    double b = weightsB.getOrDefault(g, 0.0);
    dot  += a * b;
    magA += a * a;
    magB += b * b;
}
double cosine = (magA == 0 || magB == 0) ? 0 : dot / (Math.sqrt(magA) * Math.sqrt(magB));
double S_music = 100 * cosine;
```

You can keep the diversity term (now properly defined as entropy distance) at ≤10% weight if you want to retain that flavour.

### 11.2 Add a negative-feedback EMA step on passes (≈2 hours)

Implements implicit negative feedback. ~10 lines of code in `BehavioralProfileService.doUpdateAfterSwipe`, one new section in your thesis (~1 page), and immediate measurable improvement in NDCG. Sketch in §9.5.

### 11.3 Add the offline-evaluation harness from §10.1 (≈1 week)

Without metrics there is no thesis. The harness is roughly 200 lines:

```
EvaluationHarness
  ├─ SeedDataSnapshot.load()            # frozen seed users with their swipe history
  ├─ Splitter.holdout(K=5)              # last K likes per user as test set
  ├─ Ranker.rank(user, candidates)      # run the algorithm
  ├─ Metrics.precision(...)
  ├─ Metrics.recall(...)
  ├─ Metrics.ndcg(...)
  ├─ Metrics.mrr(...)
  ├─ Metrics.coverage(...)
  ├─ Metrics.gini(...)
  └─ Reporter.csv(...)
```

This unlocks every chart in Chapter 6.

### 11.4 Stretch goals

- **(D) Logistic regression for lifestyle weights.** Use seed `UserSwipe` rows with `matchScoreAtSwipe` and outcomes (`resultedInMatch`, `Match.conversationStarted`). Fit logistic weights, replace the rule-based numbers, report the new weights with confidence intervals.
- **(E) Item-based CF as a fourth fusion dimension.** For each candidate `v`, score = average similarity of `v` to users `u` has previously liked (similarity = behavioural cosine). Frame as future work, *but implement a prototype* — even a basic one is a strong signal.
- **(F) Threshold decay.** `T_u ← (1 − 0.01)·T_u` per day. Half a day of work; addresses §9.7 directly.

---

## 12. Related work and citations

A starter bibliography. All are well-established and freely citable.

**Recommender systems — surveys**
- Ricci, F., Rokach, L., & Shapira, B. (2015). *Recommender Systems Handbook* (2nd ed.). Springer.
- Burke, R. (2002). Hybrid recommender systems: Survey and experiments. *User Modeling and User-Adapted Interaction*, 12(4).

**Collaborative filtering**
- Sarwar, B. et al. (2001). Item-based collaborative filtering recommendation algorithms. *WWW '01*.
- Hu, Y., Koren, Y., & Volinsky, C. (2008). Collaborative filtering for implicit feedback datasets. *ICDM '08*. — Cite for the negative-feedback discussion in §9.5.
- Koren, Y. (2009). Collaborative filtering with temporal dynamics. *KDD '09*. — Cite for the genre absent-decay forgetting factor.

**Cold start**
- Schein, A. I. et al. (2002). Methods and metrics for cold-start recommendations. *SIGIR '02*.

**Similarity measures**
- Salton, G., & McGill, M. J. (1983). *Introduction to Modern Information Retrieval*. — Cite for cosine.
- Jaccard, P. (1912). The distribution of the flora in the alpine zone. — Cite for Jaccard similarity.

**Online learning / EMA**
- Robbins, H., & Monro, S. (1951). A stochastic approximation method. *Annals of Mathematical Statistics*. — Cite for the `1/(n+1)` schedule.

**Evaluation**
- Järvelin, K., & Kekäläinen, J. (2002). Cumulated gain-based evaluation of IR techniques. *ACM TOIS*. — NDCG.

**Dating / mate selection**
- Finkel, E. J., Eastwick, P. W., Karney, B. R., Reis, H. T., & Sprecher, S. (2012). Online dating: A critical analysis. *Psychological Science in the Public Interest*, 13(1).
- Hitsch, G. J., Hortaçsu, A., & Ariely, D. (2010). Matching and sorting in online dating. *American Economic Review*, 100(1).

**Music similarity**
- Schedl, M., Knees, P., McFee, B., Bogdanov, D., & Kaminskas, M. (2015). Music recommender systems. In *Recommender Systems Handbook*.

**Fairness in recommendations**
- Ekstrand, M. D. et al. (2018). All the cool kids, how do they fit in? *FAT* '18*. — For Gini-based fairness analysis.

---

## 13. Glossary of variables and constants

For copy-paste into a thesis appendix.

| Constant | Value | Where defined | Meaning |
|---|---|---|---|
| `ALGORITHM_VERSION` | `"v2.0"` | `MatchScoringService` | Cache row version tag |
| Music overlap weight | 0.70 | `MatchScoreCalculator` | Sub-weight for `overlapScore` |
| Music weight-similarity weight | 0.20 | `MatchScoreCalculator` | Sub-weight for `weightSimScore` |
| Music diversity weight | 0.10 | `MatchScoreCalculator` | Sub-weight for `diversityScore` |
| `overlapScore` blend | 0.7 / 0.3 | `MatchScoreCalculator` | avgOverlap vs coverage |
| Music weight floor | 0.30 | `MatchScoringService` | At importance = 0 |
| Music weight ceiling | 0.80 | `MatchScoringService` | At importance = 100 |
| Lifestyle sub-weight | 0.45 | `MatchScoringService` | Of remaining after music |
| Interests sub-weight | 0.30 | `MatchScoringService` | Of remaining after music |
| Location sub-weight | 0.25 | `MatchScoringService` | Of remaining after music |
| Lifestyle goal weight | 0.40 | `LifestyleScoreCalculator` | Within lifestyle |
| Lifestyle kids weight | 0.30 | `LifestyleScoreCalculator` | Within lifestyle |
| Lifestyle smoking weight | 0.15 | `LifestyleScoreCalculator` | Within lifestyle |
| Lifestyle drinking weight | 0.15 | `LifestyleScoreCalculator` | Within lifestyle |
| Behavioural cap | 0.40 | `MatchScoringService` | Max behavioural contribution |
| Confidence ceiling | 50 | `BehavioralProfileService` | totalLikes for c_u = 1 |
| Cold-start threshold | 5 | `BehavioralScoreCalculator` | Min likes to activate |
| EMA α floor | 0.20 | `BehavioralProfileService` | Lower bound on adaptation rate |
| Genre absent decay | 0.98 | `BehavioralProfileService` | Per-update multiplier |
| Genre weight floor | 0.01 | `BehavioralProfileService` | Pruning threshold |
| Inactivity grace | 14 days | `BehavioralProfileService` | Before confidence decay |
| Inactivity decay | 0.98/day | `BehavioralProfileService` | After grace |
| Threshold EMA α | 0.15 | `BehavioralProfileService` | Effective-score-threshold update |
| Threshold scaling | 0.85 | `MatchRecommendationService` | Min-score multiplier |
| Threshold activation | 0.30 | `MatchRecommendationService` | Confidence required |
| Interests floor | 10 | `InterestsScoreCalculator` | Minimum non-empty score |
| Default location score | 50 | `MatchScoringService` | When coords missing |
| Default lifestyle score | 50 | `LifestyleScoreCalculator` | When data missing |
| Score cache TTL | n/a | `UserMatchScore` | Invalidated by `updatedAt` invariant |
| Candidate pool TTL | 5 min | `MatchRecommendationService` | Caffeine cache |
| Genre weight cache TTL | 10 min | `BehavioralScoreCalculator` | Caffeine cache |
| Candidate pool size | 500 | `MatchRecommendationService` | RANDOM() LIMIT |
| Spotify weights | 3 / 2 / 1 | `SpotifyGenreSyncService` | short / medium / long term |

---

## Appendix A — File map

| File | Role |
|---|---|
| `services/matching/MatchScoringService.java` | Orchestrator |
| `services/matching/MatchScoreCalculator.java` | Music dimension |
| `services/matching/LifestyleScoreCalculator.java` | Lifestyle dimension |
| `services/matching/InterestsScoreCalculator.java` | Interests dimension (Jaccard) |
| `services/matching/BehavioralScoreCalculator.java` | Cosine vs centroid |
| `services/matching/BehavioralProfileService.java` | EMA centroid update, threshold, decay |
| `services/matching/MatchRecommendationService.java` | Candidate retrieval, two-tier serving, caches |
| `services/matching/SwipeService.java` | Swipe ingestion, mutual-like detection |
| `services/matching/MatchService.java` | Match lifecycle (create, unmatch, conversation start) |
| `services/matching/MatchLifecycleListener.java` | Block → auto-unmatch event handler |
| `services/matching/SpotifyGenreSyncService.java` | Genre extraction from Spotify |
| `services/matching/GenreExtractionService.java` | Genre persistence with atomic replace |
| `services/matching/GenreWeightCalculator.java` | Logarithmic frequency weighting |
| `services/matching/GenrePrefetchContext.java` | Per-request bulk-fetch ThreadLocal |
| `models/matching/dao/Match.java` | Mutual match (directional scores) |
| `models/matching/dao/UserSwipe.java` | Swipe log |
| `models/matching/dao/UserBehavioralProfile.java` | Per-user behavioural state |
| `models/matching/dao/UserGenrePreference.java` | User × genre weights |
| `models/matching/dao/CanonicalGenre.java` | Master genre list |
| `models/matching/dao/UserMatchScore.java` | Pair-keyed score cache |
| `repositories/UserJpaRepository.java` | `findCandidateUsers` JPQL |
| `repositories/UserSwipeRepository.java` | Swipe queries (inc. paged exclusions) |
| `repositories/MatchRepository.java` | Match upsert and lifecycle queries |

---

*Last updated: 2026-05-09. Algorithm version: v2.0.*
