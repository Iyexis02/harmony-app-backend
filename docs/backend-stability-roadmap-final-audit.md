# Backend Stability Fix Roadmap — Final Architecture Audit

## Purpose

This document is the **consolidated, forward-looking roadmap** for the dating app Spring Boot backend. It captures every remaining architectural risk, structural weakness, and improvement opportunity identified during a full-stack review of the codebase as of 2026-03-18.

Previous roadmaps (7 documents, 53 batches) addressed data integrity, error handling, security, scalability, concurrency, cross-module integrity, and JPA correctness. **All 53 batches are complete.** This roadmap covers what remains.

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

## Pre-Audit Baseline (2026-03-18)

### Compilation Status
- **Main source**: Compiles cleanly (`./mvnw compile` — zero errors)
- **Test source**: **286 compilation errors** across **23 of 61 test files** — tests cannot run
- **Root causes**: Constructor signature drift (services gained new dependencies after tests were written), `GenrePreferenceSource` String→enum migration, `Pageable` parameter additions to repository methods, `SwipeResult` API changes, `assertDoesNotThrow` ambiguity, access modifier mismatches

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

### Architecture Snapshot
- **11 controllers** (6 production, 2 public/account, 3 dev-only test)
- **28 service classes** (16 core + 12 matching-domain)
- **14 JPA repositories** + 1 custom `UserRepository` (manual SQL impl)
- **13 JPA entities** with optimistic locking on `UserEntity`, `Match`, `UserBehavioralProfile`
- **5-dimensional matching algorithm** (music, lifestyle, interests, location, behavioral) with score caching

---

## Dependency Graph

```
Phase 1 — Test Suite Recovery (P0, blocking everything)
  A (independent — test compilation fix)

Phase 2 — Structural Refactoring (P1)
  B (independent — UserServiceImpl decomposition)
  C (independent — repository pattern unification)
  A ─► D (error response standardization — tests must compile first)

Phase 3 — Resilience & Observability (P2)
  E (independent — circuit breakers for external APIs)
  F (independent — Spring Actuator + health checks)
  G (independent — Flyway migration strategy)

Phase 4 — Production Hardening (P3)
  F ─► H (structured logging — needs Actuator/Micrometer in place)
  I (independent — API documentation with SpringDoc)
  J (independent — distributed locking for multi-instance)
```

## Recommended Implementation Order

```
Phase 1 — Test Suite Recovery (P0)
  1. Batch A — Fix test compilation errors (23 files, 286 errors)

Phase 2 — Structural Refactoring (P1)
  2. Batch B — Decompose UserServiceImpl god class
  3. Batch C — Unify dual repository pattern
  4. Batch D — Standardize error response format                [depends on A]

Phase 3 — Resilience & Observability (P2)
  5. Batch E — Circuit breakers for Spotify and Google Maps APIs
  6. Batch F — Spring Actuator + health/metrics endpoints
  7. Batch G — Flyway migration strategy resolution

Phase 4 — Production Hardening (P3)
  8. Batch H — Structured logging with correlation IDs          [depends on F]
  9. Batch I — OpenAPI documentation with SpringDoc
 10. Batch J — Distributed locking for multi-instance deployment
```

---

## Phase 1 — Test Suite Recovery (P0)

### - [x] Batch A — Fix test compilation errors ✅ 2026-03-18

**Priority:** P0 | **Risk:** High | **Effort:** 4–6 hours
**Depends on:** Nothing
**Blocking:** All other batches (no regression safety without tests)

#### Problem

286 compilation errors across 23 test files. Tests have drifted from production code as previous roadmap batches added constructor parameters, changed method signatures, and migrated String fields to enums. **No test can currently run**, which means every prior batch's verification is incomplete and every future change is unverified.

#### Affected Test Files (23 files)

**Category 1 — Constructor signature drift (services gained new dependencies):**
- `BatchDGenreSyncAtomicityTest.java` — `SpotifyGenreSyncService` constructor changed (added `UserJpaRepository`)
- `BatchDRetryResilienceTest.java` — same `SpotifyGenreSyncService` constructor issue
- `BatchFSpotifyConcurrencyTest.java` — same `SpotifyGenreSyncService` constructor issue
- `BatchFRateLimitTest.java` — `RateLimitFilter` and `AuthenticatedRateLimitInterceptor` constructors changed (inject `RateLimiter` interface)

**Category 2 — `GenrePreferenceSource` String→enum migration:**
- `BatchBFetchStrategyTest.java` — sets `source` as `String` instead of `GenrePreferenceSource` enum
- `Phase2IntegrationTest.java` — same issue
- `DomainModelHardeningTest.java` — same issue

