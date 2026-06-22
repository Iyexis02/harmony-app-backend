# Backend Stability Fix Roadmap — Security Audit

## Purpose

This document tracks the security hardening refactor for the dating app Spring Boot backend. It addresses 15 vulnerabilities identified during a full security audit covering authentication flow, authorization rules, JWT handling, password storage, CSRF, input sanitization, sensitive endpoint exposure, rate limiting, and data leaks.

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
A (independent — foundation)
B (independent — foundation)
A ─► C
B ─► D
C ─► E
D ─► E
E ─► F
G (independent of all others)
```

## Recommended Implementation Order

```
Phase 1 — Critical Fixes (P0)
  1. Batch A — Secret Logging Removal + JWT Claim Reduction
  2. Batch B — Input Validation Hardening

Phase 2 — Auth & Access Control (P1)
  3. Batch C — Token Version Legacy Cutoff + HMAC Key Validation   [depends on A]
  4. Batch D — IDOR Protection + Pagination Bounds                 [depends on B]

Phase 3 — Server-Side Enforcement (P2)
  5. Batch E — Email Verification Enforcement + Account Lockout    [depends on C, D]

Phase 4 — Infrastructure & Leak Prevention (P3)
  6. Batch F — Rate Limit Proxy Trust + Auth Endpoint Rate Limits  [depends on E]
  7. Batch G — Information Disclosure Hardening                    [independent]
