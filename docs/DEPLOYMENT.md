# Deployment Plan — Harmony Dating App (Demo: Vercel + Railway)

## Context

The full stack now lives in version control (`Iyexis02/harmony-app-backend` + the Next.js
frontend repo). The goal is to deploy a **public demo** that can comfortably serve **a few
hundred users**, on **free platform subdomains** for now, with a clean path to a custom domain
and larger scale later. Decision (confirmed with the user):

- **Frontend** → **Vercel** (native Next.js 16; free Hobby tier; global CDN).
- **Backend + Postgres + Redis** → **Railway** (one project, private networking, usage billing).
- The split is cheap because backend auth is **stateless Bearer JWT**, not shared cookies — the
  only cross-origin work is adding the Vercel origin to backend CORS.

### Stack (confirmed by inspection)
- **Backend**: Spring Boot 3.5.7 fat-jar, Java 17, embedded Tomcat (200 threads), Postgres +
  HikariCP (30), Flyway, optional Redis (fails open → Caffeine), Resilience4j, Actuator with
  liveness/readiness probes, `forward-headers-strategy: framework` (proxy-aware), Swagger off in
  prod. External SaaS: Spotify, Resend (email), Google Maps geocoding.
- **Frontend**: Next.js 16 + React 19 + NextAuth 4 + Tailwind 4. **Not static** — has
  `middleware.ts` (auth gating) and server API routes (`/api/cloudinary/delete`,
  `/api/places/autocomplete`) using server-only secrets → needs a Node/Edge runtime (Vercel).
  **Images → Cloudinary** (no object storage needed in our infra). Talks to backend both
  server-side (`BACKEND_API_URL`) and client-side (`NEXT_PUBLIC_API_URL`).

---

## ⚠️ Critical pre-deploy blockers (must be handled — in priority order)

1. **DB schema is not reproducible from migrations.** Flyway migrations create only the matching
   tables; `users` + all profile sub-tables (`user_photos`, `user_lifestyle`, `user_personality`,
   `user_music_preferences`, `user_dating_preferences`, `user_privacy_settings`,
   `user_behavioral_profile`) were created by Hibernate `ddl-auto` historically and baselined at
   v8. A fresh DB won't have them → startup fails under `ddl-auto: validate`. **Fix:** bootstrap
   the Railway DB from a `pg_dump` of the working dev schema, then let Flyway baseline at 8
   (Part B). ✅ `schema.sql` generated (13 tables, `flyway_schema_history` excluded).
2. **Datasource URL is hardcoded** to `127.0.0.1:5433` in `application.yml`. ✅ **Fixed** —
   parameterized to `${DB_URL:...}` (commit `61f0ce6`).
3. **CORS allows localhost only.** The browser calls the backend directly
   (`NEXT_PUBLIC_API_URL`), so the Vercel origin must be allowed. ✅ **Fixed** — env-driven via
   `CORS_ALLOWED_ORIGINS` (commit `61f0ce6`).
4. **Spotify app is in Development Mode → hard cap of 25 users.** A several-hundred-user demo
   requires a **Quota Extension Request** in the Spotify Developer Dashboard (review takes days).
   **Action:** submit early (Part D).
5. **Email can't reach arbitrary users without a verified domain** (Resend's `onboarding@resend.dev`
   only delivers to the account owner). **RESOLVED → Spotify-first demo:** Spotify-login users are
   created `emailVerified=true` (`UserServiceImpl.findOrCreateUser:71`), so they bypass the
   `EmailVerificationFilter` with **no code change**. Email/password signup still exists but its
   verification mail won't deliver — don't rely on it for the demo (optionally hide it in the FE).

---

## Target Architecture

```
                 Browser
                   │
        ┌──────────┴───────────┐
   (NEXT_PUBLIC_API_URL)   (page loads)
        │                      │
        ▼                      ▼
  Railway: Spring Boot ◄── Vercel: Next.js 16
   :8080 (public HTTPS)    (SSR + middleware + /api/* BFF routes)
        │  ▲                   │
   private│  │(SSR: BACKEND_API_URL)
   network│  └───────────────-─┘
        ▼
  Railway: Postgres 16   (Railway: Redis — OMITTED for demo)
        ▲
  Cloudinary (images) · Spotify · Resend · Nominatim/Google (geocode)
```

