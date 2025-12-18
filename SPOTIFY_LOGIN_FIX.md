# Spotify Login Endpoint Fix

## Issues Found and Fixed

### ✅ **Issue 1: Missing Encryption Key Configuration**
**Problem:** The `EncryptionServiceImpl` requires `encryption.secret.key` property which was missing from `application.yml`.

**Location:** `src/main/java/com/example/dating/services/impl/EncryptionServiceImpl.java:27`

**Error:** Application fails to start or throws exception when trying to encrypt Spotify tokens.

**Fix Applied:** Added encryption configuration to `application.yml`:
```yaml
encryption:
  secret:
    key: ${ENCRYPTION_SECRET_KEY:xhnXQGxzJn/nCBAfZfPrvafxfrmAKDTZeL5r+gJ+q70=}
```

This uses an environment variable if available, otherwise falls back to the default key.

---

## Verification Steps

### 1. Check PostgreSQL is Running
```bash
# Check if PostgreSQL is running on port 5433
netstat -an | grep 5433
# or
docker ps | grep postgres
```

Your database config:
- Host: 127.0.0.1
- Port: 5433
- Database: dating
- Username: root
- Password: secret

### 2. Test the Endpoint

**Request:**
```bash
curl -X POST http://localhost:8080/api/v1/auth/spotify-login \
  -H "Content-Type: application/json" \
  -d '{
    "spotifyId": "z870zfmpu1c3y8vzbs3c8fdqz",
    "email": "mladenhangi.12@gmail.com",
    "name": "Iyexis",
    "spotifyAccessToken": "BQBWCCJ_Yx-g58gXnfUSmVTgoxW_SwPiDsLfmUR7lIgITGzpF5kCQsRm29q2Ihh_LVcUnswy_bvDB8RjwdN-hI0qBtUj3aZxlxxcWWPwsaybmXDt3Gt82klP2fJKJ0tqVSBFQdOyicy4j_YUsToJZwLabtjaQQoVJw0MGxpkAdeTGVzWejPPmWPwuGxJmtzWHYUeKvVZG6X4zaE5dUeyl3r0WXXfK_rWbUc571WIPtsvbSv4-vVpWbrlseBNbhHlHYxsIoKhBg",
    "spotifyRefreshToken": "AQA9dPj85xpJkCssUPPqKyccHe75LZ5oplbxyB_RdWxT0LNOxE0B1-s6xyS4PDzFTcVILG3o8sDEpVd33q2RhblXT_070Q4eSUU2txB2fa39ck_BSD6Z8-JDbpyLgfoDBvw",
    "spotifyTokenExpiresAt": 1764025919,
    "imageUrl": "https://scontent-lhr6-1.xx.fbcdn.net/v/t1.30497-1/84628273_176159830277856_972693363922829312_n.jpg?stp=c379.0.1290.1290a_dst-jpg_s320x320_tt6&_nc_cat=1&ccb=1-7&_nc_sid=7565cd&_nc_ohc=AkLkGXARHVkQ7kNvwF5rAKT&_nc_oc=AdncNwN8I8Ije8bl-Gftt-ALteAKQegZct4joqEv2Q-Qjt8hVjzLd81ppjtwjLVmRlBDguFQe4TbLptX97mdzj5O&_nc_zt=24&_nc_ht=scontent-lhr6-1.xx&edm=AP4hL3IEAAAA&oh=00_AfjNI4xBrZpIxhynY_6vth-vkdHFjeQ14ENTx7GGQPnzdg&oe=694C5859"
  }'
```