```

---

## Phase 1 — Critical Fixes (P0)

### - [x] Batch A — Secret Logging Removal + JWT Claim Reduction
<!-- Completed: 2026-03-16 -->

**Priority:** CRITICAL / HIGH
**Risk:** Secrets written to log files; JWT PII readable by anyone who intercepts a token
**Affected files:**
- `services/impl/JwtServiceImpl.java`
- `security/SecurityConfig.java`

**What to do:**

1. **Remove `log.info(claims.toString())` at `JwtServiceImpl:61`.**
   - This logs ALL JWT claims (email, authProvider, tokenVersion) on every `getUserIdFromToken()` call.
   - Delete the line entirely. If debug-level diagnostics are needed, log only the `userId` claim at `debug` level.

2. **Downgrade Spotify token logging in `JwtServiceImpl:refreshToken()`.**
   - Lines 92–93: remove the refresh token prefix log (`log.debug` that prints first 10 chars of refresh token).
   - Lines 104–107: remove the auth header log (`log.debug` that prints first 20 chars of Basic auth header).
   - Line 132: remove `log.debug("Response body: {}", response.getBody())` — may contain access tokens.
   - Replace all with a single `log.debug("Spotify token refresh completed with status {}", response.getStatusCode())`.

3. **Remove PII from JWT claims in `JwtServiceImpl:generateToken()`.**
   - Lines 172–174: remove `email`, `authProvider`, and `emailVerified` claims from the token body.
   - Keep only: `userId` (line 171), `tokenVersion` (line 175), `sub` (line 179).
   - **Impact check:** search the entire codebase for any code that reads `email`, `authProvider`, or `emailVerified` from the JWT. If found, refactor those call sites to fetch from the DB using `userId` instead.

**Verification:**
- `grep -rn "claims.toString()" src/` returns zero matches
- `grep -rn "getClaimAsString.*email" src/` returns zero matches (except test controllers if still present)
- JWT tokens decoded at https://jwt.io contain only `userId`, `tokenVersion`, `sub`, `iat`, `exp`

---

### - [x] Batch B — Input Validation Hardening
<!-- Completed: 2026-03-16 -->

**Priority:** HIGH / MEDIUM
**Risk:** Null/oversized payloads reach encryption service; unbounded action strings accepted
**Affected files:**
- `models/auth/ConnectSpotifyRequestDto.java`
- `models/matching/dto/SwipeRequestDto.java`

**What to do:**

1. **Add validation to `ConnectSpotifyRequestDto`.**
   - Currently has ZERO validation annotations — all fields can be null or arbitrarily large.
   - Add:
     ```java
     @NotBlank(message = "Spotify ID is required")
     @Size(max = 255, message = "Spotify ID too long")
     private String spotifyId;

     @NotBlank(message = "Spotify access token is required")
     @Size(max = 2048, message = "Spotify access token too long")
     private String spotifyAccessToken;

     @NotBlank(message = "Spotify refresh token is required")
     @Size(max = 2048, message = "Spotify refresh token too long")
     private String spotifyRefreshToken;

     @NotNull(message = "Token expiration timestamp is required")
     private Long spotifyTokenExpiresAt;
     ```

2. **Restrict `SwipeRequestDto.action` to allowed values.**
   - Currently only `@NotBlank` — any string is accepted.
   - Add: `@Pattern(regexp = "^(like|pass|super_like|block)$", message = "Action must be like, pass, super_like, or block")`
   - This is defense-in-depth; the service layer may also validate, but the boundary should reject invalid input early.

3. **Add `@Size(max = 50)` to `SwipeRequestDto.platform`** to prevent oversized strings in the `platform` field.

**Verification:**
- POST `/api/v1/auth/connect-spotify` with `{}` body returns 400 with field-level errors
- POST `/api/v1/matching/swipe` with `{"swipedUserId":"x","action":"invalid"}` returns 400
- All existing tests pass (no tests should be sending invalid actions)

---

## Phase 2 — Auth & Access Control (P1)

### - [x] Batch C — Token Version Legacy Cutoff + HMAC Key Validation
<!-- Completed: 2026-03-16 -->

**Priority:** MEDIUM
**Risk:** Pre-Batch-B tokens without `tokenVersion` claim are permanently valid even after password reset; weak HMAC keys accepted silently
**Depends on:** Batch A (JWT claim changes must land first so new tokens are clean)
**Affected files:**
- `security/SecurityConfig.java`
- `services/impl/JwtServiceImpl.java` (or a new config validator)

**What to do:**

1. **Reject tokens without `tokenVersion` claim.**
   - In `SecurityConfig.jwtDecoder()` (lines 106–111), the current logic passes through tokens that have no `tokenVersion` claim.
   - Change the logic: if `userId` is present but `tokenVersion` is null, **reject the token** with `throw new BadJwtException("Token missing required version claim")`.
   - This means all pre-Batch-B tokens will be invalidated. Users will need to re-login. This is acceptable for a security hardening pass.

2. **Validate HMAC key minimum length at startup.**
   - In `SecurityConfig` or a `@PostConstruct` init method, check that `secret.getBytes(StandardCharsets.UTF_8).length >= 32`.
   - If shorter, throw `IllegalStateException("JWT_SECRET_KEY must be at least 32 bytes (256 bits)")`.
   - Also change both usages of `secret.getBytes()` (in `SecurityConfig:99` and `JwtServiceImpl:183`) to `secret.getBytes(StandardCharsets.UTF_8)` for deterministic encoding.

**Verification:**
- Application fails to start with a 16-char JWT_SECRET_KEY (startup validation)
- A JWT without `tokenVersion` claim returns 401
- A JWT with correct `tokenVersion` still works
- Password reset still invalidates all prior tokens

---

### - [x] Batch D — IDOR Protection + Pagination Bounds
<!-- Completed: 2026-03-16 -->

**Priority:** HIGH / MEDIUM
**Risk:** Any authenticated user can calculate match scores against arbitrary users (profile enumeration); unbounded pagination causes DoS
**Depends on:** Batch B (validation patterns established)
**Affected files:**
- `controllers/MatchingController.java`
- `services/matching/MatchRecommendationService.java` (if self-check/block-check added there)

**What to do:**

1. **Add self-check and block-check to `getMatchScore()`.**
   - At `MatchingController:53`, before calling `recommendationService.getMatchScore()`:
     ```java
     if (currentUser.getId().equals(otherUserId)) {
         return ResponseEntity.badRequest().body(null); // or a proper error DTO
     }
     ```
   - In the service layer (or controller), verify the target user is not blocked by / has not blocked the current user. Return 403 if blocked.

2. **Cap pagination parameters across all paginated endpoints.**
   - `getPotentialMatches()` (line 64): cap `limit` to max 100, floor `offset` at 0.
   - `getMatches()` (line 99): same caps.
   - Implementation: add a private helper in `MatchingController`:
     ```java
     private int clampLimit(int limit) { return Math.max(1, Math.min(limit, 100)); }
     private int clampOffset(int offset) { return Math.max(0, offset); }
     ```
   - Apply to all `limit` and `offset` parameters before passing to services.

3. **Validate `status` query param in `getMatches()`.**
   - Line 98: currently any string silently falls through to `getAllMatches`.
   - Restrict to `active` or `all`. Return 400 for anything else.

**Verification:**
- GET `/api/v1/matching/score/{selfId}` returns 400
- GET `/api/v1/matching/potential?limit=999999` uses limit=100
- GET `/api/v1/matching/potential?offset=-5` uses offset=0
- GET `/api/v1/matching/matches?status=hacked` returns 400

---

## Phase 3 — Server-Side Enforcement (P2)

### - [x] Batch E — Email Verification Enforcement + Account Lockout
<!-- Completed: 2026-03-16 -->

**Priority:** MEDIUM
**Risk:** Unverified-email accounts have full API access; no per-account lockout allows distributed brute-force
**Depends on:** Batch C (token claims finalized), Batch D (controller patterns established)
**Affected files:**
- `security/SecurityConfig.java` (or a new `EmailVerificationFilter.java`)
- `services/impl/AuthServiceImpl.java`
- `models/user/common/dao/UserEntity.java` (add `failedLoginAttempts` + `lockedUntil` fields)

**What to do:**

1. **Enforce email verification server-side.**
   - Option A (recommended): add a servlet filter that runs after JWT authentication. For any request outside `/api/v1/auth/**` and `/api/v1/onboarding/**`, look up the user's `emailVerified` status. If `false`, return 403 with `{"error": "Email verification required"}`.
   - Option B: add a `@PreAuthorize` custom expression. More granular but more verbose.
   - **Exception list:** onboarding endpoints should remain accessible to unverified users so they can complete their profile while waiting for the verification email.

2. **Add per-account failed login lockout.**
   - Add two columns to `UserEntity`: `failedLoginAttempts` (int, default 0) and `lockedUntil` (LocalDateTime, nullable).
   - In `AuthServiceImpl.login()`:
     - Before password check: if `lockedUntil` is in the future, throw `AccountLockedException("Account temporarily locked. Try again later.")`.
     - On password mismatch: increment `failedLoginAttempts`. If >= 5, set `lockedUntil = now + 15 minutes`.
     - On successful login: reset `failedLoginAttempts` to 0 and `lockedUntil` to null.
   - Create `AccountLockedException` extending `RuntimeException`. Add handler in `GlobalExceptionHandler` returning 429.

3. **Create a Flyway migration** for the new columns (or rely on `ddl-auto: update` if that's the current mode for dev).

**Verification:**
- Unverified user can hit `/api/v1/onboarding/basic-info` but gets 403 on `/api/v1/matching/potential`
- After 5 failed logins for the same account, the 6th returns 429 even from a different IP
- After 15 minutes, the account unlocks
- Successful login resets the counter

---

## Phase 4 — Infrastructure & Leak Prevention (P3)

### - [x] Batch F — Rate Limit Proxy Trust + Authenticated Endpoint Rate Limits
<!-- Completed: 2026-03-16 -->

**Priority:** MEDIUM
**Risk:** X-Forwarded-For spoofable bypasses all rate limits; no rate limits on authenticated endpoints
**Depends on:** Batch E (account lockout provides defense-in-depth before this)
**Affected files:**
- `security/RateLimitFilter.java`
- `application.yml`

**What to do:**

1. **Configure trusted proxy for X-Forwarded-For.**
   - Add `server.forward-headers-strategy: framework` to `application.yml`. This tells Spring to use its built-in `ForwardedHeaderFilter` which respects only headers from configured trusted proxies.
   - In `RateLimitFilter.resolveClientIp()`: if no reverse proxy is deployed (dev mode), fall back to `request.getRemoteAddr()` only. If behind a proxy, rely on the Spring-processed remote address rather than parsing `X-Forwarded-For` manually.
   - **Alternative for production:** if deploying behind nginx/ALB, configure the proxy to overwrite (not append) `X-Forwarded-For`, and document this requirement.

2. **Add rate limits to authenticated swipe endpoint.**
   - POST `/api/v1/matching/swipe` → 60 requests / minute per user (keyed by `userId` from JWT, not IP).
   - This requires extracting the userId from the JWT in the filter. Since `RateLimitFilter` runs before Spring Security, you have two options:
     - Option A: move swipe rate limiting to a Spring MVC interceptor (runs after auth).
     - Option B: add a second filter registered after the security filter chain.
   - Option A is simpler.

3. **Add rate limits to score calculation.**
   - GET `/api/v1/matching/score/{id}` → 30 requests / minute per user.

**Verification:**
- With `X-Forwarded-For: 1.2.3.4` and no trusted proxy config, the server uses the actual remote address (not 1.2.3.4)
- Authenticated user hitting swipe > 60 times/min gets 429
- Rate limit test with curl confirms `Retry-After` header present

---

### - [x] Batch G — Information Disclosure Hardening
<!-- Completed: 2026-03-16 -->

**Priority:** LOW / MEDIUM
**Risk:** Auth error messages leak account existence and provider type; hardcoded DB credentials in config
**Independent — can be done at any point
**Affected files:**
- `services/impl/AuthServiceImpl.java`
- `controllers/AuthController.java`
- `application.yml`

**What to do:**

1. **Unify login error messages to prevent user enumeration.**
   - `AuthServiceImpl:96-98`: change `"Please login with Spotify"` to the same generic `"Invalid email or password"`. Currently this confirms the email exists AND is a Spotify account.
   - `AuthServiceImpl:240`: change `"Email already verified"` to a neutral message or just return 200 OK silently (same as forgotPassword pattern).

2. **Make registration not reveal existing accounts (optional, stricter).**
   - Currently `AuthController:54-57` returns 409 for existing emails. This confirms account existence.
   - Stricter approach: always return 201 with a generic "Check your email" message. If the email already exists, send a "Someone tried to register with your email" notification instead.
   - **Decision (2026-03-16): Kept Option A (409 preserved).** Rationale: the "silent 201 + notification email" approach introduces a harassment vector — any actor can spam arbitrary email addresses with notification emails, a real concern for a dating app where users may have stalkers. The login enumeration oracle (the higher-value target) is already hardened. This decision is intentional and documented.

3. **Move DB credentials to environment variables.**
   - In `application.yml` lines 5-6, replace:
     ```yaml
     username: root
     password: secret
     ```
     with:
     ```yaml
     username: ${DB_USERNAME:root}
     password: ${DB_PASSWORD:secret}
     ```
   - The defaults (`root`/`secret`) remain for local dev, but production deployments MUST set the env vars.

4. **Add `DeleteAccount` endpoint (GDPR).**
   - `DeleteAccountRequestDto` exists but no controller endpoint uses it.
   - Wire up a `DELETE /api/v1/auth/account` (or `POST /api/v1/auth/delete-account`) endpoint that:
     - Requires current password confirmation
     - Soft-deletes or hard-deletes the user (based on data retention policy)
     - Invalidates all tokens (bump `tokenVersion` or delete the user)
   - This is a compliance concern, not strictly a security vulnerability. Implement based on product requirements.

**Verification:**
- Login with a Spotify-registered email via email/password returns "Invalid email or password" (not "Please login with Spotify")
- `application.yml` no longer has literal `secret` as password (or has env-var fallback)
- If delete-account implemented: POST with correct password returns 200 and user can no longer authenticate

---

## Progress Tracker

| Batch | Name | Priority | Status | Completed |
|-------|------|----------|--------|-----------|
| A | Secret Logging Removal + JWT Claim Reduction | P0 | - [x] | 2026-03-16 |
| B | Input Validation Hardening | P0 | - [x] | 2026-03-16 |
| C | Token Version Legacy Cutoff + HMAC Key Validation | P1 | - [x] | 2026-03-16 |
| D | IDOR Protection + Pagination Bounds | P1 | - [x] | 2026-03-16 |
| E | Email Verification Enforcement + Account Lockout | P2 | - [x] | 2026-03-16 |
| F | Rate Limit Proxy Trust + Authenticated Endpoint Rate Limits | P3 | - [x] | 2026-03-16 |
| G | Information Disclosure Hardening | P3 | - [x] | 2026-03-16 |