- One backend instance is plenty for hundreds of users (Tomcat 200 / Hikari 30).
- **Redis is omitted for the demo**: with a single backend instance the `CaffeineRateLimiter`
  and in-memory `DistributedLockService` fallbacks are fully correct. Add Railway Redis only when
  running >1 backend instance (then also add `spring.data.redis.password`/TLS support — not
  currently configured).

---

## Part A — Backend on Railway  ✅ code changes committed (`61f0ce6`, `8ec4414`)

### A1. `Dockerfile` (repo root) — multi-stage, no Maven wrapper dependency
The Maven wrapper jar is gitignored (`.mvn/wrapper/maven-wrapper.jar`), so the image uses a
Maven base image, not `./mvnw`. Tests need a live DB (known to fail standalone), so the build uses
`-DskipTests`. A `.dockerignore` trims `target/`, `.git`, logs, docs.

### A2. Config changes in `src/main/resources/application.yml` (done)
- **Datasource:** `url: ${DB_URL:jdbc:postgresql://127.0.0.1:5433/dating}` (keeps local default,
  prod-overridable). `username`/`password` already env-driven (`DB_USERNAME`/`DB_PASSWORD`).
- **Port for Railway:** `server.port: ${PORT:8080}` (Railway injects `$PORT`).
- **Geocoding:** `provider: ${GEOCODING_PROVIDER:google}` — set `nominatim` on Railway to avoid
  GCP billing (keyless). `geocoding.google.api-key: ${GOOGLE_MAPS_API_KEY:}` now has an empty
  default so a nominatim-only deploy boots without any Google key.

### A3. CORS — env-driven (`src/main/java/com/example/dating/security/SecurityConfig.java`, done)
Allowed origins read from `${cors.allowed-origins:...}` (env `CORS_ALLOWED_ORIGINS`,
comma-separated), defaulting to the local dev set. On Railway set it to the Vercel URL.
`allowCredentials` stays true; since auth is Bearer, the Vercel origin just needs to be allow-listed.

### A4. Profile
Deploy with the **default profile** (do NOT set `SPRING_PROFILES_ACTIVE=dev`). This keeps Swagger
off and excludes the `@Profile("dev")` Phase1/2/3 test controllers and seed loaders.

### A5. Railway backend service
- New Railway project → "Deploy from GitHub repo" → select `harmony-app-backend`, branch `master`.
- Build: Dockerfile (auto-detected). Healthcheck path: `/actuator/health/readiness`.
- Generate a public domain (`*.up.railway.app`).
- Env vars (see Appendix A).

---

## Part B — Database on Railway (Postgres) + schema bootstrap

1. Add a **Postgres** service to the Railway project (PG 16). It exposes `PG*` vars over the
   private network.
2. **Bootstrap the schema (one-time)** — solves blocker #1:
   - From the working local dev DB (excludes the Flyway history table on purpose, so the v8
     baseline triggers on first boot instead of re-running V1–V8 against existing tables):
     `pg_dump --schema-only --no-owner --no-privileges -T flyway_schema_history -h 127.0.0.1 -p 5433 -U root -d dating -f schema.sql`
   - Load into Railway PG once (via Railway's public psql connect string):
     `psql "<railway-public-connection-url>" -f schema.sql`
   - This reproduces the exact schema the app validates against (the dev DB is the de-facto source
     of truth). Verified: 13 tables, no data, no `flyway_schema_history`.
3. Keep `spring.flyway.baseline-on-migrate: true` / `baseline-version: 8` (unchanged) so Flyway
   sees the populated schema + no history table, baselines at 8, and runs nothing further — App
   boots clean under `ddl-auto: validate`.
4. **Backups:** enable Railway's automated Postgres backups (and take a manual snapshot after the
   first successful boot).
5. **Follow-up (post-demo, recommended):** consolidate the schema into a real `V1__Baseline.sql`
   so future environments are reproducible from migrations alone, and all future schema changes go
   through new `V9+` migrations. (Out of scope for getting the demo live; noted as tech debt.)

---

## Part C — Frontend on Vercel

