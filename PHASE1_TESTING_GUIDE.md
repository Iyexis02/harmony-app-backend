# Phase 1 Testing Guide - Step by Step

This guide will walk you through testing every component of Phase 1 to understand how the matching system foundation works.

---

## Prerequisites

1. **Application is running**: `mvn spring-boot:run`
2. **Database is accessible**: PostgreSQL on port 5433
3. **At least 2 test users exist** in your database

---

## Method 1: Automated Integration Tests

### Run the Complete Test Suite

```bash
mvn test -Dtest=Phase1IntegrationTest
```

This will run **12 comprehensive tests** that verify:
- ✅ Genre seed data
- ✅ Genre search & lookup
- ✅ Genre hierarchy
- ✅ User genre preferences (CRUD)
- ✅ User swipes
- ✅ Mutual matches
- ✅ All repository queries
- ✅ Cross-entity relationships

### What You'll See

The tests output detailed information:

```
🧪 TEST 1: Verifying Genre Seed Data
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
✓ Total genres in database: 152
✓ Primary genres (shown in UI): 28

First 10 primary genres:
  → Rock (rock)
  → Pop (pop)
  → Hip Hop (hip-hop)
  → Electronic (electronic)
  ...

✅ Genre seed data verification passed!
```

---

## Method 2: Manual API Testing

Use the Phase 1 Test Controller to manually test via REST API.

**Base URL**: `http://localhost:8080/api/test/phase1`

### 1. Check Overall Statistics

```bash
curl http://localhost:8080/api/test/phase1/stats
```

**Expected Response**:
```json
{
  "genres": {
    "total": 152,
    "primary": 28,
    "topLevel": 15
  },
  "users": {
    "totalUsers": 5,
    "usersWithGenrePreferences": 0
  },
  "swipes": {
    "total": 0
  },
  "matches": {
    "total": 0
  }
}
```

**What this tells you**:
- 152 genres were seeded on startup
- 28 are primary genres (shown in UI)
- 15 are top-level (Rock, Pop, Hip-Hop, etc.)
- Your users exist but haven't set genre preferences yet

---

### 2. Explore Genres

#### 2a. List All Primary Genres

```bash
curl "http://localhost:8080/api/test/phase1/genres?primaryOnly=true"
```

**Expected Response**:
```json
{
  "total": 28,
  "genres": [
    {
      "id": "...",
      "name": "rock",
      "displayName": "Rock",
      "isPrimary": true,
      "parentGenre": null
    },
    {
      "id": "...",
      "name": "indie-rock",
      "displayName": "Indie Rock",
      "isPrimary": true,
      "parentGenre": "Rock"
    },
    ...
  ]
}
```

**What this shows**: All genres marked as "primary" (shown in UI dropdowns, etc.)

#### 2b. Search for Genres

```bash
curl "http://localhost:8080/api/test/phase1/genres?search=indie"
```

**Expected Response**:
```json
{
  "total": 7,
  "genres": [
    {"name": "indie", "displayName": "Indie", ...},
    {"name": "indie-rock", "displayName": "Indie Rock", ...},
    {"name": "indie-pop", "displayName": "Indie Pop", ...},
    {"name": "indie-folk", "displayName": "Indie Folk", ...},
    ...
  ]
}
```

**What this demonstrates**: Fuzzy search works - finds all genres with "indie" in name or display name

#### 2c. View Genre Hierarchy

```bash
curl http://localhost:8080/api/test/phase1/genres/hierarchy
```

**Expected Response**:
```json
{
  "topLevelCount": 15,
  "hierarchy": [
    {
      "name": "rock",
      "displayName": "Rock",
      "children": [
        {"name": "indie-rock", "displayName": "Indie Rock"},
        {"name": "alternative-rock", "displayName": "Alternative Rock"},
        {"name": "punk-rock", "displayName": "Punk Rock"},
        ...
      ]
    },
    {
      "name": "pop",
      "displayName": "Pop",
      "children": [
        {"name": "indie-pop", "displayName": "Indie Pop"},
        {"name": "synth-pop", "displayName": "Synth Pop"},
        ...
      ]
    },
    ...
  ]
}
```

