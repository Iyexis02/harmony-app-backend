# Database Constraint Fix - Registration Stage

**Date**: 2025-12-01
**Issue**: Database check constraint doesn't match Java enum values
**Status**: ⚠️ REQUIRES DATABASE MIGRATION

---

## 🔴 The Problem

**Error Message**:
```
ERROR: new row for relation "users" violates check constraint "users_registration_stage_check"
Detail: Failing row contains (..., BASIC_PROFILE, ...)
```

**Root Cause**:
The database has an **outdated check constraint** that only allows these values:
- `STARTED`
- `STEP1`
- `STEP2`
- `STEP3`
- `STEP4`
- `FINISHED`

But the Java `RegistrationStage` enum uses **descriptive names**:
- `STARTED`
- `BASIC_PROFILE` ❌ (rejected by DB)
- `LOCATION_INFO` ❌ (rejected by DB)
- `PHOTOS` ❌ (rejected by DB)
- `MUSIC_PREFERENCES` ❌ (rejected by DB)
- `LIFESTYLE` ❌ (rejected by DB)
- `PERSONALITY` ❌ (rejected by DB)
- `DATING_PREFERENCES` ❌ (rejected by DB)
- `PRIVACY_SETTINGS` ❌ (rejected by DB)
- `FINISHED`

---

## ✅ The Solution

Run the migration script to update the database constraint.

### Step 1: Connect to PostgreSQL

```bash
# Using psql
psql -h localhost -U postgres -d dating_db

# Or using your preferred database tool (DBeaver, pgAdmin, etc.)
```

### Step 2: Run the Migration Script

```bash
# From command line
psql -h localhost -U postgres -d dating_db -f fix_registration_stage_constraint.sql

# Or copy-paste the SQL commands into your database tool
```

### Step 3: Verify the Fix

After running the script, you should see output like:

```
ALTER TABLE
UPDATE 0  (or number of rows updated)
UPDATE 0
UPDATE 0
UPDATE 0
ALTER TABLE

 constraint_name                  | constraint_definition
----------------------------------+-------------------------
 users_registration_stage_check  | CHECK (registration_stage IN ('STARTED', 'BASIC_PROFILE', ...))

 registration_stage    | count
-----------------------+-------
 STARTED               | X
 (rows showing current values)
```

---

## 📝 Migration Script Details

The script `fix_registration_stage_constraint.sql` performs these steps:

### 1. Drop Old Constraint
```sql
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_registration_stage_check;
```

### 2. Migrate Existing Data (if needed)
```sql
-- If your database has any users with old STEP1-4 values, they'll be updated
UPDATE users SET registration_stage = 'BASIC_PROFILE' WHERE registration_stage = 'STEP1';
UPDATE users SET registration_stage = 'LOCATION_INFO' WHERE registration_stage = 'STEP2';
UPDATE users SET registration_stage = 'PHOTOS' WHERE registration_stage = 'STEP3';
UPDATE users SET registration_stage = 'MUSIC_PREFERENCES' WHERE registration_stage = 'STEP4';
```

### 3. Create New Constraint
```sql
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
```

---

## 🧪 Testing After Migration

### Test 1: Verify Constraint Allows New Values

```bash
# Should succeed now
curl -X PUT http://localhost:8080/api/v1/onboarding/basic-info \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Test User",
    "dateOfBirth": "1995-06-15",
    "gender": "MALE",
    "sexualOrientation": "STRAIGHT"
  }'
```

**Expected Response**: `200 OK` with `registrationStage: "BASIC_PROFILE"`

### Test 2: Complete Multiple Steps

```bash
# Step 1: Basic Profile
PUT /api/v1/onboarding/basic-info
# Response should have: "registrationStage": "BASIC_PROFILE" ✅

# Step 2: Location
PUT /api/v1/onboarding/location
# Response should have: "registrationStage": "LOCATION_INFO" ✅

# Step 3: Photos
PUT /api/v1/onboarding/photos
# Response should have: "registrationStage": "PHOTOS" ✅
```

All should succeed without constraint violations!

---

## 🔍 How This Happened

**Timeline**:
1. Initially, the database was created with a simple step system (`STEP1`, `STEP2`, etc.)
2. The Java code was refactored to use descriptive enum names (`BASIC_PROFILE`, `LOCATION_INFO`, etc.)
3. The database constraint was **not updated** to match the Java enum
4. When the onboarding fix added `registrationStage` updates, it exposed this mismatch

