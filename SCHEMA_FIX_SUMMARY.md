# Database Schema Fix Summary

## Issue
After refactoring UserEntity to split into multiple entities, the database still contained old columns that were causing constraint violations when trying to insert new users.

## Changes Applied

### 1. Removed Old Columns from `users` Table
```sql
-- Columns moved to UserPrivacySettings
DROP COLUMN is_profile_public

-- Columns moved to UserLifestyle
DROP COLUMN education
DROP COLUMN occupation
DROP COLUMN relationship_status
DROP COLUMN wants_kids

-- Column replaced by UserPhoto entity
DROP COLUMN image_url
```

### 2. Fixed NOT NULL Constraints
```sql
-- Made registration_stage NOT NULL (default: STARTED)
ALTER TABLE users ALTER COLUMN registration_stage SET NOT NULL

-- Made created_at NOT NULL
ALTER TABLE users ALTER COLUMN created_at SET NOT NULL
```

## Current Database Schema

### Main Tables
- `users` - Core user data (email, spotify, location, basic profile)
- `user_music_preferences` - Music taste preferences
- `user_lifestyle` - Education, occupation, habits
- `user_personality` - Bio, interests, MBTI
- `user_dating_preferences` - Age range, distance, match preferences
- `user_privacy_settings` - Privacy controls
- `user_photos` - Multiple profile photos

### `users` Table Structure (after fix)
| Column | Type | Nullable | Notes |
|--------|------|----------|-------|
| id | varchar(36) | NO | Primary key |
| email | varchar(255) | NO | Unique |
| spotify_id | varchar(255) | YES | Unique |
| name | varchar(100) | YES | |
| dob | date | YES | Date of birth |
| gender | varchar(50) | YES | Enum |
| sexual_orientation | varchar(50) | YES | Enum |
| location_lat | numeric | YES | Latitude |
| location_lon | numeric | YES | Longitude |
| location_city | varchar(100) | YES | |
| location_country | varchar(100) | YES | |
| language | varchar(50) | YES | |
| spotify_access_token | text | YES | Encrypted |
| spotify_refresh_token | text | YES | Encrypted |
| spotify_token_expires | timestamp(tz) | YES | |
| last_spotify_sync_at | timestamp | YES | |
| registration_stage | varchar(50) | NO | STARTED to FINISHED |
| premium_status | boolean | NO | Default: false |
| subscription_expires | timestamp | YES | |
| profile_completion_score | integer | YES | 0-100 |
| cache_version | integer | YES | For matching algorithm |
| created_at | timestamp | NO | Auto-set |
| updated_at | timestamp | YES | Auto-updated |

## Test the Fix

Now you can test the `/spotify-login` endpoint:

```bash
curl -X POST http://localhost:8080/api/v1/auth/spotify-login \
  -H "Content-Type: application/json" \
  -d '{
    "spotifyId": "z870zfmpu1c3y8vzbs3c8fdqz",
    "email": "mladenhangi.12@gmail.com",
    "name": "Iyexis",
    "spotifyAccessToken": "YOUR_ACCESS_TOKEN",
    "spotifyRefreshToken": "YOUR_REFRESH_TOKEN",
    "spotifyTokenExpiresAt": 1764025919,
    "imageUrl": "https://example.com/image.jpg"
  }'
```

## Expected Result

✅ **Success Response (200 OK):**
```json
{
  "id": "generated-uuid",
  "email": "mladenhangi.12@gmail.com",
  "name": "Iyexis",
  "imageUrl": null,
  "registrationStage": "STARTED",
  "city": null,
  "country": null,
  "latitude": null,
  "longitude": null,
  "sexualOrientation": null,
  "gender": null,
  "dateOfBirth": null
}
```

## What Was NOT Fixed

These columns are nullable but have `@Builder.Default` in Java. They should get default values when Hibernate creates the schema, but existing rows won't be updated:

- `cache_version` (should default to 1)
- `profile_completion_score` (should default to 0)

This is fine because:
1. These are optional fields
2. JPA will use the defaults for new inserts if not specified
3. Existing rows can be NULL without issues

## Verification Queries

### Check a user record
```sql
SELECT id, email, name, registration_stage, created_at, premium_status
FROM users
WHERE email = 'mladenhangi.12@gmail.com';
```

### Check related entities
```sql
-- Check if privacy settings exist
SELECT * FROM user_privacy_settings WHERE user_id = 'user-uuid-here';

-- Check if photos exist
SELECT * FROM user_photos WHERE user_id = 'user-uuid-here';

-- Check all related data
SELECT
    u.email,
    u.registration_stage,
    CASE WHEN ump.id IS NOT NULL THEN 'Yes' ELSE 'No' END as has_music_prefs,
    CASE WHEN ul.id IS NOT NULL THEN 'Yes' ELSE 'No' END as has_lifestyle,
    CASE WHEN up.id IS NOT NULL THEN 'Yes' ELSE 'No' END as has_personality,
    CASE WHEN udp.id IS NOT NULL THEN 'Yes' ELSE 'No' END as has_dating_prefs,
    CASE WHEN ups.id IS NOT NULL THEN 'Yes' ELSE 'No' END as has_privacy
FROM users u
LEFT JOIN user_music_preferences ump ON u.id = ump.user_id
LEFT JOIN user_lifestyle ul ON u.id = ul.user_id
LEFT JOIN user_personality up ON u.id = up.user_id
LEFT JOIN user_dating_preferences udp ON u.id = udp.user_id
LEFT JOIN user_privacy_settings ups ON u.id = ups.user_id
WHERE u.email = 'mladenhangi.12@gmail.com';
```

## Schema is Now Clean ✅

The database schema now matches the refactored Java entities. The endpoint should work without constraint violations.