**What this shows**: The parent-child relationships between genres (Rock → Indie Rock, etc.)

---

### 3. Create User Genre Preferences

First, get a user ID from your database:

```sql
SELECT id, email FROM users LIMIT 1;
```

Let's say the user ID is `abc-123-def-456`.

#### 3a. Add Genre Preferences

```bash
# Add Indie Rock preference
curl -X POST http://localhost:8080/api/test/phase1/users/abc-123-def-456/genres \
  -H "Content-Type: application/json" \
  -d '{
    "genreName": "indie-rock",
    "weight": 0.9,
    "source": "manual_selection"
  }'
```

**Expected Response**:
```json
{
  "message": "Created",
  "preference": {
    "id": "...",
    "genre": {
      "name": "indie-rock",
      "displayName": "Indie Rock"
    },
    "weight": 0.9,
    "source": "manual_selection",
    "confidence": 1.0
  }
}
```

Add a few more:

```bash
# Add Electronic preference
curl -X POST http://localhost:8080/api/test/phase1/users/abc-123-def-456/genres \
  -H "Content-Type: application/json" \
  -d '{"genreName": "electronic", "weight": 0.7, "source": "manual_selection"}'

# Add Jazz preference
curl -X POST http://localhost:8080/api/test/phase1/users/abc-123-def-456/genres \
  -H "Content-Type: application/json" \
  -d '{"genreName": "jazz", "weight": 0.5, "source": "manual_selection"}'
```

#### 3b. View User's Genre Preferences

```bash
curl http://localhost:8080/api/test/phase1/users/abc-123-def-456/genres
```

**Expected Response**:
```json
{
  "userId": "abc-123-def-456",
  "totalGenres": 3,
  "preferences": [
    {
      "genre": {"displayName": "Indie Rock"},
      "weight": 0.9,
      "source": "manual_selection",
      "rank": null
    },
    {
      "genre": {"displayName": "Electronic"},
      "weight": 0.7,
      "source": "manual_selection",
      "rank": null
    },
    {
      "genre": {"displayName": "Jazz"},
      "weight": 0.5,
      "source": "manual_selection",
      "rank": null
    }
  ]
}
```

**What this shows**:
- User now has 3 genre preferences
- Ordered by weight (highest first)
- Each has a source ("manual_selection" vs "spotify_derived")

---

### 4. Test Swipe Functionality

Get two user IDs from your database:

```sql
SELECT id, email FROM users LIMIT 2;
```

Let's say:
- User 1: `user-1-id`
- User 2: `user-2-id`

#### 4a. User 1 Likes User 2

```bash
curl -X POST http://localhost:8080/api/test/phase1/swipe \
  -H "Content-Type: application/json" \
  -d '{
    "swiperId": "user-1-id",
    "swipedId": "user-2-id",
    "action": "like",
    "score": 85.5
  }'
```

**Expected Response**:
```json
{
  "swipe": {
    "id": "...",
    "action": "like",
    "matchScore": 85.5,
    "resultedInMatch": false
  },
  "mutualMatch": false
}
```

**What happened**: User 1 swiped right (liked) User 2, but no mutual match yet

#### 4b. User 2 Likes User 1 Back (Mutual Match!)

```bash
curl -X POST http://localhost:8080/api/test/phase1/swipe \
  -H "Content-Type: application/json" \
  -d '{
    "swiperId": "user-2-id",
    "swipedId": "user-1-id",
    "action": "like",
    "score": 82.0
  }'
```

**Expected Response**:
```json
{
  "swipe": {
    "id": "...",
    "action": "like",
    "matchScore": 82.0,
    "resultedInMatch": true
  },
  "mutualMatch": true,
  "match": {
    "id": "...",
    "matchedAt": "2024-01-15T10:30:00",
    "matchScore": 82.0,
    "status": "active",
    "conversationStarted": false,
    "matchSource": "mutual_swipe"
  }
}
```