**Why It Wasn't Caught Earlier**:
- The original code **wasn't updating** `registrationStage` at all (the bug we just fixed)
- So the constraint mismatch was never triggered
- Once we fixed the registration stage tracking, the constraint violation appeared

---

## 🚨 Important Notes

### Before Running Migration

1. **Backup your database** (always a good practice)
   ```bash
   pg_dump -h localhost -U postgres dating_db > dating_db_backup_$(date +%Y%m%d).sql
   ```

2. **Check for existing data**
   ```sql
   SELECT registration_stage, COUNT(*)
   FROM users
   GROUP BY registration_stage;
   ```

3. **Verify no production users are affected**
   - If you have users with `STEP1-4` values, the migration will update them
   - Make sure the mapping is correct for your use case

### After Running Migration

1. **Restart your Spring Boot application** (to clear any cached constraints)
   ```bash
   # Stop app (Ctrl+C)
   mvn spring-boot:run
   ```

2. **Test all onboarding steps** to ensure they work correctly

3. **Monitor logs** for any constraint violations

---

## 🎯 Quick Fix Checklist

- [ ] Backup database
- [ ] Run migration script: `fix_registration_stage_constraint.sql`
- [ ] Verify constraint was updated (check script output)
- [ ] Restart Spring Boot application
- [ ] Test basic-info endpoint (should succeed now)
- [ ] Test all 8 onboarding steps
- [ ] Verify registration stage updates correctly
- [ ] Update your team/deployment docs with this migration

---

## 📊 Before vs After

### Before Migration ❌

```
Database Constraint: ['STARTED', 'STEP1', 'STEP2', 'STEP3', 'STEP4', 'FINISHED']
Java Enum:          ['STARTED', 'BASIC_PROFILE', 'LOCATION_INFO', ...]
                                       ❌ MISMATCH!
Result: Constraint violation error
```

### After Migration ✅

```
Database Constraint: ['STARTED', 'BASIC_PROFILE', 'LOCATION_INFO', 'PHOTOS',
                      'MUSIC_PREFERENCES', 'LIFESTYLE', 'PERSONALITY',
                      'DATING_PREFERENCES', 'PRIVACY_SETTINGS', 'FINISHED']
Java Enum:          ['STARTED', 'BASIC_PROFILE', 'LOCATION_INFO', 'PHOTOS',
                     'MUSIC_PREFERENCES', 'LIFESTYLE', 'PERSONALITY',
                     'DATING_PREFERENCES', 'PRIVACY_SETTINGS', 'FINISHED']
                                       ✅ MATCH!
Result: Onboarding works perfectly
```

---

## 🔧 Alternative: Quick Fix (Development Only)

If you're in development and want to quickly test without the migration:

```sql
-- TEMPORARY FIX (not recommended for production)
-- Just drop the constraint entirely
ALTER TABLE users DROP CONSTRAINT users_registration_stage_check;
```

**⚠️ Warning**: This removes validation, so any value can be inserted. Use the proper migration for production!

---

## 📞 Troubleshooting

### Issue: "constraint does not exist"

```
ERROR: constraint "users_registration_stage_check" of relation "users" does not exist
```

**Solution**: The constraint might have a different name. Find it with:
```sql
SELECT conname
FROM pg_constraint
WHERE conrelid = 'users'::regclass
AND contype = 'c';
```

Then update the script with the correct constraint name.

### Issue: Migration runs but constraint still fails

**Solution**:
1. Check if constraint was actually dropped:
   ```sql
   SELECT * FROM pg_constraint WHERE conname = 'users_registration_stage_check';
   ```
2. If it exists, manually drop it:
   ```sql
   ALTER TABLE users DROP CONSTRAINT users_registration_stage_check CASCADE;
   ```
3. Re-run the migration script

### Issue: "permission denied"

**Solution**: Make sure you're connected as a user with ALTER TABLE permissions (usually `postgres` superuser)

---

## ✅ Summary

**Root Cause**: Database constraint used old `STEP1-4` values, Java enum uses descriptive names

**Fix**: Run `fix_registration_stage_constraint.sql` to update the database constraint

**Impact**: After running migration, all onboarding endpoints will work correctly

**Time to Fix**: ~2 minutes (including verification)

---

**Status**: Migration script ready to run
**Location**: `fix_registration_stage_constraint.sql`
**Last Updated**: 2025-12-01