**Category 3 — Repository method signature changes (`Pageable` added):**
- `BatchDIdorProtectionTest.java` — `findBlockedUserIds()` / `findBlockedByUserIds()` now require `Pageable`
- `BatchCMutualMatchAtomicityTest.java` — `SwipeResult` constructor / factory method changed

**Category 4 — `SwipeResult` API changes:**
- `SwipeDuplicateConcurrencyTest.java` — `SwipeResult` fields/constructor changed
- `SwipeServerSideScoreTest.java` — same issue
- `IntegrityBatchAMutualMatchRaceTest.java` — same issue

**Category 5 — `assertDoesNotThrow` ambiguity (JUnit 5 overload resolution):**
- `BatchDCandidateLoadingTest.java`
- `BatchDPaginateFromCacheTest.java`
- `BatchEQueryOptimizationTest.java`
- `BatchGEntityLoadingTest.java`

**Category 6 — Other type/access issues:**
- `BatchCIndexTest.java` — references removed/renamed constants
- `BatchHRateLimitCacheKeyTest.java` — `ObjectMapper.readValue()` ambiguity
- `BatchHTypeFixTest.java` — `UserPhoto.onCreate()` access modifier (protected)
- `GlobalExceptionHandlerBatchBTest.java` — missing exception class or method
- `ScalabilityBatchBIndexTest.java` — index name/constant drift
- `Phase1IntegrationTest.java` — multiple signature changes
- `Phase3IntegrationTest.java` — multiple signature changes

#### Implementation Tasks

1. **Do NOT rewrite tests from scratch** — fix each file's compilation errors to match current production signatures
2. For each category, apply the fix pattern systematically:
   - **Constructor drift**: Update the mock/constructor call to include the new parameters (add `@Mock` fields for new dependencies)
   - **Enum migration**: Replace `"spotify_derived"` strings with `GenrePreferenceSource.SPOTIFY_DERIVED` (or equivalent enum value)
   - **Pageable addition**: Add `PageRequest.of(0, 10_000)` argument to repository method calls
   - **SwipeResult**: Update field access / constructor calls to match current `SwipeResult` DTO shape
   - **assertDoesNotThrow ambiguity**: Cast the lambda to `Executable` explicitly: `assertDoesNotThrow((Executable) () -> ...)`
   - **Access modifier**: If `UserPhoto.onCreate()` is `protected`, either make it package-private or test via the JPA lifecycle (persist + verify)
3. After fixing each file, run `./mvnw test-compile` to confirm the error count drops
4. After all 23 files compile, run `./mvnw test` and record results
5. Tests that fail at **runtime** (not compilation) should be documented but not blocked — runtime failures indicate real bugs that other batches may address

#### Verification

- [x] `./mvnw test-compile` exits with zero errors
- [x] `./mvnw test` runs to completion — 447 tests run, 3 failures, 420 errors (all runtime, pre-existing)
- [x] All 23 previously-broken files participate in the test run
- [x] No test file was deleted to achieve compilation — all tests preserved

---

## Phase 2 — Structural Refactoring (P1)

### - [x] Batch B — Decompose UserServiceImpl God Class ✅ 2026-03-18

**Priority:** P1 | **Risk:** Medium | **Effort:** 3–4 hours
**Depends on:** Nothing (but Batch A recommended for regression safety)

#### Problem

`UserServiceImpl` has **12 constructor-injected dependencies** (the most of any class in the codebase) and handles four distinct responsibilities:

1. **User CRUD** — `findOrCreateUser()`, `userExists()`, `getUserBySpotifyId()`
2. **Spotify token management** — `getValidSpotifyToken()`, `refreshAndUpdateUserToken()` with in-memory `ConcurrentHashMap<String, Object>` lock
3. **Account deletion** — `deleteAccount()` with `PESSIMISTIC_WRITE` lock and cascade cleanup across 6 repositories
4. **Behavioral cache invalidation** — `behavioralScoreCalculator.invalidateCache()` during deletion

This violates SRP, makes testing difficult (12 mocks per test), and concentrates mutable shared state (`tokenRefreshLocks`) in a service that also handles unrelated concerns.

#### Files Affected