**What happened**:
- User 2 liked User 1 back
- System detected mutual like
- Created a Match entity automatically
- Both swipes updated with `resultedInMatch: true`

#### 4c. View User's Swipe History

```bash
curl http://localhost:8080/api/test/phase1/users/user-1-id/swipes
```

**Expected Response**:
```json
{
  "userId": "user-1-id",
  "totalSwipes": 1,
  "likes": 1,
  "passes": 0,
  "swipeThroughRate": 100.0,
  "matchRate": 100.0,
  "swipes": [
    {
      "id": "...",
      "action": "like",
      "swipedAt": "2024-01-15T10:25:00",
      "matchScore": 85.5,
      "resultedInMatch": true
    }
  ]
}
```

**Analytics explained**:
- **swipeThroughRate**: 100% (1 like out of 1 total swipe)
- **matchRate**: 100% (1 match out of 1 like)

---

### 5. Check Matches

#### 5a. View User's Matches

```bash
curl http://localhost:8080/api/test/phase1/users/user-1-id/matches
```

**Expected Response**:
```json
{
  "userId": "user-1-id",
  "totalMatches": 1,
  "withConversation": 0,
  "withoutConversation": 1,
  "matches": [
    {
      "id": "...",
      "matchedAt": "2024-01-15T10:30:00",
      "matchScore": 82.0,
      "status": "active",
      "conversationStarted": false,
      "matchSource": "mutual_swipe"
    }
  ]
}
```

**What this shows**: User 1 has 1 active match, conversation not started yet

#### 5b. Check If Two Users Are Matched

```bash
curl "http://localhost:8080/api/test/phase1/matches/check?user1Id=user-1-id&user2Id=user-2-id"
```

**Expected Response**:
```json
{
  "user1Id": "user-1-id",
  "user2Id": "user-2-id",
  "matched": true,
  "match": {
    "id": "...",
    "matchedAt": "2024-01-15T10:30:00",
    "matchScore": 82.0,
    "status": "active"
  }
}
```

---

## Method 3: Direct Database Inspection

Connect to your PostgreSQL database and run these queries:

### View All Genres

```sql
-- Count genres
SELECT COUNT(*) FROM canonical_genres;
-- Should return ~152

-- View primary genres
SELECT display_name, name, is_primary
FROM canonical_genres
WHERE is_primary = true
ORDER BY display_order;
```

### View Genre Hierarchy

```sql
-- See parent-child relationships
SELECT
    child.display_name AS "Child Genre",
    parent.display_name AS "Parent Genre"
FROM canonical_genres child
LEFT JOIN canonical_genres parent ON child.parent_genre_id = parent.id
WHERE child.parent_genre_id IS NOT NULL
ORDER BY parent.display_name, child.display_name;
```

**Expected Result**:
```
Child Genre          | Parent Genre
---------------------|-------------
Indie Rock          | Rock
Alternative Rock    | Rock
Punk Rock           | Rock
...
Indie Pop           | Pop
Synth Pop           | Pop
...
```

### View User Genre Preferences

```sql
SELECT
    u.email,
    g.display_name AS genre,
    ugp.weight,
    ugp.source,
    ugp.confidence
FROM user_genre_preferences ugp
JOIN users u ON ugp.user_id = u.id
JOIN canonical_genres g ON ugp.genre_id = g.id
ORDER BY u.email, ugp.weight DESC;
```

### View Swipes and Matches

```sql
-- View all swipes
SELECT
    u1.email AS swiper,
    u2.email AS swiped,
    us.action,
    us.match_score_at_swipe,
    us.resulted_in_match,
    us.swiped_at
FROM user_swipes us
JOIN users u1 ON us.swiper_user_id = u1.id
JOIN users u2 ON us.swiped_user_id = u2.id
ORDER BY us.swiped_at DESC;

-- View all matches
SELECT
    u1.email AS user_a,
    u2.email AS user_b,
    m.match_score,
    m.status,
    m.conversation_started,
    m.matched_at
FROM matches m
JOIN users u1 ON m.user_a_id = u1.id
JOIN users u2 ON m.user_b_id = u2.id
WHERE m.status = 'active'
ORDER BY m.matched_at DESC;
```

