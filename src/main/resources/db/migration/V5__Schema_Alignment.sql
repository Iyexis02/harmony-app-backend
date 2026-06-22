-- V5: Schema Alignment
-- Brings the Flyway-managed schema in sync with entity definitions.
-- All changes here were previously applied silently by ddl-auto: update in dev
-- but were never captured in a migration file.
-- After this migration, ddl-auto: validate can be trusted in all profiles.

-- ============================================================
-- matches table
-- ============================================================

-- Widen unmatched_by from VARCHAR(10) to VARCHAR(36).
-- V1 defined VARCHAR(10); Match entity declares length=36 to store a UUID.
-- No data loss: the column is only set on unmatch, never truncated in practice.
ALTER TABLE matches
    ALTER COLUMN unmatched_by TYPE VARCHAR(36);

-- Add match_score_b: B→A directional score (Integrity Batch H).
-- Null for matches created before that batch — backward-compatible.
ALTER TABLE matches
    ADD COLUMN IF NOT EXISTS match_score_b DOUBLE PRECISION;

-- Add version: optimistic locking column (Concurrency Batch A).
-- DEFAULT 0 seeds every existing row so the first update succeeds immediately.
ALTER TABLE matches
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- Composite indexes added during Scalability Batch B/C.
-- V1 only created four single-column indexes on this table.
CREATE INDEX IF NOT EXISTS idx_status_matched_at
    ON matches(status, matched_at);

CREATE INDEX IF NOT EXISTS idx_match_a_status
    ON matches(user_a_id, status);

CREATE INDEX IF NOT EXISTS idx_match_b_status
    ON matches(user_b_id, status);

CREATE INDEX IF NOT EXISTS idx_match_a_status_conv
    ON matches(user_a_id, status, conversation_started);

CREATE INDEX IF NOT EXISTS idx_match_b_status_conv
    ON matches(user_b_id, status, conversation_started);

-- ============================================================
-- user_match_scores table
-- ============================================================

-- Add interests_score and behavioral_score (algorithm v2.0 — 5-dimensional scoring).
-- V1 only created 4 dimension columns; the entity declares 6.
ALTER TABLE user_match_scores
    ADD COLUMN IF NOT EXISTS interests_score  DOUBLE PRECISION;

ALTER TABLE user_match_scores
    ADD COLUMN IF NOT EXISTS behavioral_score DOUBLE PRECISION;

-- Composite index for cache batch-fetch before every feed load (Scalability Batch C).
CREATE INDEX IF NOT EXISTS idx_user_version
    ON user_match_scores(user_id, algorithm_version);

-- ============================================================
-- user_swipes table
-- ============================================================

-- Composite index for blocked-user exclusion queries (Scalability Batch B).
-- V1 only created four single-column indexes on this table.
CREATE INDEX IF NOT EXISTS idx_swiped_action
    ON user_swipes(swiped_user_id, action);
