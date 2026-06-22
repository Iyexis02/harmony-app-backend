-- Batch C: Match data-integrity hardening
-- Adds a unique constraint on (user_a_id, user_b_id) so the database enforces
-- that at most one match record exists for each user pair regardless of race conditions.

-- Step 1: remove any duplicate match rows that already exist, keeping the oldest one.
DELETE FROM matches
WHERE id NOT IN (
    SELECT DISTINCT ON (user_a_id, user_b_id) id
    FROM matches
    ORDER BY user_a_id, user_b_id, created_at ASC
);

-- Step 2: add the unique constraint (idempotent — skips if already present).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uq_match_user_pair'
          AND conrelid = 'matches'::regclass
    ) THEN
        ALTER TABLE matches
            ADD CONSTRAINT uq_match_user_pair UNIQUE (user_a_id, user_b_id);
    END IF;
END $$;
