-- Batch F: Behavioral Profile Optimistic Locking
-- Adds a version column to user_behavioral_profile for JPA @Version optimistic locking.
-- The DEFAULT 0 seeds every existing row so the first update succeeds without special handling.

ALTER TABLE user_behavioral_profile
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
