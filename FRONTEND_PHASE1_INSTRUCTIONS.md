# Frontend Implementation Guide: Phase 1 - Database Foundation

## Overview

Phase 1 is the **database foundation** layer. It doesn't have primary user-facing features, but you'll need **admin/testing interfaces** to verify the system is working correctly.

**What users see:** Nothing directly - this is backend infrastructure
**What admins/developers see:** Testing dashboards to verify data integrity

---

## What Was Built (Backend)

✅ 5 Database tables:
- `canonical_genres` - Master genre list (100+ genres)
- `user_genre_preferences` - User music preferences
- `user_swipes` - Swipe history (like/pass)
- `matches` - Mutual matches between users
- `user_match_scores` - Pre-calculated match scores

✅ 5 Repositories with custom queries
✅ Genre seed data (automatically loaded on startup)
✅ Test endpoints for manual testing

---

## Frontend Components Needed

### 1. Admin Dashboard (Optional but Recommended)

**Purpose:** Verify Phase 1 is working correctly

**Route:** `/admin/phase1`

**Features:**
- Display genre statistics
- View genre hierarchy
- Search genres
- Test genre relationships

---

## API Endpoints Available

### Base URL
```
http://localhost:8080/api/test/phase1
```

**⚠️ WARNING:** These are TEST endpoints with NO AUTHENTICATION. Remove before production!

### 1. Get All Genres

```http
GET /api/test/phase1/genres?primaryOnly=true&search=rock

Query Parameters:
- primaryOnly (optional): boolean - only show primary genres
- search (optional): string - fuzzy search by name

Response:
{
  "total": 2,
  "genres": [
    {
      "id": "genre-uuid",
      "name": "rock",
      "displayName": "Rock",
      "isPrimary": true,
      "parentGenre": null
    },
    {
      "id": "genre-uuid-2",
      "name": "indie-rock",
      "displayName": "Indie Rock",
      "isPrimary": false,
      "parentGenre": "Rock"
    }
  ]
}
```

### 2. Get Genre by ID

```http
GET /api/test/phase1/genres/{genreId}

Response:
{
  "genre": {
    "id": "genre-uuid",
    "name": "rock",
    "displayName": "Rock",
    "isPrimary": true,
    "parentGenre": null
  },
  "children": [
    {
      "id": "child-uuid-1",
      "name": "indie-rock",
      "displayName": "Indie Rock",
      "isPrimary": false,
      "parentGenre": "Rock"
    },
    {
      "id": "child-uuid-2",
      "name": "alternative-rock",
      "displayName": "Alternative Rock",
      "isPrimary": false,
      "parentGenre": "Rock"
    }
  ],
  "childCount": 2
}
```

### 3. Get Genre Hierarchy

```http
GET /api/test/phase1/genres/hierarchy

Response:
{
  "topLevelCount": 15,
  "hierarchy": [
    {
      "id": "rock-uuid",
      "name": "rock",
      "displayName": "Rock",
      "isPrimary": true,
      "parentGenre": null,
      "children": [
        {
          "id": "indie-rock-uuid",
          "name": "indie-rock",
          "displayName": "Indie Rock",
          "isPrimary": false,
          "parentGenre": "Rock"
        }
      ]
    }
  ]
}
```

### 4. Add User Genre Preference (Testing)

```http
POST /api/test/phase1/users/{userId}/genres

Body:
{
  "genreName": "rock",
  "weight": 0.8,
  "source": "manual_selection"
}

Response:
{
  "message": "Created",
  "preference": {
    "id": "pref-uuid",
    "genre": {
      "id": "genre-uuid",
      "name": "rock",
      "displayName": "Rock",
      "isPrimary": true,
      "parentGenre": null
    },
    "weight": 0.8,
    "source": "manual_selection",
    "confidence": 1.0,
    "rank": 1
  }
}
```

### 5. Get User's Genre Preferences

