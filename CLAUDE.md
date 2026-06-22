# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Session Protocol

- Before each first prompt of the session, read `CLAUDE_COMMANDS.md` and follow its rules
- Read both `BACKEND_PROJECT_STATUS.md` and `FRONTEND_PROJECT_STATUS.md` from `C:\Users\MladenHangi\dating-app-docs\` each session
- Update `BACKEND_PROJECT_STATUS.md` after completing work

## Build & Run Commands

```bash
# Build (skip tests)
./mvnw clean package -DskipTests

# Run the application
./mvnw spring-boot:run

# Run all tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=Phase1SimpleTest

# Run a single test method
./mvnw test -Dtest=Phase1SimpleTest#testMethodName

# Run tests in the matching package
./mvnw test -Dtest="com.example.dating.matching.*"
```

## Tech Stack

- **Java 17**, **Spring Boot 3.5.7**, **Maven**
- **PostgreSQL** on `localhost:5433`, database `dating` (user: root / secret)
- **Spring Security 6** with JWT (OAuth2 Resource Server + jjwt 0.12.5)
- **Lombok** (`@RequiredArgsConstructor` + `final` fields for DI everywhere)
- **Resend** for transactional email
- **Spotify Web API** integration for music data
- **Google Maps / Nominatim** for geocoding
- **Flyway** is authoritative for schema (`ddl-auto: validate`, baseline version 8). Schema changes require a new `V{N}__Description.sql` migration in `src/main/resources/db/migration` — never use `ddl-auto: update`

## Architecture

### Package Layout (`com.example.dating`)

- `controllers/` — REST endpoints, all under `AppConstants.BASE_API_ROUTE` = `/api/v1`
- `services/` + `services/impl/` — Business logic with interface/impl separation
- `services/matching/` — Matching algorithm v2.0 (multi-dimensional scoring + behavioral learning)
- `models/` — Entities (`dao`) and DTOs (`dto`), organized by domain (user, matching, auth, spotify, onboarding)
- `repositories/` — Spring Data JPA repositories for matching entities
- `postgres/` + `postgres/impl/` — Custom `UserRepository` interface with manual implementation (separate from JPA repos)
- `security/` — `SecurityConfig` with JWT decoder, CORS, endpoint authorization
- `config/` — Seed data loaders for dev (`UserSeedDataLoader`, `ExtendedUserSeedDataLoader`, `GenreSeedDataLoader`)
- `enums/user/` — User-related enums
- `exceptions/` — Custom exceptions with global handler

### Data Access: Dual Repository Pattern

There are **two** repository patterns coexisting:
1. **`postgres/UserRepository`** — Custom interface with manual SQL impl in `postgres/impl/`. Used for the core `User` entity.
2. **`repositories/*Repository`** — Standard Spring Data JPA repositories. Used for matching entities (`Match`, `UserSwipe`, `UserMatchScore`, `CanonicalGenre`, `UserGenrePreference`, `UserBehavioralProfile`) and user sub-entities (photos, lifestyle, personality, etc.).

### Matching Algorithm v2.0

The matching system is the core feature. Key design:

- **`MatchScoringService`** — Orchestrator that combines 5 dimensions: music, lifestyle, interests, location, behavioral
- **Score is directional** — A→B ≠ B→A (because `musicMatchImportance` and behavioral profile are per-user)
- **Behavioral learning** — `BehavioralProfileService` updates a user's profile after each swipe; confidence = min(1.0, totalLikes/50.0); contributes up to 40% of final score
- **Cold start** — `BehavioralScoreCalculator` returns 50.0 if totalLikes < 5
- **Cache** — Pre-computed scores in `user_match_scores` table with `algorithmVersion = "v2.0"`; stale if either user updated since `computedAt`
- **Gender matching** — Uses `Set<String>` split on comma (never `String.contains` to avoid MALE/FEMALE substring bug)
- **`@Lazy`** on `BehavioralScoreCalculator` field in `MatchScoringService` to break circular dependency

### Security & Endpoint Authorization

- `/api/v1/auth/**` (except `connect-spotify`, which is authenticated), `/api/v1/spotify/**`, `/public/**`, `/actuator/health` — No auth required
- `/api/test/**` — Phase test controllers are `@Profile("dev")` only; they do not exist outside the dev profile (and still require a JWT when they do)
- Everything else — Requires valid JWT in `Authorization: Bearer <token>` header; unverified-email users are additionally blocked by `EmailVerificationFilter` (403 `EMAIL_VERIFICATION_REQUIRED`) outside auth/onboarding/public paths
- All errors use the standard envelope `{code, message, fields, timestamp}` (`ErrorResponse` + `ErrorCode` constants) — including filter/interceptor responses
- CORS allows: `localhost:3000`, `localhost:5173`, `localhost:4200`, `localhost:8081`

### Key API Groups

| Controller | Path | Purpose |
|---|---|---|
| `AuthController` | `/api/v1/auth` | Register, login, Spotify login, email verification, password reset |
| `OnboardingController` | `/api/v1/onboarding` | Multi-step profile setup (basic info, location, photos, music, lifestyle, personality, dating prefs, privacy) |
| `MatchingController` | `/api/v1/matching` | Score calculation, potential matches, swipe, match management, analytics |
| `GenrePreferenceController` | `/api/v1/preferences/genres` | Music genre preferences CRUD + Spotify sync |

### Multi-Agent Workflow

This backend is developed alongside a **separate frontend project** (`C:\Users\MladenHangi\dating-app`). Both share documentation in `C:\Users\MladenHangi\dating-app-docs\`. When adding/changing API endpoints, update `BACKEND_PROJECT_STATUS.md` with request/response examples so the frontend Claude can integrate.

## Environment Variables

Required: `JWT_SECRET_KEY` (min 32 bytes — startup fails otherwise), `ENCRYPTION_SECRET_KEY`, `SPOTIFY_CLIENT_ID`, `SPOTIFY_CLIENT_SECRET`, `GOOGLE_MAPS_API_KEY` (unused in dev profile, which uses Nominatim), `RESEND_API_KEY`. Defaults exist for `FRONTEND_URL`, `MAIL_FROM`, `DB_USERNAME`/`DB_PASSWORD`, and `REDIS_HOST`/`REDIS_PORT` (Redis is optional — rate limiting falls back to in-process Caffeine).