- `src/main/java/com/example/dating/services/impl/UserServiceImpl.java` (modify — reduce to user CRUD only)
- `src/main/java/com/example/dating/services/UserService.java` (modify — move methods to new interfaces)
- New: `src/main/java/com/example/dating/services/SpotifyTokenService.java` (interface)
- New: `src/main/java/com/example/dating/services/impl/SpotifyTokenServiceImpl.java` (token refresh logic)
- New: `src/main/java/com/example/dating/services/AccountDeletionService.java` (interface)
- New: `src/main/java/com/example/dating/services/impl/AccountDeletionServiceImpl.java` (deletion + cleanup)
- All callers of `userService.getValidSpotifyToken()` — update to inject `SpotifyTokenService`
- All callers of `userService.deleteAccount()` — update to inject `AccountDeletionService`
- `src/main/java/com/example/dating/controllers/AccountController.java` (inject `AccountDeletionService`)

#### Implementation Tasks

1. **Extract `SpotifyTokenServiceImpl`**:
   - Move `getValidSpotifyToken()`, `refreshAndUpdateUserToken()`, `isTokenExpiredOrExpiring()`, and the `tokenRefreshLocks` ConcurrentHashMap
   - Dependencies: `UserRepository`, `EncryptionService`, `JwtService`
   - Keep `@Transactional` annotations as-is
2. **Extract `AccountDeletionServiceImpl`**:
   - Move `deleteAccount()` and its `PESSIMISTIC_WRITE` lock + cascade delete logic
   - Dependencies: `UserRepository`, `UserJpaRepository`, `PasswordEncoder`, `UserSwipeRepository`, `MatchRepository`, `UserMatchScoreRepository`, `UserGenrePreferenceRepository`, `UserBehavioralProfileRepository`, `BehavioralScoreCalculator`
3. **Slim down `UserServiceImpl`**:
   - Remaining methods: `findOrCreateUser()`, `userExists()`, `getUserBySpotifyId()`
   - Remaining dependencies: `UserRepository`, `EncryptionService`, `UserMapper`, `PasswordEncoder`
   - Remove all 6 matching repository imports and `BehavioralScoreCalculator`
4. **Update callers**: Search for all `UserService` usages and redirect to new services
5. **Preserve the `UserService` interface** for backwards compatibility — add `SpotifyTokenService` and `AccountDeletionService` as separate interfaces

#### Verification

- [x] `UserServiceImpl` has ≤5 constructor dependencies (now 4)
- [x] `SpotifyTokenServiceImpl` compiles and contains `tokenRefreshLocks`
- [x] `AccountDeletionServiceImpl` compiles and contains `PESSIMISTIC_WRITE` lock logic
- [x] All existing callers updated — no compile errors
- [x] `./mvnw compile` passes
- [x] Behavioral cache invalidation still works during account deletion

---

### - [x] Batch C — Unify Dual Repository Pattern — COMPLETE 2026-03-18

**Priority:** P1 | **Risk:** Medium | **Effort:** 4–5 hours
**Depends on:** Nothing (but Batch A recommended for regression safety)

#### Problem

The codebase has **two coexisting repository patterns** for the same `User` entity:

1. **`postgres/UserRepository`** — Custom interface with manual SQL implementation in `postgres/impl/UserRepositoryImpl`. Methods: `save()`, `findById()`, `findByEmail()`, `findBySpotifyId()`, `findByVerificationTokenHash()`, `findByResetPasswordTokenHash()`, `deleteById()`.
2. **`repositories/UserJpaRepository`** — Spring Data JPA extending `JpaRepository<UserEntity, String>`. Contains `findCandidateUsers()`, `findByIdForUpdate()`, `touchUpdatedAt()`, custom JPQL queries.

Both are injected across the codebase — `UserServiceImpl` uses `UserRepository`, `MatchRecommendationService` uses `UserJpaRepository`, `AccountController` uses both. This creates confusion about which to use for new code, duplicates the save path (custom SQL vs JPA merge), and prevents leveraging Spring Data's query derivation.

#### Files Affected

- `src/main/java/com/example/dating/postgres/UserRepository.java` (remove after migration)
- `src/main/java/com/example/dating/postgres/impl/UserRepositoryImpl.java` (remove after migration)
- `src/main/java/com/example/dating/repositories/UserJpaRepository.java` (add missing methods)
- All files that import `com.example.dating.postgres.UserRepository` (update imports)

#### Implementation Tasks

1. **Audit `postgres/UserRepository` methods** — for each, determine if `UserJpaRepository` already has an equivalent or if one needs to be added:
   - `save(User)` → `UserJpaRepository.save(UserEntity)` (already exists via JpaRepository)
   - `findById(String)` → already exists via JpaRepository
   - `findByEmail(String)` → add `Optional<UserEntity> findByEmail(String email)` to `UserJpaRepository`
   - `findBySpotifyId(String)` → add `Optional<UserEntity> findBySpotifyId(String spotifyId)`
   - `findByVerificationTokenHash(String)` → add derived query method
   - `findByResetPasswordTokenHash(String)` → add derived query method
   - `deleteById(String)` → already exists via JpaRepository