```http
GET /api/test/phase1/users/{userId}/genres

Response:
{
  "userId": "user-uuid",
  "totalGenres": 5,
  "preferences": [
    {
      "id": "pref-uuid",
      "genre": {
        "id": "genre-uuid",
        "name": "rock",
        "displayName": "Rock",
        "isPrimary": true,
        "parentGenre": null
      },
      "weight": 0.85,
      "source": "spotify_derived",
      "confidence": 1.0,
      "rank": 1
    }
  ]
}
```

### 6. Delete User Genre Preference

```http
DELETE /api/test/phase1/users/{userId}/genres/{genreId}

Response:
{
  "message": "Preference deleted successfully"
}
```

### 7. Get User's Swipes

```http
GET /api/test/phase1/users/{userId}/swipes

Response:
{
  "userId": "user-uuid",
  "totalSwipes": 25,
  "likes": 15,
  "passes": 10,
  "swipeThroughRate": 60.0,
  "matchRate": 33.3,
  "swipes": [
    {
      "id": "swipe-uuid",
      "action": "like",
      "swipedAt": "2025-12-30T12:00:00",
      "matchScore": 85.5,
      "resultedInMatch": true
    }
  ]
}
```

### 8. Record a Swipe

```http
POST /api/test/phase1/swipe

Body:
{
  "swiperId": "user1-uuid",
  "swipedId": "user2-uuid",
  "action": "like",
  "score": 85.5
}

Response:
{
  "swipe": {
    "id": "swipe-uuid",
    "action": "like",
    "swipedAt": "2025-12-30T12:00:00",
    "matchScore": 85.5,
    "resultedInMatch": true
  },
  "mutualMatch": true,
  "match": {
    "id": "match-uuid",
    "matchedAt": "2025-12-30T12:00:00",
    "matchScore": 85.5,
    "status": "active",
    "conversationStarted": false,
    "matchSource": "mutual_swipe"
  }
}
```

### 9. Get User's Matches

```http
GET /api/test/phase1/users/{userId}/matches

Response:
{
  "userId": "user-uuid",
  "totalMatches": 5,
  "withConversation": 2,
  "withoutConversation": 3,
  "matches": [
    {
      "id": "match-uuid",
      "matchedAt": "2025-12-30T12:00:00",
      "matchScore": 85.5,
      "status": "active",
      "conversationStarted": false,
      "matchSource": "mutual_swipe"
    }
  ]
}
```

### 10. Check if Two Users are Matched

```http
GET /api/test/phase1/matches/check?user1Id={id1}&user2Id={id2}

Response:
{
  "user1Id": "user1-uuid",
  "user2Id": "user2-uuid",
  "matched": true,
  "match": {
    "id": "match-uuid",
    "matchedAt": "2025-12-30T12:00:00",
    "matchScore": 85.5,
    "status": "active",
    "conversationStarted": false,
    "matchSource": "mutual_swipe"
  }
}
```

### 11. Get Phase 1 Statistics

```http
GET /api/test/phase1/stats

Response:
{
  "genres": {
    "total": 115,
    "primary": 45,
    "topLevel": 15
  },
  "users": {
    "totalUsers": 10,
    "usersWithGenrePreferences": 8
  },
  "swipes": {
    "total": 150
  },
  "matches": {
    "total": 25
  }
}
```

---

## Component Implementation

### Component 1: Genre Browser (Admin Tool)

**File:** `src/components/admin/GenreBrowser.jsx` (or `.tsx`)

**Purpose:** Browse and search the genre database

