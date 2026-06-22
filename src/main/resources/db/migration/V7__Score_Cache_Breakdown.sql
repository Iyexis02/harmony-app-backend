-- Batch F: Preserve match breakdown and insights in the score cache.
-- Adds two nullable TEXT columns to user_match_scores.
-- Existing rows retain NULL in both columns; the service handles this gracefully
-- by falling back to null breakdown/insights on cache hit for legacy rows.

ALTER TABLE user_match_scores
    ADD COLUMN IF NOT EXISTS breakdown_json TEXT,
    ADD COLUMN IF NOT EXISTS insights_json  TEXT;
