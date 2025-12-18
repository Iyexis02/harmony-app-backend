-- Fix database schema after refactoring
-- Remove old columns from users table that were moved to separate entities

-- Columns moved to UserPrivacySettings
ALTER TABLE users DROP COLUMN IF EXISTS is_profile_public;

-- Columns moved to UserLifestyle
ALTER TABLE users DROP COLUMN IF EXISTS education;
ALTER TABLE users DROP COLUMN IF EXISTS occupation;
ALTER TABLE users DROP COLUMN IF EXISTS relationship_status;
ALTER TABLE users DROP COLUMN IF EXISTS wants_kids;

-- Column removed (now handled by UserPhoto entity)
ALTER TABLE users DROP COLUMN IF EXISTS image_url;

-- Verify the cleanup
SELECT
    column_name,
    data_type,
    is_nullable,
    column_default
FROM information_schema.columns
WHERE table_name = 'users'
ORDER BY ordinal_position;