```jsx
import { useState, useEffect } from 'react';

export default function GenreBrowser() {
  const [genres, setGenres] = useState([]);
  const [search, setSearch] = useState('');
  const [primaryOnly, setPrimaryOnly] = useState(true);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    fetchGenres();
  }, [search, primaryOnly]);

  const fetchGenres = async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams();
      if (primaryOnly) params.append('primaryOnly', 'true');
      if (search) params.append('search', search);

      const response = await fetch(
        `http://localhost:8080/api/test/phase1/genres?${params}`
      );
      const data = await response.json();
      setGenres(data.genres || []);
    } catch (error) {
      console.error('Failed to fetch genres:', error);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="genre-browser">
      <h1>Genre Database</h1>

      {/* Search and Filters */}
      <div className="controls">
        <input
          type="text"
          placeholder="Search genres..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="search-input"
        />

        <label>
          <input
            type="checkbox"
            checked={primaryOnly}
            onChange={(e) => setPrimaryOnly(e.target.checked)}
          />
          Primary genres only
        </label>
      </div>

      {/* Genre List */}
      {loading ? (
        <div>Loading...</div>
      ) : (
        <div className="genre-list">
          <p>Found {genres.length} genres</p>
          {genres.map((genre) => (
            <div key={genre.id} className="genre-card">
              <h3>{genre.displayName}</h3>
              <p>
                <strong>Name:</strong> {genre.name}
              </p>
              <p>
                <strong>Primary:</strong> {genre.isPrimary ? 'Yes' : 'No'}
              </p>
              {genre.parentGenre && (
                <p>
                  <strong>Parent:</strong> {genre.parentGenre}
                </p>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
```

**Styling:** `src/components/admin/GenreBrowser.css`

```css
.genre-browser {
  max-width: 1200px;
  margin: 0 auto;
  padding: 20px;
}

.controls {
  display: flex;
  gap: 20px;
  align-items: center;
  margin-bottom: 20px;
}

.search-input {
  flex: 1;
  padding: 10px;
  font-size: 16px;
  border: 1px solid #ddd;
  border-radius: 4px;
}

.genre-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 20px;
}

.genre-card {
  border: 1px solid #ddd;
  border-radius: 8px;
  padding: 15px;
  background: white;
}

.genre-card h3 {
  margin-top: 0;
  color: #1db954; /* Spotify green */
}

.genre-card p {
  margin: 5px 0;
  font-size: 14px;
}
```

---

### Component 2: Phase 1 Statistics Dashboard

**File:** `src/components/admin/Phase1Dashboard.jsx`

**Purpose:** Show overall statistics about Phase 1 data

```jsx
import { useState, useEffect } from 'react';

export default function Phase1Dashboard() {
  const [stats, setStats] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetchStats();
  }, []);

  const fetchStats = async () => {
    try {
      const response = await fetch('http://localhost:8080/api/test/phase1/stats');
      const data = await response.json();
      setStats(data);
    } catch (error) {
      console.error('Failed to fetch stats:', error);
    } finally {
      setLoading(false);
    }
  };

  if (loading) {
    return <div>Loading statistics...</div>;
  }

  return (
    <div className="dashboard">
      <h1>Phase 1: Database Foundation</h1>
      <p>System statistics and health check</p>

      <div className="stats-grid">
        <div className="stat-card">
          <h2>{stats?.genres?.total || 0}</h2>
          <p>Total Genres</p>
        </div>

        <div className="stat-card">
          <h2>{stats?.genres?.primary || 0}</h2>
          <p>Primary Genres</p>
          <small>Shown in UI</small>
        </div>

        <div className="stat-card">
          <h2>{stats?.genres?.topLevel || 0}</h2>
          <p>Top-Level Genres</p>
          <small>No parent</small>
        </div>

        <div className="stat-card">
          <h2>{stats?.users?.usersWithGenrePreferences || 0}</h2>
          <p>Users with Preferences</p>
        </div>

        <div className="stat-card">
          <h2>{stats?.swipes?.total || 0}</h2>
          <p>Total Swipes</p>
        </div>

        <div className="stat-card">
          <h2>{stats?.matches?.total || 0}</h2>
          <p>Mutual Matches</p>
        </div>
      </div>

      <div className="status-indicator">
        <h3>✅ Phase 1 Status: Operational</h3>
        <ul>
          <li>✓ Genre database seeded</li>
          <li>✓ Repositories functioning</li>
          <li>✓ All tables created</li>
        </ul>
      </div>
    </div>
  );
}
```

**Styling:** `src/components/admin/Phase1Dashboard.css`

```css
.dashboard {
  max-width: 1200px;
  margin: 0 auto;
  padding: 20px;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 20px;
  margin: 30px 0;
}

.stat-card {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  padding: 30px;
  border-radius: 12px;
  text-align: center;
  box-shadow: 0 4px 6px rgba(0,0,0,0.1);
}

.stat-card h2 {
  font-size: 48px;
  margin: 0 0 10px 0;
  font-weight: bold;
}

.stat-card p {
  font-size: 18px;
  margin: 0;
  opacity: 0.9;
}

.stat-card small {
  font-size: 12px;
  opacity: 0.7;
  display: block;
  margin-top: 5px;
}

.status-indicator {
  background: #f0f9ff;
  border: 2px solid #1db954;
  border-radius: 8px;
  padding: 20px;
  margin-top: 30px;
}

.status-indicator h3 {
  color: #1db954;
  margin-top: 0;
}

.status-indicator ul {
  list-style: none;
  padding: 0;
}

.status-indicator li {
  padding: 5px 0;
  font-size: 16px;
}
```

---

## User Flows

### Flow 1: Admin Checks Genre Database

```
1. Admin navigates to /admin/phase1
2. Dashboard loads, shows statistics
3. Admin clicks "Browse Genres"
4. Genre browser opens
5. Admin searches for "rock"
6. System shows: Rock, Indie Rock, Alternative Rock, etc.
7. Admin verifies genres are loaded correctly ✓
```

---

## State Management

For Phase 1 (admin tools), **simple React state is sufficient**:

```jsx
// Simple component state
const [genres, setGenres] = useState([]);
const [loading, setLoading] = useState(false);
const [error, setError] = useState(null);
```

No need for Redux/Zustand for admin-only features.

---

## Testing Checklist

### Manual Testing

✅ **Genre Browser**
- [ ] Search returns correct results
- [ ] Primary filter works
- [ ] Genre hierarchy displays correctly
- [ ] Parent-child relationships shown

✅ **Dashboard**
- [ ] Statistics load correctly
- [ ] All numbers are accurate
- [ ] Status indicator shows "Operational"

### API Testing

```bash
# Test 1: Get all genres
curl http://localhost:8080/api/test/phase1/genres

# Test 2: Search for "rock"
curl "http://localhost:8080/api/test/phase1/genres?search=rock"

# Test 3: Get primary genres only
curl "http://localhost:8080/api/test/phase1/genres?primaryOnly=true"

# Test 4: Get genre hierarchy
curl http://localhost:8080/api/test/phase1/genres/hierarchy

# Test 5: Get Phase 1 stats
curl http://localhost:8080/api/test/phase1/stats
```

---

## Deployment Notes

### Development
```bash
# Backend runs on
http://localhost:8080

# Frontend should run on
http://localhost:3000  # React
http://localhost:5173  # Vite
```

### Production Considerations

⚠️ **IMPORTANT:** Phase 1 test endpoints should be **REMOVED** or **SECURED** before production:

```java
// Option 1: Delete Phase1TestController.java

// Option 2: Add authentication
@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/admin/phase1")
public class Phase1AdminController {
    // Secured admin endpoints
}
```

---

## Summary: What to Build

### Required (Admin Tools)
1. ✅ **Genre Browser** - Browse/search genre database
2. ✅ **Phase 1 Dashboard** - Statistics overview

### Optional (Nice to Have)
- Genre hierarchy tree view
- Genre relationship visualizer
- Database health checks
- Test data generators

### Not Needed
- User-facing UI (Phase 1 is backend only)
- Authentication for test endpoints (dev only)
- Complex state management

---

## Next Steps

After completing Phase 1 frontend:

1. ✅ Verify genre database is loaded (100+ genres)
2. ✅ Test search and filtering
3. ✅ Move to **Phase 2** frontend (actual user features!)

Phase 1 is infrastructure - **Phase 2 is where the real UI work begins!** 🚀
