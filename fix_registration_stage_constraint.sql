-- Fix registration_stage check constraint to match Java RegistrationStage enum
-- Date: 2025-12-01
-- Issue: Database constraint uses old values (STEP1-4), but Java enum uses descriptive names

-- Step 1: Drop the old check constraint
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_registration_stage_check;

-- Step 2: Update any existing STEP1-4 values to new descriptive names (if any exist)
-- Note: This mapping assumes STEP1 = BASIC_PROFILE, STEP2 = LOCATION_INFO, etc.
UPDATE users SET registration_stage = 'BASIC_PROFILE' WHERE registration_stage = 'STEP1';
UPDATE users SET registration_stage = 'LOCATION_INFO' WHERE registration_stage = 'STEP2';
UPDATE users SET registration_stage = 'PHOTOS' WHERE registration_stage = 'STEP3';
UPDATE users SET registration_stage = 'MUSIC_PREFERENCES' WHERE registration_stage = 'STEP4';

-- Step 3: Create new check constraint with all proper enum values
ALTER TABLE users ADD CONSTRAINT users_registration_stage_check
CHECK (registration_stage IN (
    'STARTED',
    'BASIC_PROFILE',
    'LOCATION_INFO',
    'PHOTOS',
    'MUSIC_PREFERENCES',
    'LIFESTYLE',
    'PERSONALITY',
    'DATING_PREFERENCES',
    'PRIVACY_SETTINGS',
    'FINISHED'
));

-- Step 4: Verify the constraint was created correctly
SELECT
    conname AS constraint_name,
    pg_get_constraintdef(oid) AS constraint_definition
FROM pg_constraint
WHERE conrelid = 'users'::regclass
AND conname = 'users_registration_stage_check';

-- Step 5: Verify current registration_stage values
SELECT
    registration_stage,
    COUNT(*) as count
FROM users
GROUP BY registration_stage
ORDER BY registration_stage;