2. **Handle the domain model gap**: The custom `UserRepository` returns `User` (domain object), while `UserJpaRepository` returns `UserEntity` (JPA entity). Callers that use `User` must be updated to either:
   - Accept `UserEntity` directly (simpler, less mapping)
   - Use `UserMapper` to convert (preserves domain boundary)
3. **Migrate callers one service at a time**: Start with `AuthServiceImpl` (highest usage), then `UserServiceImpl`, `OnboardingServiceImpl`, `SpotifyServiceImpl`, controllers
4. **Delete `postgres/UserRepository` and `postgres/impl/UserRepositoryImpl`** after all callers migrated
5. **Delete the `postgres/` package** if empty

#### Verification

- [ ] `postgres/` package deleted — no manual SQL implementation remains
- [ ] All user queries go through `UserJpaRepository`
- [ ] `./mvnw compile` passes
- [ ] No duplicate save paths for `UserEntity`
- [ ] Token lookup queries (`findByVerificationTokenHash`, etc.) work via Spring Data derived queries

#### Notes

This is a larger refactor that touches many files. Consider doing it in two sub-steps: (1) add all methods to `UserJpaRepository` and verify they work, (2) migrate callers and delete the old interface. If the `User` domain object is heavily used, the mapper conversion adds noise — evaluate whether collapsing `User` and `UserEntity` into a single class is worth the additional scope.

---

### - [x] Batch D — Standardize Error Response Format ✅ 2026-03-19

**Priority:** P1 | **Risk:** Low | **Effort:** 2–3 hours
**Depends on:** Batch A (tests must compile to verify no contract breakage)

#### Problem

`GlobalExceptionHandler` returns inconsistent response shapes:

- Validation errors: `{"error": "Validation failed", "fields": {...}}`
- Domain errors: `{"error": "message"}`
- Optimistic lock: `{"error": "Concurrent modification detected, please retry"}`
- Data integrity: `{"error": "A conflict occurred..."}`

No `code` field for programmatic error handling by the frontend. No `timestamp`. No `path`. The frontend must parse error message strings to distinguish error types.

#### Files Affected

- `src/main/java/com/example/dating/exceptions/GlobalExceptionHandler.java` (modify all handlers)
- New: `src/main/java/com/example/dating/models/common/ErrorResponse.java`
- Frontend integration docs need updating

#### Implementation Tasks

1. Create `ErrorResponse` record/class:
   ```java
   public record ErrorResponse(
       String code,           // e.g. "VALIDATION_ERROR", "DUPLICATE_SWIPE", "UNAUTHORIZED"
       String message,        // Human-readable message
       Map<String, String> fields,  // Only for validation errors, null otherwise
       Instant timestamp
   ) {}
   ```
2. Define error code constants (e.g., in an `ErrorCode` class or enum):
   - `VALIDATION_ERROR`, `INVALID_ARGUMENT`, `INVALID_TOKEN`, `UNAUTHORIZED`, `FORBIDDEN`
   - `NOT_FOUND`, `CONFLICT`, `DUPLICATE_SWIPE`, `CONCURRENT_MODIFICATION`
   - `EMAIL_EXISTS`, `ACCOUNT_LOCKED`, `SPOTIFY_TOKEN_EXPIRED`, `SPOTIFY_UNAVAILABLE`
   - `INTERNAL_ERROR`
3. Update every `@ExceptionHandler` in `GlobalExceptionHandler` to return `ResponseEntity<ErrorResponse>`
4. **Preserve HTTP status codes** — only the body shape changes, not the status
5. Update `BACKEND_PROJECT_STATUS.md` with the new error response contract so the frontend Claude can integrate

#### Verification

- [x] All `@ExceptionHandler` methods return `ErrorResponse`
- [x] Every response includes `code`, `message`, `timestamp`
- [x] Validation errors include `fields` map
- [x] HTTP status codes unchanged from current behavior
- [x] `./mvnw compile` passes
- [x] Frontend docs updated with new error shape (BACKEND_PROJECT_STATUS.md)

---

## Phase 3 — Resilience & Observability (P2)

### - [x] Batch E — Circuit Breakers for External APIs ✅ 2026-03-19

**Priority:** P2 | **Risk:** Medium | **Effort:** 3–4 hours
**Depends on:** Nothing

#### Problem