**Expected Response (200 OK):**
```json
{
  "id": "uuid-here",
  "email": "mladenhangi.12@gmail.com",
  "name": "Iyexis",
  "imageUrl": "https://...",
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

### 3. Check Application Logs

The application will show SQL statements since `show-sql: true` is enabled:
```
Hibernate: insert into users (created_at, dob, email, gender, ... ) values (?, ?, ?, ?, ...)
```

---

## Common Error Messages and Solutions

### Error: "Failed to configure a DataSource"
**Solution:** Make sure PostgreSQL is running:
```bash
docker run --name postgres-dating -e POSTGRES_PASSWORD=secret -e POSTGRES_USER=root -e POSTGRES_DB=dating -p 5433:5432 -d postgres:15
```

### Error: "UnsupportedClassVersionError"
**Solution:** Make sure you're using Java 23:
```bash
java -version  # Should show Java 23
```

### Error: "Encryption failed"
**Solution:**
1. The encryption key has been added to application.yml
2. For production, set environment variable:
   ```bash
   export ENCRYPTION_SECRET_KEY="your-secure-key-here"
   ```

### Error: 400 Bad Request
**Check:**
1. JSON format is correct
2. All required fields are present: `spotifyId`, `email`, `name`, `spotifyAccessToken`, `spotifyRefreshToken`, `spotifyTokenExpiresAt`
3. Check logs for validation errors

### Error: 500 Internal Server Error
**Check logs for:**
1. Database connection issues
2. Encryption service errors
3. Null pointer exceptions

---

## Flow Explanation

When you call `/spotify-login`:

1. **AuthController** receives the request (line 28)
2. Calls **UserService.findOrCreateUser()** (line 30)
3. **UserServiceImpl**:
   - Checks if user exists by spotifyId (line 37)
   - If exists: Updates tokens and info (lines 41-47)
   - If new: Creates new user with STARTED stage (lines 49-59)
   - **Encrypts** access and refresh tokens using AES-256-GCM (lines 45-47 or 56-58)
4. **UserRepository** saves to PostgreSQL (line 63)
5. **UserMapper** converts to DTO response (line 64)
6. Returns user info to frontend (line 31)

---

## Security Notes

### Encryption
- Access tokens and refresh tokens are encrypted using AES-256-GCM
- Each encryption uses a random IV (Initialization Vector)
- Stored as Base64 in the database

### Production Recommendations

1. **Use Environment Variable for Encryption Key:**
   ```bash
   # Generate a new key for production
   openssl rand -base64 32

   # Set as environment variable
   export ENCRYPTION_SECRET_KEY="<generated-key>"
   ```

2. **Don't commit encryption keys to Git:**
   - The default key in application.yml is for development only
   - Use environment variables in production

3. **Database Security:**
   - Change default password from "secret"
   - Use SSL connections in production
   - Restrict network access to database

---

## Database Schema

The endpoint will create/update the `users` table:

```sql
CREATE TABLE users (
    id VARCHAR(36) PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    spotify_id VARCHAR(255) UNIQUE,
    access_token TEXT,  -- Encrypted
    refresh_token TEXT, -- Encrypted
    spotify_token_expires TIMESTAMP,
    last_spotify_sync_at TIMESTAMP,
    name VARCHAR(100),
    dob DATE,
    gender VARCHAR(50),
    sexual_orientation VARCHAR(50),
    location_lat DECIMAL(10,7),
    location_lon DECIMAL(10,7),
    location_city VARCHAR(100),
    location_country VARCHAR(100),
    language VARCHAR(50),
    image_url TEXT,
    registration_stage VARCHAR(50) NOT NULL,
    premium_status BOOLEAN NOT NULL DEFAULT false,
    subscription_expires TIMESTAMP,
    profile_completion_score INTEGER DEFAULT 0,
    cache_version INTEGER DEFAULT 1,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

-- Indexes created automatically by JPA
CREATE INDEX idx_spotify_id ON users(spotify_id);
CREATE INDEX idx_location ON users(location_lat, location_lon);
CREATE INDEX idx_registration_stage ON users(registration_stage);
CREATE INDEX idx_premium_status ON users(premium_status);
```

---

## Next Steps After Login

After successful login, the frontend should:

1. **Store the user ID** from response for subsequent API calls
2. **Check registrationStage**: If "STARTED", redirect to onboarding
3. **Begin onboarding flow**:
   - PUT `/api/v1/onboarding/basic-info`
   - PUT `/api/v1/onboarding/location`
   - PUT `/api/v1/onboarding/photos`
   - PUT `/api/v1/onboarding/music-preferences`
   - PUT `/api/v1/onboarding/lifestyle`
   - PUT `/api/v1/onboarding/personality`
   - PUT `/api/v1/onboarding/dating-preferences`
   - PUT `/api/v1/onboarding/privacy-settings`

4. **Track progress** with:
   - GET `/api/v1/onboarding/progress`

---

## Troubleshooting Commands

### Check if app is running
```bash
curl http://localhost:8080/actuator/health
```

### Check database connection
```bash
psql -h 127.0.0.1 -p 5433 -U root -d dating -c "SELECT version();"
```

### View recent users
```bash
psql -h 127.0.0.1 -p 5433 -U root -d dating -c "SELECT id, email, name, registration_stage, created_at FROM users ORDER BY created_at DESC LIMIT 5;"
```

### Clear test data
```bash
psql -h 127.0.0.1 -p 5433 -U root -d dating -c "DELETE FROM users WHERE email = 'mladenhangi.12@gmail.com';"
```

---

## Testing with cURL

### Create new user
```bash
curl -v -X POST http://localhost:8080/api/v1/auth/spotify-login \
  -H "Content-Type: application/json" \
  -d @- << 'EOF'
{
  "spotifyId": "testuser123",
  "email": "test@example.com",
  "name": "Test User",
  "spotifyAccessToken": "test_access_token",
  "spotifyRefreshToken": "test_refresh_token",
  "spotifyTokenExpiresAt": 1764025919,
  "imageUrl": "https://example.com/image.jpg"
}
EOF
```

### Login existing user
```bash
curl -v -X POST http://localhost:8080/api/v1/auth/spotify-login \
  -H "Content-Type: application/json" \
  -d @- << 'EOF'
{
  "spotifyId": "testuser123",
  "email": "newemail@example.com",
  "name": "Updated Name",
  "spotifyAccessToken": "new_access_token",
  "spotifyRefreshToken": "new_refresh_token",
  "spotifyTokenExpiresAt": 1764029999,
  "imageUrl": "https://example.com/newimage.jpg"
}
EOF
```

The second call will update the existing user's information.

---

## Status: FIXED ✅

The endpoint should now work correctly. The encryption key has been added to `application.yml`.

**What was changed:**
- File: `src/main/resources/application.yml`
- Added: `encryption.secret.key` configuration
- Value: Base64-encoded 256-bit AES key with environment variable fallback
