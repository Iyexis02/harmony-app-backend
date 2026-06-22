# Backend Stability Fix Roadmap — Error Handling Audit

## Purpose

This document tracks the error handling refactor for the dating app Spring Boot backend. It addresses issues identified during a full audit covering global exception handling, error response consistency, unhandled exceptions, information leaking, retry logic for external services, resilience patterns, and logging practices.

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
A (independent — foundation: RuntimeException → proper exceptions)
B (independent — foundation: GlobalExceptionHandler gaps)
A ─► C (controller consistency depends on proper exceptions existing)
B ─► C (controller consistency depends on handler covering all exception types)
C ─► D (external service resilience depends on consistent error surfaces)
E (independent — Spotify sync data-loss fix, no deps)
F (independent — logging hygiene, no deps)
```

## Recommended Implementation Order

```
Phase 1 — Exception Taxonomy (P0)
  1. Batch A — Replace RuntimeException with proper domain exceptions    [independent]
  2. Batch B — GlobalExceptionHandler gap coverage                       [independent]

Phase 2 — Controller Consistency (P1)
  3. Batch C — Unify controller error handling pattern                   [depends on A, B]

Phase 3 — External Service Resilience (P2)
  4. Batch D — Retry logic and resilience for Spotify + Email            [depends on C]

Phase 4 — Data Safety & Hygiene (P3)
  5. Batch E — SpotifyGenreSyncService destructive-then-fail fix         [independent]
  6. Batch F — Logging hygiene and @Async exception handling             [independent]