The backend calls two external APIs with no circuit breaker:

1. **Spotify Web API** — used for token refresh, top artists/tracks/genres, genre sync. If Spotify is rate-limiting or down, every user request that touches Spotify will hang for 10s (read timeout) then fail. With 200 Tomcat threads, 20 concurrent Spotify-dependent requests can exhaust the thread pool.

2. **Google Maps Geocoding API** — used during onboarding location step. If the API key is invalid or quota exceeded, geocoding fails silently and users have no location data (breaking distance-based matching).

Current resilience: `JwtServiceImpl.refreshToken()` has a 3-attempt retry with backoff, and `SpotifyGenreSyncService` tolerates partial range failures. But there is no circuit breaker to stop repeated calls to a known-down service.

#### Files Affected

- `pom.xml` (add Resilience4j dependency)
- `src/main/java/com/example/dating/services/impl/SpotifyServiceImpl.java`
- `src/main/java/com/example/dating/services/impl/JwtServiceImpl.java` (token refresh)
- `src/main/java/com/example/dating/services/matching/SpotifyGenreSyncService.java`
- `src/main/java/com/example/dating/services/impl/GeocodingServiceImpl.java`
- New: `src/main/resources/application.yml` (Resilience4j config section)

#### Implementation Tasks

1. Add Resilience4j Spring Boot starter to `pom.xml`:
   ```xml
   <dependency>
       <groupId>io.github.resilience4j</groupId>
       <artifactId>resilience4j-spring-boot3</artifactId>
       <version>2.2.0</version>
   </dependency>
   ```
2. Configure two circuit breakers in `application.yml`:
   ```yaml
   resilience4j:
     circuitbreaker:
       instances:
         spotify:
           slidingWindowSize: 10
           failureRateThreshold: 50
           waitDurationInOpenState: 30s
           permittedNumberOfCallsInHalfOpenState: 3
         geocoding:
           slidingWindowSize: 5
           failureRateThreshold: 60
           waitDurationInOpenState: 60s
           permittedNumberOfCallsInHalfOpenState: 2
   ```
3. Annotate Spotify API call methods with `@CircuitBreaker(name = "spotify", fallbackMethod = "...")`
4. Annotate geocoding call with `@CircuitBreaker(name = "geocoding", fallbackMethod = "...")`
5. Implement fallback methods:
   - Spotify: throw `SpotifyApiException("Spotify service temporarily unavailable — circuit open")`
   - Geocoding: log warning, return null coordinates (caller already handles null location)
6. Ensure `SpotifyTokenRevokedException` (401/403) does **NOT** count as a circuit breaker failure — these are expected for revoked tokens, not API outages. Use `ignoreExceptions` config.

#### Verification

- [x] Resilience4j dependency in `pom.xml`
- [x] Circuit breaker config in `application.yml`
- [x] `@CircuitBreaker` on Spotify and geocoding methods
- [x] 401/403 Spotify responses do not trip the circuit breaker
- [x] `./mvnw compile` passes
- [x] Application starts without Resilience4j config errors

---

### - [x] Batch F — Spring Actuator + Health/Metrics Endpoints ✅ 2026-03-19

**Priority:** P2 | **Risk:** Low | **Effort:** 2–3 hours
**Depends on:** Nothing

#### Problem

No health check, metrics, or info endpoints exist. This blocks:
- Container orchestration (K8s/ECS) liveness and readiness probes
- Monitoring dashboards (Grafana, Datadog)
- Connection pool visibility (HikariCP metrics)
- Async task executor visibility (queue depth, active threads)

#### Files Affected

- `pom.xml` (add Actuator + Micrometer)
- `src/main/resources/application.yml` (Actuator config)
- `src/main/java/com/example/dating/security/SecurityConfig.java` (permit Actuator endpoints)

#### Implementation Tasks

1. Add Spring Boot Actuator to `pom.xml`:
   ```xml
   <dependency>
       <groupId>org.springframework.boot</groupId>
       <artifactId>spring-boot-starter-actuator</artifactId>
   </dependency>
   ```
2. Configure Actuator in `application.yml`:
   ```yaml
   management:
     endpoints:
       web:
         exposure:
           include: health,info,metrics,prometheus
     endpoint:
       health:
         show-details: when_authorized
         probes:
           enabled: true
     health:
       db:
         enabled: true
       redis:
         enabled: true
   ```
3. Update `SecurityConfig` to permit Actuator endpoints:
   ```java
   .requestMatchers("/actuator/health/**").permitAll()
   .requestMatchers("/actuator/**").hasRole("ADMIN") // or authenticated
   ```
   For initial deployment, permit health endpoints publicly (needed for load balancer probes) and restrict others.
