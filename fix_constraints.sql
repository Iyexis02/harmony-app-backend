-- Fix NOT NULL constraints to match UserEntity requirements

-- Fix registration_stage - should NOT be NULL
UPDATE users SET registration_stage = 'STARTED' WHERE registration_stage IS NULL;
ALTER TABLE users ALTER COLUMN registration_stage SET NOT NULL;

-- Fix created_at - should NOT be NULL
UPDATE users SET created_at = NOW() WHERE created_at IS NULL;
ALTER TABLE users ALTER COLUMN created_at SET NOT NULL;

-- Verify constraints
SELECT
    column_name,
    data_type,
    is_nullable,
    column_default
FROM information_schema.columns
WHERE table_name = 'users'
    AND column_name IN ('registration_stage', 'created_at', 'premium_status', 'cache_version', 'profile_completion_score')
ORDER BY column_name;
