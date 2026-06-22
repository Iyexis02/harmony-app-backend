-- V8: Backfill @Version columns left NULL by prior ddl-auto: update runs.
--
-- Background: V4 (user_behavioral_profile) and V5 (matches) used
-- "ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0".
-- The DEFAULT only seeds rows when the column is actually created. If a
-- previous ddl-auto: update had already added the column with no default,
-- the migration was a no-op and old rows still hold NULL. The users table
-- never had a migration for its version column at all — it was added
-- silently by ddl-auto: update with no default, so every pre-existing user
-- row has version = NULL.
--
-- Symptom: Hibernate's auto-flush throws
-- "Cannot invoke java.lang.Long.longValue() because current is null"
-- in Versioning.increment when any dirty entity with NULL version is flushed.
-- First seen on Spotify login (UserServiceImpl.findOrCreateUser modifies
-- the user, the next SELECT triggers auto-flush, NPE).
--
-- Fix per table, in this order:
--   1. ADD COLUMN IF NOT EXISTS  — safety net for users table.
--   2. UPDATE … SET version = 0 WHERE version IS NULL  — backfill before constraint.
--   3. ALTER … SET DEFAULT 0     — so future inserts that omit version succeed.
--   4. ALTER … SET NOT NULL      — enforce the invariant going forward.

-- ============================================================
-- users
-- ============================================================
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS version BIGINT;

UPDATE users SET version = 0 WHERE version IS NULL;

ALTER TABLE users ALTER COLUMN version SET DEFAULT 0;
ALTER TABLE users ALTER COLUMN version SET NOT NULL;

-- ============================================================
-- matches
-- ============================================================
ALTER TABLE matches
    ADD COLUMN IF NOT EXISTS version BIGINT;

UPDATE matches SET version = 0 WHERE version IS NULL;

ALTER TABLE matches ALTER COLUMN version SET DEFAULT 0;
ALTER TABLE matches ALTER COLUMN version SET NOT NULL;

-- ============================================================
-- user_behavioral_profile
-- ============================================================
ALTER TABLE user_behavioral_profile
    ADD COLUMN IF NOT EXISTS version BIGINT;

UPDATE user_behavioral_profile SET version = 0 WHERE version IS NULL;

ALTER TABLE user_behavioral_profile ALTER COLUMN version SET DEFAULT 0;
ALTER TABLE user_behavioral_profile ALTER COLUMN version SET NOT NULL;