4. Verify HikariCP metrics auto-register (Spring Boot auto-configures this with Actuator present)
5. Verify async task executor metrics are exposed (thread pool stats)

#### Verification

- [x] `GET /actuator/health` returns `{"status": "UP"}` with DB and Redis components
- [x] `GET /actuator/health/liveness` and `/readiness` return 200
- [x] `GET /actuator/metrics/hikaricp.connections.active` returns pool data
- [x] Actuator endpoints not accessible without auth (except `/health`)
- [x] `./mvnw compile` passes

---

### - [x] Batch G — Flyway Migration Strategy Resolution ✅ 2026-03-19

**Priority:** P2 | **Risk:** Medium | **Effort:** 2–3 hours
**Depends on:** Nothing

#### Problem

The codebase has **both** Flyway and Hibernate DDL management configured, creating ambiguity:

- `application.yml`: `ddl-auto: validate` + Flyway enabled with `baseline-on-migrate: true`
- `application-dev.yml`: `ddl-auto: update` (overrides in dev profile)
- 4 migration files exist: `V1__Create_Matching_Tables.sql`, `V2__Auth_Token_Hardening.sql`, `V3__Match_Unique_Constraint.sql`, `V4__Behavioral_Profile_Version.sql`
- An old `V1_0__Create_Matching_Tables.sql` is staged but deleted (git status: `AD`)

**Risks:**
- In production (`validate`), if Flyway migrations haven't run, Hibernate validation fails and the app won't start
- In dev (`update`), Hibernate auto-creates columns that may not match migration definitions
- The `Match.unmatchedBy` column was widened from `VARCHAR(10)` to `VARCHAR(36)` in code but no migration exists for it
- Indexes added via `@Index` annotations may not exist in production if only Flyway is trusted

#### Files Affected

- `src/main/resources/application.yml`
- `src/main/resources/application-dev.yml`
- `src/main/resources/db/migration/V5__*.sql` (new migration)
- Entity files with `@Index` annotations

#### Implementation Tasks

1. **Commit to Flyway as the authoritative schema manager**:
   - Set `ddl-auto: validate` in all profiles (including dev) — Hibernate validates but never modifies
   - Remove `ddl-auto: update` from `application-dev.yml`
2. **Create `V5__Schema_Alignment.sql`** to capture all schema changes made by `ddl-auto: update` that aren't in V1–V4:
   - All `@Index` annotations added during JPA/Scalability audits (audit entity files for `@Table(indexes = ...)`)
   - `Match.unmatchedBy` column type: `ALTER TABLE matches ALTER COLUMN unmatched_by TYPE VARCHAR(36)`
   - `Match.matchScoreB` column (added in Integrity Batch H)
   - Any missing `version` columns not covered by V4
3. **Clean up the deleted migration**: Remove the staged-then-deleted `V1_0__Create_Matching_Tables.sql` from git
4. **Document the migration workflow** in a comment at the top of `application.yml`:
   ```yaml
   # Schema management: Flyway is authoritative. ddl-auto: validate ensures code matches DB.
   # To add a schema change: create a new V{N}__Description.sql migration file.
   # NEVER use ddl-auto: update in any profile.
   ```
5. **Test**: Drop and recreate the local database, run `./mvnw spring-boot:run`, confirm all migrations apply and Hibernate validation passes

#### Verification

- [x] `ddl-auto: validate` in all profiles
- [x] `V5__Schema_Alignment.sql` exists and includes all missing indexes/columns
- [x] App starts cleanly with a fresh database (Flyway creates everything)
- [x] Hibernate validation passes (no missing columns/tables)
- [x] `./mvnw compile` passes

---

## Phase 4 — Production Hardening (P3)

### - [x] Batch H — Structured Logging with Correlation IDs ✅ 2026-03-19

**Priority:** P3 | **Risk:** Low | **Effort:** 3–4 hours
**Depends on:** Batch F (Actuator/Micrometer should be in place)

#### Problem

All logging uses unstructured SLF4J `log.info/warn/error` with no request correlation. In production with multiple concurrent users:
- Cannot trace a single request across service calls
- Cannot correlate Spotify sync failures with the originating user request
- Cannot aggregate logs by request for debugging
- Log format is plain text — not parseable by log aggregation tools (ELK, Datadog, CloudWatch)

#### Files Affected

- `pom.xml` (add structured logging dependency if needed)
- `src/main/resources/logback-spring.xml` (new — Logback config)
- `src/main/java/com/example/dating/security/` (add MDC filter for correlation ID)