1. Import the frontend repo in Vercel → it auto-detects Next.js 16. **No Dockerfile, no
   `output: 'standalone'`** needed (that's only for self-hosting).
2. `next.config.ts` `images.remotePatterns` already allows Cloudinary, Spotify CDNs, and Google —
   **no change needed.**
3. Vercel env vars: see Appendix B.
4. Note: `NEXTAUTH_URL` and Vercel's auto-generated preview URLs differ — set `NEXTAUTH_URL` to the
   stable production URL; preview deployments may need their own value or will mis-redirect OAuth.

---

## Part D — External services configuration

### Spotify (blocker #4)
- Add redirect URIs in the Spotify dashboard for prod:
  `https://<app>.vercel.app/api/auth/callback/spotify` (NextAuth), plus any backend
  connect-spotify callback the app uses. Keep the localhost ones for dev.
- **Submit a Quota Extension Request** to leave Development Mode (25-user cap). Until approved,
  only explicitly-added Spotify accounts (max 25) can log in — fine for an initial closed demo,
  blocking for an open one.

### Email / Resend (blocker #5) — DECIDED: Spotify-first demo (option b)
- **No domain, no code change.** Spotify-login users are created `emailVerified=true`
  (`UserServiceImpl.findOrCreateUser:71`) → they bypass `EmailVerificationFilter` entirely.
- `RESEND_API_KEY` is still **required at startup** (the app wires the Resend client), so set it to
  a valid key; `MAIL_FROM` can stay the default `onboarding@resend.dev`. Mail simply won't be relied
  upon for the demo flow.
- **Frontend:** route the primary CTA to **Spotify login**; optionally hide/disable email-password
  registration so demo users don't hit the dead-end verification path.
- **Later (real launch):** verify a custom domain on Resend and set `MAIL_FROM=noreply@yourdomain`
  to enable email/password signup + password reset.

### Cloudinary
- Create an **unsigned upload preset** (its name → `NEXT_PUBLIC_CLOUDINARY_UPLOAD_PRESET`); keep
  the API secret server-side only (already the pattern).

### Geocoding
- Use **Nominatim** for the demo (`GEOCODING_PROVIDER=nominatim`, keyless, free) to avoid GCP
  billing. Respect Nominatim's usage policy; switch to Google later if volume requires it.

---

## Part E — CI/CD

- **Native auto-deploy** on both platforms: connect each GitHub repo; push to `master` →
  Railway rebuilds the Docker image and redeploys; Vercel rebuilds the Next.js app. No custom
  GitHub Actions required.
- Test gating in CI is **out of scope** (the suite needs a live DB / Testcontainers — a known
  limitation). Build uses `-DskipTests`.

---

## Deploy sequence (ordered checklist)

1. ✅ Backend code/config: `Dockerfile` + `.dockerignore`; parameterize datasource URL, add
   `server.port`, env-drive CORS, empty Google-key default. Committed + pushed.
2. Railway: create project → add Postgres → bootstrap schema from `schema.sql` (Part B) → enable
   backups.
3. Railway: add backend service from GitHub → set env vars (Appendix A) → deploy → confirm
   `/actuator/health` is `UP` and `/actuator/health/readiness` passes.
4. Spotify: add prod redirect URIs; submit quota extension.
5. Resend/Cloudinary/Google: configure per Part D.
6. Vercel: import frontend repo → set env vars (Appendix B; `NEXT_PUBLIC_API_URL` = Railway URL)
   → deploy.
7. Set backend `CORS_ALLOWED_ORIGINS` + `FRONTEND_URL` to the live Vercel URL; redeploy backend.
8. Update `NEXTAUTH_URL` to the live Vercel URL if it changed; redeploy frontend.

---

## Verification (end-to-end, against live URLs)

- `GET https://<backend>/actuator/health` → `{"status":"UP"}`; DB component up.
- Open `https://<app>.vercel.app` → **Spotify login** → middleware redirects to `/onboarding`.
- Complete onboarding (photo upload hits Cloudinary; location uses Places autocomplete) → reach
  `/discover`.
- `GET /api/v1/matching/potential` returns candidates; swipe → mutual like creates a match.
- Confirm CORS: browser network tab shows successful cross-origin calls to the Railway backend
  (no CORS errors).
- Check Railway logs for Flyway: should report baseline at v8 and **no failed migrations**; no
  `ddl-auto validate` errors at boot.

---

## Risks & follow-ups

- **Spotify 25-user cap** until quota extension approved — submit first.
- **Schema bootstrap is a manual `pg_dump`** — fragile; do the `V1__Baseline.sql` consolidation
  post-demo so environments are reproducible from code.
- **No Redis** → rate limiting/locks are per-instance; correct only while running a single backend
  instance. Add Redis (with auth/TLS config) before horizontal scaling.
- **Nominatim** has rate/usage limits — fine for demo traffic, revisit for scale.
- **Frontend Google Places** (`GOOGLE_MAPS_API_KEY`) needs GCP billing enabled; otherwise location
  autocomplete errors (verify onboarding degrades gracefully, or swap to a keyless provider).
- Vercel **Hobby** tier is non-commercial; move to Pro if the demo becomes commercial.

## Rough cost (verify current pricing)
- Vercel Hobby: **$0** · Railway (backend + Postgres, demo scale): **~$10–20/mo** ·
  optional email domain: **~$1–12/yr** · Spotify/Resend/Cloudinary/Nominatim: **free tiers**.
- **≈ $10–20/month.**

---

## Appendix — Environment Variable Sets

> Real secret values (JWT/ENCRYPTION/NEXTAUTH) are **not stored in this file** — generate them with
> `openssl` and keep them in the platform's secret store only.
> URL chicken-and-egg order: deploy backend → set Vercel vars with backend URL → deploy frontend →
> set backend `FRONTEND_URL`/`CORS_ALLOWED_ORIGINS` to the Vercel URL → register Spotify redirect.

### A. Railway — backend service variables
```
# Generated secrets — store in Railway only
JWT_SECRET_KEY=<openssl rand -base64 48>          # used as raw HMAC key, must be >=32 bytes
ENCRYPTION_SECRET_KEY=<openssl rand -base64 32>   # base64 that decodes to 32 bytes (AES-256)

# Database (Railway references; Postgres service named "Postgres")
DB_URL=jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}
DB_USERNAME=${{Postgres.PGUSER}}
DB_PASSWORD=${{Postgres.PGPASSWORD}}

# Spotify (same app as frontend)
SPOTIFY_CLIENT_ID=<spotify client id>
SPOTIFY_CLIENT_SECRET=<spotify client secret>

# Email (Resend free key; required at boot, unused in Spotify-first demo)
RESEND_API_KEY=<resend api key>

# Geocoding (keyless)
GEOCODING_PROVIDER=nominatim

# Frontend URL + CORS (set AFTER Vercel deploy; have safe defaults for first boot)
FRONTEND_URL=https://<your-app>.vercel.app
CORS_ALLOWED_ORIGINS=https://<your-app>.vercel.app
```
- **Do NOT set** `PORT` (Railway injects it), `SPRING_PROFILES_ACTIVE` (default profile is
  prod-safe; `dev` enables Swagger + test controllers), `GOOGLE_MAPS_API_KEY` (defaults to empty),
  or any `REDIS_*` (fail-open fallback).
- Healthcheck path: `/actuator/health/readiness`.
- DB SSL fallback: if first boot fails on SSL, append `?sslmode=require` to `DB_URL`.

### B. Vercel — frontend project variables
```
# NextAuth — store secret in Vercel only
NEXTAUTH_SECRET=<openssl rand -base64 32>
NEXTAUTH_URL=https://<your-app>.vercel.app        # stable prod domain, not preview URLs

# Backend API (both = Railway backend public URL; NEXT_PUBLIC_* is baked at BUILD time)
BACKEND_API_URL=https://<your-backend>.up.railway.app
NEXT_PUBLIC_API_URL=https://<your-backend>.up.railway.app

# Spotify (same app as backend)
SPOTIFY_CLIENT_ID=<spotify client id>
SPOTIFY_CLIENT_SECRET=<spotify client secret>
SPOTIFY_API_BASE_URL=https://api.spotify.com/v1

# Cloudinary (image uploads)
NEXT_PUBLIC_CLOUDINARY_CLOUD_NAME=<cloud name>
NEXT_PUBLIC_CLOUDINARY_UPLOAD_PRESET=<unsigned preset name>
CLOUDINARY_API_KEY=<cloudinary api key>
CLOUDINARY_API_SECRET=<cloudinary api secret>

# Google Places — location autocomplete (/api/places/autocomplete); needs GCP billing enabled
GOOGLE_MAPS_API_KEY=<google maps/places key>

# Admin routes (/admin/*)
ADMIN_EMAILS=<your admin email>
```
- **`GOOGLE_MAPS_API_KEY` is the one remaining Google dependency** (frontend Places autocomplete,
  requires GCP billing). Skip → autocomplete route errors (verify onboarding location degrades
  gracefully); or swap the frontend route to a keyless provider (separate frontend change).
- If the backend URL changes later, **redeploy the frontend** (NEXT_PUBLIC_* are build-time).
- Spotify redirect URI to register: `https://<your-app>.vercel.app/api/auth/callback/spotify`.