```

---

## Phase 1 — Exception Taxonomy (P0)

### - [x] Batch A — Replace RuntimeException with Proper Domain Exceptions
<!-- Completed: 2026-03-16 -->

**Priority:** CRITICAL
**Risk:** Every `RuntimeException("User not found")` bypasses `GlobalExceptionHandler`'s `UserNotFoundException` handler and returns HTTP 500 instead of 404. Affects 6+ call sites on every authenticated request where the JWT references a deleted or non-existent user.
**Affected files:**
- `controllers/MatchingController.java`
- `controllers/GenrePreferenceController.java`
- `services/matching/MatchRecommendationService.java`
- `services/matching/SwipeService.java`
- `services/matching/MatchService.java`
- `services/matching/BehavioralProfileService.java`
- `services/impl/UserServiceImpl.java`

**What to do:**

1. **`MatchingController.getCurrentUser()` (line 181)** — change:
   ```java
   .orElseThrow(() -> new RuntimeException("User not found"));
   ```
   to:
   ```java
   .orElseThrow(() -> new UserNotFoundException("User not found"));
   ```
   This fires on every authenticated matching endpoint when the JWT's userId no longer exists in the database.

2. **`GenrePreferenceController.getCurrentUser()` (line 190)** — same fix as above. Change `RuntimeException` to `UserNotFoundException`.

3. **`MatchRecommendationService.findPotentialMatches()` (line 81)** — change `RuntimeException("User not found: " + user.getId())` to `UserNotFoundException("User not found")`. Remove the userId from the message to avoid leaking it in error responses.

4. **`MatchRecommendationService.getMatchScore()` (lines 242–243)** — two `RuntimeException` calls. Change both to `UserNotFoundException("User not found")`. Remove the userId suffix from both messages.

5. **`SwipeService.recordSwipe()` (lines 66–69)** — two `RuntimeException` calls for swiped user and swiper user. Change both to `UserNotFoundException("User not found")`. Remove the userId suffix from both messages.

6. **`MatchService.createMatch()` (line 94)** — `RuntimeException("Failed to create or retrieve match between users ...")`. This represents a database inconsistency (INSERT succeeded or conflicted, but the subsequent SELECT returned empty). Change to `IllegalStateException("Match creation failed — database inconsistency")` so it falls through to the generic handler as 500, which is correct for this case, but with a safe message.

7. **`MatchService.markConversationStarted()` (line 183)** — `RuntimeException("Match not found: " + matchId)`. Change to `MatchNotFoundException("Match not found")`. Remove the matchId from the message.

8. **`BehavioralProfileService.createEmptyProfile()` (line 252)** — `RuntimeException("User not found: " + userId)`. Change to `UserNotFoundException("User not found")`. Remove the userId suffix.

9. **`UserServiceImpl.refreshAndUpdateUserToken()` (line 195)** — `RuntimeException("Failed to refresh Spotify token", e)`. Leave as `RuntimeException` for now (will be addressed in Batch D with proper Spotify error handling), but remove the original exception from the constructor to prevent it from leaking Spotify API details through the generic handler's log.

**Verification:**
- Confirm that calling any matching/genre endpoint with a JWT for a deleted user returns 404 `{"error": "User not found"}` instead of 500 `{"error": "An unexpected error occurred"}`.
- Confirm that `MatchService.markConversationStarted()` with a non-existent matchId returns 404.

---

### - [x] Batch B — GlobalExceptionHandler Gap Coverage
<!-- Completed: 2026-03-16 -->

**Priority:** HIGH
**Risk:** Missing handlers cause exceptions to fall through to the generic catch-all, returning 500 with a vague message when a more specific status code is appropriate.
**Affected files:**
- `exceptions/GlobalExceptionHandler.java`

**What to do:**

1. **Add `IllegalStateException` handler.** `UserServiceImpl.getValidSpotifyToken()` throws `IllegalStateException("User has not connected Spotify")` — this currently returns 500. Add:
   ```java
   @ExceptionHandler(IllegalStateException.class)
   public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
       return ResponseEntity.status(HttpStatus.BAD_REQUEST)
               .body(Map.of("error", ex.getMessage()));
   }
   ```

2. **Fix `MethodArgumentNotValidException` handler to handle non-field errors (line 23).** The current code casts all errors to `FieldError`, which will throw `ClassCastException` if a class-level `@Valid` constraint produces a global (non-field) error. Change:
   ```java
   ex.getBindingResult().getAllErrors().forEach((error) -> {
       String fieldName = ((FieldError) error).getField();
       String errorMessage = error.getDefaultMessage();
       fields.put(fieldName, errorMessage);
   });
   ```
   to:
   ```java
   ex.getBindingResult().getAllErrors().forEach((error) -> {
       String fieldName = (error instanceof FieldError fe) ? fe.getField() : error.getObjectName();
       String errorMessage = error.getDefaultMessage();
       fields.put(fieldName, errorMessage);
   });
   ```

3. **Sanitize `IllegalArgumentException` handler message (line 33).** Currently returns `ex.getMessage()` directly. Some `IllegalArgumentException` throws include internal details (e.g., from framework code or `Long.parseLong`). Add a safe message allowlist approach or simply log the original and return a generic message:
   ```java
   @ExceptionHandler(IllegalArgumentException.class)
   public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
       log.warn("Illegal argument: {}", ex.getMessage());
       return ResponseEntity.status(HttpStatus.BAD_REQUEST)
               .body(Map.of("error", ex.getMessage()));
   }
   ```
   This is acceptable because all current `IllegalArgumentException` throws in the codebase use user-safe messages. Add a comment noting this assumption.

4. **Add `HttpMessageNotReadableException` handler** for malformed JSON request bodies. Currently falls through to the generic handler and returns 500. Add:
   ```java
   @ExceptionHandler(HttpMessageNotReadableException.class)
   public ResponseEntity<Map<String, String>> handleUnreadableMessage(HttpMessageNotReadableException ex) {
       log.warn("Malformed request body: {}", ex.getMessage());
       return ResponseEntity.status(HttpStatus.BAD_REQUEST)
               .body(Map.of("error", "Malformed request body"));
   }
   ```
   Import `org.springframework.http.converter.HttpMessageNotReadableException`.

5. **Add `MissingServletRequestParameterException` handler** for missing required query parameters. Add:
   ```java
   @ExceptionHandler(MissingServletRequestParameterException.class)
   public ResponseEntity<Map<String, String>> handleMissingParam(MissingServletRequestParameterException ex) {
       return ResponseEntity.status(HttpStatus.BAD_REQUEST)
               .body(Map.of("error", "Missing required parameter: " + ex.getParameterName()));
   }
   ```
   Import `org.springframework.web.bind.MissingServletRequestParameterException`.

**Verification:**
- Send a request with no Spotify connected to any endpoint calling `getValidSpotifyToken()` — should return 400 instead of 500.
- Send a malformed JSON body to any `@RequestBody` endpoint — should return 400 `{"error": "Malformed request body"}` instead of 500.
- Send a request missing a required `@RequestParam` — should return 400 with the parameter name.
- Trigger a class-level validation error — should not throw `ClassCastException`.

---

## Phase 2 — Controller Consistency (P1)

### - [x] Batch C — Unify Controller Error Handling Pattern
<!-- Completed: 2026-03-16 -->

**Priority:** HIGH
**Risk:** `AuthController` and `GenrePreferenceController` catch exceptions locally, duplicating and sometimes contradicting `GlobalExceptionHandler`. `AuthController.login()` swallows `AccountLockedException` inside a generic `catch (Exception e)`, so locked users see 500 "Login failed" instead of 429 "Account temporarily locked". Multiple endpoints return empty response bodies on error, preventing the frontend from showing meaningful messages.
**Affected files:**
- `controllers/AuthController.java`
- `controllers/GenrePreferenceController.java`
- `controllers/MatchingController.java`
- `controllers/UserController.java`

**What to do:**

1. **`AuthController` — remove all local try/catch blocks.** Let exceptions propagate to `GlobalExceptionHandler`. The handler already covers `EmailAlreadyExistsException` (409), `InvalidCredentialsException` (401), `EmailNotVerifiedException` (403), `InvalidTokenException` (400), `UserNotFoundException` (404), `SpotifyAlreadyConnectedException` (409), `AccountLockedException` (429), `IllegalArgumentException` (400), and the generic `Exception` catch-all (500).

   Specifically for each method:
   - `handleSpotifyLogin()` — remove the try/catch. `IllegalArgumentException` and `OptimisticLockingFailureException` are already handled by the global handler. `DataIntegrityViolationException` is not — add a handler for it in `GlobalExceptionHandler` (see step 6).
   - `register()` — remove the try/catch. `EmailAlreadyExistsException` is handled globally.
   - `login()` — **critical fix**: remove the try/catch. This is the one that swallows `AccountLockedException`. The global handler will correctly return 429.
   - `verifyEmail()` — remove the try/catch. `InvalidTokenException` is handled globally.
   - `resendVerification()` — remove the try/catch. Keep the `UserNotFoundException` catch **only** to return the misleading-for-security 200 response (don't reveal if user exists). Alternatively, move this logic to the service layer.
   - `forgotPassword()` — remove the try/catch. The service already handles the "don't reveal email" logic silently.
   - `resetPassword()` — remove the try/catch. `InvalidTokenException` is handled globally.
   - `connectSpotify()` — remove the try/catch. All exceptions are handled globally.

   **Special case for `resendVerification()`:** The controller catches `UserNotFoundException` to return a misleading 200 for security. Two options:
   - **(Preferred)** Move this logic to `AuthServiceImpl.resendVerificationEmail()` — catch `UserNotFoundException` there and return silently, so the controller never sees it.
   - **(Alternative)** Keep a single `catch (UserNotFoundException e)` in the controller for this one endpoint only and add a comment explaining why.

2. **`GenrePreferenceController` — remove all local try/catch blocks.** Let exceptions propagate to `GlobalExceptionHandler`. After Batch A, `getCurrentUser()` throws `UserNotFoundException` (handled globally as 404). `IllegalArgumentException` from the service layer is handled globally as 400. The generic `Exception` catch-all handles everything else as 500.

   One exception: `syncFromSpotify()` line 93 currently leaks `e.getMessage()` in the response body (`"Failed to sync preferences: " + e.getMessage()`). Removing the try/catch fixes this leak automatically since the global handler uses a sanitized generic message.

3. **`MatchingController` — add error bodies to empty responses.** Change the three bare status-code returns to include error messages:
   - Line 60 (self-score): `return ResponseEntity.badRequest().body(null)` → return a 400 with `{"error": "Cannot calculate score with yourself"}`. Since the return type is `ResponseEntity<MatchScore>`, change it to return `ResponseEntity<Object>` or use `ResponseEntity.status(HttpStatus.BAD_REQUEST).build()` and throw `IllegalArgumentException("Cannot calculate score with yourself")` instead (preferred — the handler returns the right response).
   - Line 68 (blocked): throw `new UnauthorizedMatchAccessException("Access denied")` instead of returning an empty 403. The handler returns the right response.
   - Line 121 (invalid status): throw `new IllegalArgumentException("Invalid status parameter. Allowed values: active, all")` instead of returning an empty 400.

4. **`UserController` — replace null returns with exceptions.** `resolveUser()` returns `null` and each caller checks for null and returns an empty 404. Change `resolveUser()` to throw `UserNotFoundException("User not found")` on missing user. Remove all the `if (user == null) return ...` checks from callers. The global handler returns 404 with `{"error": "User not found"}`.

5. **`UserController.getTopArtists()` and `getTopTracks()` — remove `throws Exception` from method signatures.** These leak exception type information. The methods don't throw checked exceptions. Remove the `throws Exception` clause.

6. **Add `DataIntegrityViolationException` handler to `GlobalExceptionHandler`** (needed after removing `AuthController`'s local catch of it):
   ```java
   @ExceptionHandler(DataIntegrityViolationException.class)
   public ResponseEntity<Map<String, String>> handleDataIntegrity(DataIntegrityViolationException ex) {
       log.error("Data integrity violation: {}", ex.getMessage());
       return ResponseEntity.status(HttpStatus.CONFLICT)
               .body(Map.of("error", "Data conflict — please try again"));
   }
   ```
   Import `org.springframework.dao.DataIntegrityViolationException`.

**Verification:**
- Hit `POST /api/v1/auth/login` with a locked account — should return 429 with `{"error": "Account temporarily locked. Try again later."}` instead of 500.
- Hit any matching endpoint with a deleted user's JWT — should return 404 with `{"error": "User not found"}` instead of 500.
- Hit `POST /api/v1/preferences/genres/sync` with a failing Spotify API — error response should NOT contain `e.getMessage()`.
- Hit `GET /api/v1/matching/score/{selfId}` — should return 400 with error message instead of empty body.
- Hit all `UserController` endpoints with a non-existent user JWT — should return 404 with `{"error": "User not found"}` instead of empty 404.
- All auth endpoints should still return the same HTTP status codes as before (verify against the existing test suite).

---

## Phase 3 — External Service Resilience (P2)

### - [x] Batch D — Retry Logic and Resilience for Spotify + Email
<!-- Completed: 2026-03-16 -->

**Priority:** HIGH
**Risk:** Transient Spotify API or Resend email failures cause permanent errors with no retry. Spotify token refresh failures bubble up as opaque `RuntimeException`. Password reset emails can silently fail (user sees "success" but never receives the email). Email service `@Async` methods throw `RuntimeException` which is silently swallowed.
**Affected files:**
- `services/impl/JwtServiceImpl.java`
- `services/impl/UserServiceImpl.java`
- `services/impl/EmailServiceImpl.java`
- `services/matching/SpotifyGenreSyncService.java`

**What to do:**

1. **`JwtServiceImpl.refreshToken()` — add retry with backoff.** Wrap the Spotify token refresh call in a retry loop (3 attempts, 500ms/1s/2s backoff). Differentiate between retryable errors (5xx, connection timeout) and non-retryable errors (401 token revoked, 403 forbidden). For non-retryable errors, throw immediately without retry.

   Consider creating a dedicated `SpotifyTokenExpiredException` or `SpotifyApiException` instead of wrapping everything in `RuntimeException`. This lets callers distinguish "token revoked, user must re-authenticate" from "Spotify is down, try again later".

   ```java
   // Suggested exception hierarchy:
   // SpotifyApiException extends RuntimeException (generic Spotify failure)
   //   └─ SpotifyTokenRevokedException extends SpotifyApiException (401 — user must reconnect)
   ```

   Add the new exception classes to `exceptions/`. Add handlers to `GlobalExceptionHandler`:
   - `SpotifyTokenRevokedException` → 401 `{"error": "Spotify connection expired. Please reconnect."}`
   - `SpotifyApiException` → 502 `{"error": "Spotify service temporarily unavailable"}`

2. **`UserServiceImpl.refreshAndUpdateUserToken()` (line 193)** — remove the generic `catch (Exception e)` wrapper now that `JwtServiceImpl.refreshToken()` throws typed exceptions. Let `SpotifyTokenRevokedException` and `SpotifyApiException` propagate to the controller → global handler.

3. **`EmailServiceImpl` — remove `throw new RuntimeException(...)` from `@Async` methods.** The throws in `sendVerificationEmail()` (line 53) and `sendPasswordResetEmail()` (line 83) are dead code — `@Async` methods have their exceptions swallowed by the default executor. Two changes:
   - Remove the `throw` statements. The `log.error` before them already captures the failure.
   - Add retry (2 attempts with 1s delay) before giving up. Email delivery is important enough to retry once.

4. **Configure `AsyncUncaughtExceptionHandler`.** Create a simple configuration class that logs unhandled async exceptions at ERROR level so they are never silently swallowed:
   ```java
   @Configuration
   public class AsyncConfig implements AsyncConfigurer {
       @Override
       public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
           return (ex, method, params) ->
               log.error("Unhandled async exception in {}: {}", method.getName(), ex.getMessage(), ex);
       }
   }
   ```

5. **`SpotifyGenreSyncService.fetchGenresWithWeight()` — add per-time-range error isolation.** Currently, if `medium_term` fails, the entire sync throws and data is lost (see Batch E for the destructive-then-fail fix). Wrap each `fetchGenresWithWeight()` call in its own try/catch so partial data is still saved:
   ```java
   try {
       allGenres.addAll(fetchGenresWithWeight(accessToken, 50, "short_term", 3));
   } catch (Exception e) {
       log.warn("Failed to fetch short_term genres: {}", e.getMessage());
   }
   // repeat for medium_term and long_term
   ```
   Only throw if ALL three time ranges fail.

**Verification:**
- Simulate a Spotify 5xx response during token refresh — should retry 3 times before failing.
- Simulate a Spotify 401 during token refresh — should fail immediately with a user-friendly message about reconnecting Spotify.
- Simulate a Resend API failure during email send — should retry once before giving up, and should NOT throw from the async method.
- Confirm `SpotifyGenreSyncService` saves partial data if one time range fails but others succeed.

---

## Phase 4 — Data Safety & Hygiene (P3)

### - [x] Batch E — SpotifyGenreSyncService Destructive-Then-Fail Fix
<!-- Completed: 2026-03-16 -->

**Priority:** HIGH
**Risk:** `syncUserGenrePreferences()` calls `clearSpotifyPreferences(user)` BEFORE fetching from Spotify. If the subsequent Spotify API calls fail, the user's genre preferences are permanently wiped with no way to recover. This is silent data loss.
**Affected files:**
- `services/matching/SpotifyGenreSyncService.java`

**What to do:**

1. **Move `clearSpotifyPreferences()` to AFTER successful fetch.** Restructure `syncUserGenrePreferences()`:
   ```java
   public int syncUserGenrePreferences(User user, String accessToken) throws JsonProcessingException {
       log.info("Starting genre sync for user {}", user.getId());

       // Fetch genres FIRST — before deleting anything
       List<String> allGenres = new ArrayList<>();
       // ... fetch from all time ranges (with per-range isolation from Batch D) ...

       if (allGenres.isEmpty()) {
           log.warn("No genres found for user {}", user.getId());
           return 0;
       }

       // Only clear old preferences AFTER we have new data to replace them
       genreExtractionService.clearSpotifyPreferences(user);

       // Save new preferences
       genreExtractionService.extractAndSaveGenrePreferences(user, allGenres, GenrePreferenceSource.SPOTIFY_DERIVED);

       return (int) allGenres.stream().distinct().count();
   }
   ```

2. **Wrap the clear + save in `@Transactional`** so that if `extractAndSaveGenrePreferences()` fails after clearing, the entire operation rolls back and old preferences are preserved. Add `@Transactional` to `syncUserGenrePreferences()` (and `quickSyncUserGenrePreferences()` for consistency).

3. **`GenrePreferenceController.syncFromSpotify()` (line 79) — fix encrypted token bug.** The controller passes `user.getSpotifyAccessToken()` directly, which is the **encrypted** token stored in the database. It should use `UserService.getValidSpotifyToken(user)` to get the decrypted (and refreshed if needed) token. This bug causes ALL genre syncs to fail with cryptographic/Spotify auth errors. Fix:
   ```java
   String accessToken = userService.getValidSpotifyToken(user);
   // ...
   genreCount = spotifyGenreSyncService.syncUserGenrePreferences(user, accessToken);
   ```
   This requires injecting `UserService` into `GenrePreferenceController`. The controller's `getCurrentUser()` method returns a `User` domain object — `getValidSpotifyToken()` also takes a `User`, so the types align.

**Verification:**
- Simulate a Spotify API failure during genre sync — verify that existing genre preferences are NOT deleted.
- Run a successful genre sync — verify old Spotify-derived preferences are replaced with new ones.
- Verify the sync endpoint actually contacts Spotify (the encrypted-token bug should now be fixed).

---

### - [x] Batch F — Logging Hygiene and @Async Exception Handling
<!-- Completed: 2026-03-16 -->

**Priority:** MEDIUM
**Risk:** Noisy WARN-level logging for expected events; silent data corruption in serialization failures; misleading dead-code throws in async methods.
**Affected files:**
- `controllers/AuthController.java`
- `services/matching/BehavioralProfileService.java`
- `services/impl/EncryptionServiceImpl.java`

**What to do:**

1. **`AuthController` — downgrade login failure log from WARN to DEBUG (line 72).** Failed logins are expected events, especially under brute-force. At scale, WARN-level logging for every failed login creates monitoring noise. Change:
   ```java
   log.warn("Login failed for email: {}", request.getEmail());
   ```
   to:
   ```java
   log.debug("Login failed for email: {}", request.getEmail());
   ```
   Note: if `AuthController` try/catch blocks are removed in Batch C, this log line will also be removed. If Batch C is completed first, skip this step.

2. **`BehavioralProfileService.serializeToJson()` (line 289) — escalate serialization failure to ERROR and do NOT silently replace data with `"{}"`.**  Returning `"{}"` silently erases all learned genre weights. Change to:
   ```java
   private String serializeToJson(Object value) {
       try {
           return objectMapper.writeValueAsString(value);
       } catch (Exception e) {
           log.error("Failed to serialize behavioral data to JSON — preserving existing value: {}", e.getMessage());
           throw new RuntimeException("Behavioral profile serialization failed", e);
       }
   }
   ```
   The caller (`doUpdateAfterSwipe`) is wrapped in the retry orchestrator which catches all exceptions and logs at WARN before dropping the update. This is safer than silently corrupting the profile.

3. **`BehavioralProfileService.deserializeDoubleMap()` / `deserializeIntMap()` — same concern.** If deserialization fails, returning an empty map means subsequent writes will overwrite the stored data with empty content. Change behavior to throw so the retry orchestrator handles it:
   ```java
   private Map<String, Double> deserializeDoubleMap(String json) {
       if (json == null || json.isBlank()) return new HashMap<>();
       try {
           return objectMapper.readValue(json, DOUBLE_MAP_TYPE);
       } catch (Exception e) {
           log.error("Failed to deserialize behavioral genre weights: {}", e.getMessage());
           throw new RuntimeException("Behavioral profile deserialization failed", e);
       }
   }
   ```
   Apply the same pattern to `deserializeIntMap()`.

4. **`EncryptionServiceImpl.encrypt()` (line 70) — fix misleading error message.** The first catch block logs "Error during encrypting data" but throws `new RuntimeException("Decryption failed", e)`. Fix the message to `"Encryption failed"`.

**Verification:**
- Trigger a behavioral profile update with corrupted JSON in `learnedGenreWeights` — should log at ERROR and the update should be dropped (not silently overwrite with `{}`).
- Verify encryption error message says "Encryption failed", not "Decryption failed".
- If Batch C is not yet done, verify login failure logs appear at DEBUG level, not WARN.

---

## Quick Reference — Files Modified Per Batch

| Batch | Files |
|-------|-------|
| A | `MatchingController`, `GenrePreferenceController`, `MatchRecommendationService`, `SwipeService`, `MatchService`, `BehavioralProfileService`, `UserServiceImpl` |
| B | `GlobalExceptionHandler` |
| C | `AuthController`, `GenrePreferenceController`, `MatchingController`, `UserController`, `GlobalExceptionHandler` |
| D | `JwtServiceImpl`, `UserServiceImpl`, `EmailServiceImpl`, `SpotifyGenreSyncService`, new `AsyncConfig`, new `SpotifyApiException` + `SpotifyTokenRevokedException` |
| E | `SpotifyGenreSyncService`, `GenrePreferenceController` |
| F | `AuthController` (if Batch C not done), `BehavioralProfileService`, `EncryptionServiceImpl` |