#### Implementation Tasks

1. Create `logback-spring.xml` with structured JSON output for non-dev profiles:
   ```xml
   <springProfile name="!dev">
     <!-- JSON layout with timestamp, level, logger, message, correlationId, userId -->
   </springProfile>
   <springProfile name="dev">
     <!-- Human-readable console output -->
   </springProfile>
   ```
2. Create a `CorrelationIdFilter` (servlet filter, ordered before Spring Security):
   - Generate UUID correlation ID per request (or read from `X-Correlation-Id` header if present)
   - Put in MDC: `MDC.put("correlationId", id)`
   - Set response header: `X-Correlation-Id`
   - Clear MDC in `finally` block
3. Register the filter in `SecurityConfig` or as a `@Component` with `@Order(Ordered.HIGHEST_PRECEDENCE)`
4. For `@Async` methods: configure the async executor to propagate MDC context (wrap `Runnable` with MDC copy)
5. Update `AsyncConfig` task executor to use MDC-propagating decorator

#### Verification

- [x] Dev profile: human-readable logs (unchanged from current)
- [x] Non-dev profile: JSON-structured logs with `correlationId` field
- [x] `X-Correlation-Id` header present in all responses
- [x] Async behavioral profile updates carry the originating request's correlation ID
- [x] `./mvnw compile` passes

---

### - [x] Batch I — OpenAPI Documentation with SpringDoc ✅ 2026-03-19

**Priority:** P3 | **Risk:** Low | **Effort:** 2–3 hours
**Depends on:** Nothing

#### Problem

API documentation is maintained manually in `BACKEND_PROJECT_STATUS.md`. This drifts from actual endpoints, lacks request/response examples, and requires the frontend developer (or frontend Claude agent) to read a prose document instead of an interactive spec.

#### Files AffectedWe

- `pom.xml` (add SpringDoc OpenAPI)
- `src/main/resources/application.yml` (SpringDoc config)
- `src/main/java/com/example/dating/security/SecurityConfig.java` (permit Swagger UI)
- Controller classes (optional — add `@Operation` annotations for enrichment)

#### Implementation Tasks

1. Add SpringDoc OpenAPI to `pom.xml`:
   ```xml
   <dependency>
       <groupId>org.springdoc</groupId>
       <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
       <version>2.8.6</version>
   </dependency>
   ```
2. Configure in `application.yml`:
   ```yaml
   springdoc:
     api-docs:
       path: /api-docs
     swagger-ui:
       path: /swagger-ui.html
       operationsSorter: method
     default-produces-media-type: application/json
   ```
3. Update `SecurityConfig` to permit:
   ```java
   .requestMatchers("/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
   ```
4. Add `@Tag` annotations to controllers for grouping (e.g., `@Tag(name = "Matching")`)
5. Optionally add `@Operation` and `@ApiResponse` to key endpoints (swipe, potential matches, score)
6. Verify the generated spec is accurate by comparing with `BACKEND_PROJECT_STATUS.md`

#### Verification