---

## Understanding the Data Flow

### Scenario: User Sets Up Profile

1. **User selects genres during onboarding**
   ```
   UserGenrePreference created:
   - User: john@example.com
   - Genre: indie-rock
   - Weight: 0.9
   - Source: manual_selection
   ```

2. **Later, Spotify data is synced** (Phase 2)
   ```
   Additional UserGenrePreferences created:
   - User: john@example.com
   - Genre: alternative-rock
   - Weight: 0.8
   - Source: spotify_derived
   ```

3. **User browses potential matches**
   ```
   System calculates match scores (Phase 3)
   Stores in UserMatchScore table
   ```

4. **User swipes on a profile**
   ```
   UserSwipe created:
   - Swiper: john@example.com
   - Swiped: jane@example.com
   - Action: like
   - MatchScore: 85.5
   ```

5. **If mutual like occurs**
   ```
   Match created:
   - UserA: jane@example.com
   - UserB: john@example.com
   - Status: active

   Both UserSwipes updated:
   - ResultedInMatch: true
   - MatchId: [match-id]
   ```

---

## Common Test Scenarios

### Scenario 1: Genre-Based Matching

**Setup**: Create 2 users with overlapping genres

```bash
# User 1 likes: indie-rock (0.9), electronic (0.7)
# User 2 likes: indie-rock (0.8), indie-pop (0.6)

# Common: indie-rock
# This would result in high music match score in Phase 3
```

### Scenario 2: Swipe Analytics

**Setup**: User swipes on multiple profiles

```bash
# User 1 likes 8 people, passes on 12 people
# Swipe-through rate: 40% (8/20)

# Of those 8 likes, 3 result in matches
# Match rate: 37.5% (3/8)
```

### Scenario 3: Mutual Matches

**Setup**: Test the mutual match detection

```bash
# User A likes User B → No match yet
# User B likes User A → Match created automatically
# Match entity links both users
# Both swipes marked as resulted_in_match
```

---

## Troubleshooting

### Issue: Genres Not Seeded

**Check**: Look for this log on startup:
```
Seeding canonical genres...
Successfully seeded 152 canonical genres
```

**Fix**: If you see "already exist", genres are there. If not, check:
```sql
SELECT COUNT(*) FROM canonical_genres;
```

If count is 0, restart the application.

### Issue: Cannot Create Genre Preference

**Error**: `Genre not found: indie-rock`

**Fix**: Check genre name spelling:
```bash
curl "http://localhost:8080/api/test/phase1/genres?search=indie"
```

Use exact `name` field (lowercase, hyphenated).

### Issue: Swipe Not Creating Match

**Check**: Did both users swipe right on each other?

```sql
-- Check both directions
SELECT * FROM user_swipes
WHERE (swiper_user_id = 'user1' AND swiped_user_id = 'user2')
   OR (swiper_user_id = 'user2' AND swiped_user_id = 'user1');
```

Both should have `action = 'like'` for mutual match.

---

## Next Steps

Once all tests pass:

✅ **Phase 1 Complete** - Database foundation is solid

**Ready for Phase 2**: Genre Extraction Service
- Automatically populate genres from Spotify
- Automatically populate genres from manual selections
- Migration script for existing users

---

## Quick Reference: API Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/test/phase1/stats` | GET | Overall statistics |
| `/api/test/phase1/genres` | GET | List/search genres |
| `/api/test/phase1/genres/hierarchy` | GET | Genre tree structure |
| `/api/test/phase1/users/{userId}/genres` | GET | User's genre preferences |
| `/api/test/phase1/users/{userId}/genres` | POST | Add genre preference |
| `/api/test/phase1/swipe` | POST | Record a swipe |
| `/api/test/phase1/users/{userId}/swipes` | GET | User's swipe history |
| `/api/test/phase1/users/{userId}/matches` | GET | User's matches |
| `/api/test/phase1/matches/check` | GET | Check if users matched |

---

**🎯 Goal**: Understand every component before moving to Phase 2!