- [x] `GET /swagger-ui.html` renders interactive documentation (dev profile only)
- [x] `GET /api-docs` returns valid OpenAPI 3.0 JSON (dev profile only)
- [x] All 7 production controllers appear with `@Tag` grouping (AuthController, OnboardingController, MatchingController, GenrePreferenceController, UserController, AccountController, SpotifyPublicController)
- [x] Test controllers (`/api/test/**`) do NOT appear (they're `@Profile("dev")` — confirmed)
- [x] Swagger UI and api-docs DISABLED in production (springdoc.*.enabled: false in application.yml)
- [x] `./mvnw compile` passes

---

### - [x] Batch J — Distributed Locking for Multi-Instance Deployment

**Priority:** P3 | **Risk:** Medium | **Effort:** 3–4 hours
**Depends on:** Nothing (Redis already configured)

#### Problem

Two locking mechanisms are instance-local and will break under multi-instance deployment:

1. **`UserServiceImpl.tokenRefreshLocks`** — `ConcurrentHashMap<String, Object>` for per-user Spotify token refresh serialization. With 2+ instances, both can refresh the same user's token simultaneously, wasting Spotify API quota and potentially storing stale tokens.

2. **`SpotifyGenreSyncService.syncInProgress`** — `ConcurrentHashMap<String, Boolean>` guarding against concurrent genre syncs for the same user. With 2+ instances, both can sync simultaneously.

#### Files Affected

- `src/main/java/com/example/dating/services/impl/SpotifyTokenServiceImpl.java` (or `UserServiceImpl.java` if Batch B not done)
- `src/main/java/com/example/dating/services/matching/SpotifyGenreSyncService.java`
- New: `src/main/java/com/example/dating/config/DistributedLockService.java` (optional abstraction)

#### Implementation Tasks

1. Create a `DistributedLockService` that wraps Redis `SETNX` with TTL:
   ```java
   @Service
   @RequiredArgsConstructor
   public class DistributedLockService {
       private final StringRedisTemplate redisTemplate;

       public boolean tryLock(String key, Duration ttl) {
           return Boolean.TRUE.equals(
               redisTemplate.opsForValue().setIfAbsent(key, "locked", ttl));
       }

       public void unlock(String key) {
           redisTemplate.delete(key);
       }
   }
   ```
2. Add fallback to in-memory `ConcurrentHashMap` when Redis is unavailable (matching the existing `CaffeineRateLimiter` fallback pattern)
3. Replace `tokenRefreshLocks` with:
   ```java
   String lockKey = "lock:spotify:refresh:" + user.getId();
   if (!distributedLockService.tryLock(lockKey, Duration.ofSeconds(30))) {
       // Another instance is refreshing — wait briefly and re-read
   }
   try { ... } finally { distributedLockService.unlock(lockKey); }
   ```
4. Replace `syncInProgress` ConcurrentHashMap with distributed lock:
   ```java
   String lockKey = "lock:spotify:sync:" + user.getId();
   ```
5. Set lock TTL to 30s for token refresh and 60s for genre sync (safety timeout if instance crashes mid-lock)

#### Verification

- [x] Redis `SETNX` used for both lock types
- [x] In-memory fallback works when Redis is unavailable
- [x] Lock TTL prevents deadlock on instance crash
- [x] Token refresh: two concurrent calls to the same user result in one Spotify API call
- [x] Genre sync: two concurrent calls to the same user result in one sync operation
- [x] `./mvnw compile` passes

---

## Audit Issue Reference

| # | Description | Batch | Status |
|---|-------------|-------|--------|
| 1 | 286 test compilation errors across 23 files — no tests can run | A | Pending |
| 2 | `UserServiceImpl` god class — 12 dependencies, 4 responsibilities, mutable shared state | B | Complete |
| 3 | Dual repository pattern (`postgres/UserRepository` + `repositories/UserJpaRepository`) | C | Pending |
| 4 | Inconsistent error response shapes — no `code` field, no `timestamp` | D | Complete |
| 5 | No circuit breaker for Spotify or Google Maps APIs | E | Pending |
| 6 | No health/metrics/monitoring endpoints (Spring Actuator) | F | Pending |
| 7 | Flyway + Hibernate DDL ambiguity — migrations may not match schema | G | Pending |
| 8 | Unstructured logging with no request correlation IDs | H | Pending |
| 9 | No auto-generated API documentation — manual docs drift from code | I | Complete |
| 10 | Instance-local locks break under multi-instance deployment | J | Pending |

---

## Out of Scope (Documented for Future Reference)

The following were identified during the audit but are deferred beyond this roadmap:

### Architecture Debt
- **String UUID → Native PostgreSQL UUID migration**: All entities use `VARCHAR(36)` IDs. Native `UUID` type is 16 bytes, faster to index/join. Deferred per JPA Roadmap Batch H — performance cost acceptable at current scale. Revisit when load testing indicates FK join bottleneck.
- **In-memory gender/distance filtering**: `MatchRecommendationService` fetches candidates from DB then filters in Java. Moving filters to JPQL would reduce transferred rows but requires complex spatial query support. Documented in JPA Roadmap additional notes.
- **CQRS for recommendations**: If read/write patterns diverge significantly, consider separating the recommendation read model from the swipe write model.
- **Event sourcing for audit trail**: If compliance requires who/when/what tracking for sensitive operations, adopt event sourcing.

### Frontend Integration
- **Controller refactor roadmap**: See `project_controller_roadmap.md` — 8-batch plan for controller security, validation, error handling, auth standardisation, DTO cleanup, pagination, architecture. Overlaps with Batch D in this roadmap.
- **WebSocket for real-time match notifications**: Currently match events are fire-and-forget. Real-time notification requires WebSocket or SSE integration.

### Infrastructure
- **Redis cluster configuration**: Current Redis config is single-node. Production needs Sentinel or Cluster mode for HA.
- **Database read replicas**: Route `@Transactional(readOnly = true)` queries to read replicas for horizontal scaling.
- **Feature flags**: FF4J or LaunchDarkly for safe rollouts of matching algorithm changes.
