# Frontend Implementation Guide: Phase 2 - Genre Extraction & Preferences

## Overview

Phase 2 is the **Genre Preference System** - the first major user-facing feature of your dating app. This is where users either connect Spotify or manually select their music preferences.

**What users see:**
- Onboarding: "Connect Spotify" or "Select Genres Manually"
- Spotify OAuth flow (if they connect)
- Genre selection interface (if manual)
- Preference management dashboard
- Profile view showing their top genres

---

## What Was Built (Backend)

✅ **GenreExtractionService** - Extracts genres from Spotify data
✅ **GenreWeightCalculator** - Calculates intelligent weights
✅ **SpotifyGenreSyncService** - Syncs with Spotify API
✅ **Production API** - JWT-authenticated endpoints
✅ **Test API** - No-auth endpoints for development

---

## User Journey

### Journey 1: Spotify User (Recommended Path)

```
1. User signs up → Profile incomplete
2. App shows: "Connect your music taste"
3. User clicks "Connect Spotify"
4. OAuth popup → Spotify login
5. User authorizes app
6. Backend syncs top 50 tracks × 3 time ranges
7. GenreExtractionService calculates preferences
8. User sees: "Rock (85%), Jazz (72%), Electronic (45%)"
9. User can edit/add/remove genres
10. Profile complete ✓
```

### Journey 2: Non-Spotify User (Manual Path)

```
1. User signs up → Profile incomplete
2. App shows: "Connect your music taste"
3. User clicks "Select Genres Manually"
4. UI shows grid of 100+ genres
5. User selects 5-10 favorites
6. Optional: User drags to rank them
7. Backend saves with equal weights
8. User sees their selected genres
9. User can edit anytime
10. Profile complete ✓
```

### Journey 3: Hybrid User

```
1. User connects Spotify (Journey 1)
2. Preferences auto-populated
3. User manually adds "Classical" (not in Spotify)
4. User manually removes "Pop" (doesn't want to show it)
5. Profile has mix of Spotify + manual preferences
6. Re-syncing Spotify preserves manual additions
```

---

## API Endpoints

### Production Endpoints (JWT Required)

**Base URL:** `http://localhost:8080/api/v1/preferences`

All production endpoints require JWT authentication:
```http
Authorization: Bearer <JWT_TOKEN>
```

#### 1. Get User's Preferences

```http
GET /api/v1/preferences/genres?limit=20

Query Parameters:
- limit (optional): int - max number of preferences to return (default: 20)

Response:
{
  "total": 8,
  "preferences": [
    {
      "genreName": "rock",
      "genreDisplayName": "Rock",
      "weight": 0.85,
      "rank": 1,
      "confidence": 1.00,
      "source": "spotify_derived",
      "createdAt": "2025-12-29T10:00:00",
      "updatedAt": "2025-12-29T10:00:00"
    },
    {
      "genreName": "jazz",
      "genreDisplayName": "Jazz",
      "weight": 0.72,
      "rank": 2,
      "confidence": 0.95,
      "source": "spotify_derived",
      "createdAt": "2025-12-29T10:00:00",
      "updatedAt": "2025-12-29T10:00:00"
    }
  ]
}
```

#### 2. Sync from Spotify

```http
POST /api/v1/preferences/genres/sync?quick=false

Headers:
Authorization: Bearer <JWT_TOKEN>

Query Parameters:
- quick (optional): boolean - use quick sync (default: false)

Response:
{
  "success": true,
  "message": "Genre preferences synced successfully",
  "genreCount": 15
}

Error Response (if no Spotify connection):
{
  "error": "No Spotify account connected"
}
```

#### 3. Add Manual Preference

```http
POST /api/v1/preferences/genres

Headers:
Authorization: Bearer <JWT_TOKEN>
Content-Type: application/json

Body:
{
  "genreName": "classical",
  "weight": 0.9
}

Response:
{
  "success": true,
  "message": "Genre preference added",
  "preference": {
    "genreName": "classical",
    "genreDisplayName": "Classical",
    "weight": 0.90,
    "rank": 3,
    "confidence": 1.00,
    "source": "manual_selection",
    "createdAt": "2025-12-29T12:00:00",
    "updatedAt": "2025-12-29T12:00:00"
  }
}

Error Response (genre not found):
{
  "error": "Genre 'unknown-genre' not found in canonical genres"
}
```

#### 4. Remove Preference

```http
DELETE /api/v1/preferences/genres/{genreName}

Example:
DELETE /api/v1/preferences/genres/pop

Headers:
Authorization: Bearer <JWT_TOKEN>

Response:
{
  "success": true,
  "message": "Preference for 'pop' removed"
}
```

#### 5. Clear Spotify Preferences

```http
DELETE /api/v1/preferences/genres/spotify

Headers:
Authorization: Bearer <JWT_TOKEN>

Response:
{
  "success": true,
  "message": "Spotify preferences cleared"
}
```

---

### Test Endpoints (No Auth - Development Only)

**Base URL:** `http://localhost:8080/api/test/phase2`

⚠️ **Remove before production!**

#### 1. Extract from Mock Data

```http
POST /api/test/phase2/extract-mock?userId={userId}

Response:
{
  "userId": "user-uuid",
  "mockGenresCount": 17,
  "uniqueGenres": 8,
  "savedPreferences": 8,
  "preferences": [
    {
      "genre": "Rock",
      "weight": 0.85,
      "confidence": 1.0,
      "rank": 1,
      "source": "test_data"
    }
  ]
}
```

#### 2. Test Weight Calculator

```http
GET /api/test/phase2/calculate-weight?frequency=10&total=50

Response:
{
  "frequency": 10,
  "total": 50,
  "weight": 0.3937,
  "confidence": 0.95,
  "percentage": "20.0%"
}
```

#### 3. Add Manual Preference (Test)

```http
POST /api/test/phase2/add-manual?userId={userId}&genreName=rock&weight=0.9

Response:
{
  "success": true,
  "preference": {
    "genre": "Rock",
    "weight": 0.9,
    "confidence": 1.0,
    "source": "manual_selection"
  }
}
```

#### 4. Get Top Genres

```http
GET /api/test/phase2/top-genres?userId={userId}&limit=5

Response:
{
  "userId": "user-uuid",
  "totalPreferences": 8,
  "topGenres": [
    {
      "rank": 1,
      "genre": "Rock",
      "weight": 0.85,
      "confidence": 1.0,
      "source": "spotify_derived"
    },
    {
      "rank": 2,
      "genre": "Jazz",
      "weight": 0.72,
      "confidence": 0.95,
      "source": "spotify_derived"
    }
  ]
}
```

#### 5. Clear Preferences

```http
DELETE /api/test/phase2/clear?userId={userId}

Response:
{
  "success": true,
  "message": "Preferences cleared for user user-uuid"
}
```

#### 6. Test Spotify Sync (Requires Spotify Token)

```http
POST /api/test/phase2/sync-spotify?userId={userId}&quick=false

Response (Success):
{
  "success": true,
  "userId": "user-uuid",
  "genreCount": 12,
  "top10Genres": ["Rock", "Jazz", "Electronic", ...]
}

Response (Error - No Spotify):
{
  "error": "User does not have Spotify access token",
  "userId": "user-uuid"
}
```

---

## Component Architecture

### Page Structure

```
/onboarding
  └─ MusicPreferencesPage (main container)
      ├─ SpotifyConnectCard
      │   └─ SpotifyOAuthButton
      └─ ManualSelectionCard
          └─ GenreSelector

/profile
  └─ UserProfilePage
      ├─ ProfileHeader
      ├─ MusicPreferencesSection
      │   ├─ GenreChip (for each preference)
      │   └─ EditPreferencesButton
      └─ OtherProfileSections

/preferences/edit
  └─ EditPreferencesPage
      ├─ PreferenceList (current preferences)
      │   └─ PreferenceItem (editable)
      ├─ AddGenreButton
      └─ SyncSpotifyButton
```

---

## Component Implementations

### Component 1: Music Preferences Onboarding

**File:** `src/pages/onboarding/MusicPreferencesPage.jsx`

```jsx
import { useState } from 'react';
import SpotifyConnectCard from '../../components/onboarding/SpotifyConnectCard';
import ManualSelectionCard from '../../components/onboarding/ManualSelectionCard';

export default function MusicPreferencesPage() {
  const [selectedMethod, setSelectedMethod] = useState(null);

  if (selectedMethod === 'spotify') {
    return <SpotifyConnectCard onBack={() => setSelectedMethod(null)} />;
  }

  if (selectedMethod === 'manual') {
    return <ManualSelectionCard onBack={() => setSelectedMethod(null)} />;
  }

  return (
    <div className="music-preferences-onboarding">
      <div className="container">
        <h1>Let's discover your music taste 🎵</h1>
        <p className="subtitle">
          This helps us find people who vibe with your music style
        </p>

        <div className="method-cards">
          {/* Spotify Option (Recommended) */}
          <div
            className="method-card recommended"
            onClick={() => setSelectedMethod('spotify')}
          >
            <div className="badge">Recommended</div>
            <div className="spotify-icon">
              <img src="/icons/spotify.svg" alt="Spotify" />
            </div>
            <h2>Connect Spotify</h2>
            <p>Automatically analyze your listening history</p>
            <ul className="benefits">
              <li>✓ Most accurate preferences</li>
              <li>✓ Saves time</li>
              <li>✓ Updates with your taste</li>
            </ul>
            <button className="primary-button">
              Connect with Spotify
            </button>
          </div>

          {/* Manual Option */}
          <div
            className="method-card"
            onClick={() => setSelectedMethod('manual')}
          >
            <div className="manual-icon">🎸</div>
            <h2>Select Genres Manually</h2>
            <p>Choose from 100+ music genres</p>
            <ul className="benefits">
              <li>✓ No Spotify needed</li>
              <li>✓ Full control</li>
              <li>✓ Works great too</li>
            </ul>
            <button className="secondary-button">
              Select Manually
            </button>
          </div>
        </div>

        <p className="skip-text">
          <a href="/profile">Skip for now</a>
        </p>
      </div>
    </div>
  );
}
```

**Styling:** `src/pages/onboarding/MusicPreferencesPage.css`

```css
.music-preferences-onboarding {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  padding: 20px;
}

.container {
  max-width: 900px;
  text-align: center;
  color: white;
}

.container h1 {
  font-size: 42px;
  margin-bottom: 10px;
  font-weight: bold;
}

.subtitle {
  font-size: 18px;
  opacity: 0.9;
  margin-bottom: 40px;
}

.method-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(350px, 1fr));
  gap: 30px;
  margin: 40px 0;
}

.method-card {
  background: white;
  color: #333;
  border-radius: 16px;
  padding: 40px 30px;
  cursor: pointer;
  transition: transform 0.2s, box-shadow 0.2s;
  position: relative;
}

.method-card:hover {
  transform: translateY(-5px);
  box-shadow: 0 10px 30px rgba(0,0,0,0.2);
}

.method-card.recommended {
  border: 3px solid #1db954;
}

.badge {
  position: absolute;
  top: 15px;
  right: 15px;
  background: #1db954;
  color: white;
  padding: 5px 15px;
  border-radius: 20px;
  font-size: 12px;
  font-weight: bold;
}

.spotify-icon {
  width: 80px;
  height: 80px;
  margin: 0 auto 20px;
}

.spotify-icon img {
  width: 100%;
  height: 100%;
}

.manual-icon {
  font-size: 60px;
  margin-bottom: 20px;
}

.method-card h2 {
  font-size: 24px;
  margin-bottom: 10px;
}

.method-card p {
  color: #666;
  margin-bottom: 20px;
}

.benefits {
  list-style: none;
  padding: 0;
  margin: 20px 0;
  text-align: left;
}

.benefits li {
  padding: 8px 0;
  color: #555;
}

.primary-button {
  background: #1db954;
  color: white;
  border: none;
  padding: 15px 30px;
  border-radius: 30px;
  font-size: 16px;
  font-weight: bold;
  cursor: pointer;
  width: 100%;
  transition: background 0.2s;
}

.primary-button:hover {
  background: #1ed760;
}

.secondary-button {
  background: #667eea;
  color: white;
  border: none;
  padding: 15px 30px;
  border-radius: 30px;
  font-size: 16px;
  font-weight: bold;
  cursor: pointer;
  width: 100%;
  transition: background 0.2s;
}

.secondary-button:hover {
  background: #764ba2;
}

.skip-text {
  margin-top: 20px;
  opacity: 0.8;
}

.skip-text a {
  color: white;
  text-decoration: underline;
}
```

---

### Component 2: Spotify OAuth Integration

**File:** `src/components/onboarding/SpotifyConnectCard.jsx`

```jsx
import { useState } from 'react';
import { useAuth } from '../../hooks/useAuth'; // Your auth hook

export default function SpotifyConnectCard({ onBack }) {
  const [syncing, setSyncing] = useState(false);
  const [error, setError] = useState(null);
  const { user, token } = useAuth();

  const handleSpotifyConnect = () => {
    // Redirect to Spotify OAuth
    const clientId = import.meta.env.VITE_SPOTIFY_CLIENT_ID;
    const redirectUri = import.meta.env.VITE_SPOTIFY_REDIRECT_URI;
    const scopes = 'user-top-read user-read-recently-played';

    const authUrl = `https://accounts.spotify.com/authorize?` +
      `client_id=${clientId}` +
      `&response_type=code` +
      `&redirect_uri=${encodeURIComponent(redirectUri)}` +
      `&scope=${encodeURIComponent(scopes)}`;

    // Open in popup or redirect
    window.location.href = authUrl;
  };

  const handleSyncNow = async () => {
    setSyncing(true);
    setError(null);

    try {
      const response = await fetch(
        'http://localhost:8080/api/v1/preferences/genres/sync',
        {
          method: 'POST',
          headers: {
            'Authorization': `Bearer ${token}`,
            'Content-Type': 'application/json'
          }
        }
      );

      if (!response.ok) {
        throw new Error('Failed to sync with Spotify');
      }

      const data = await response.json();

      // Success! Navigate to profile
      window.location.href = '/profile';
    } catch (err) {
      setError(err.message);
    } finally {
      setSyncing(false);
    }
  };

  // Check if user already connected Spotify
  const isSpotifyConnected = user?.spotifyConnected;

  return (
    <div className="spotify-connect-card">
      <button className="back-button" onClick={onBack}>
        ← Back
      </button>

      <div className="card-content">
        <img src="/icons/spotify-large.svg" alt="Spotify" className="spotify-logo" />

        <h1>Connect Your Spotify</h1>
        <p>We'll analyze your listening history to understand your music taste</p>

        {!isSpotifyConnected ? (
          <>
            <div className="info-box">
              <h3>What we'll access:</h3>
              <ul>
                <li>Your top tracks (last 4 weeks, 6 months, all-time)</li>
                <li>Your recently played songs</li>
              </ul>
              <p className="privacy-note">
                We never see your playlists, followers, or private data
              </p>
            </div>

            <button
              className="spotify-auth-button"
              onClick={handleSpotifyConnect}
            >
              <img src="/icons/spotify-white.svg" alt="" />
              Connect Spotify Account
            </button>
          </>
        ) : (
          <>
            <div className="success-box">
              ✓ Spotify Connected
            </div>

            <button
              className="sync-button"
              onClick={handleSyncNow}
              disabled={syncing}
            >
              {syncing ? 'Syncing...' : 'Sync My Music Preferences'}
            </button>
          </>
        )}

        {error && (
          <div className="error-box">
            {error}
          </div>
        )}
      </div>
    </div>
  );
}
```

---

### Component 3: Manual Genre Selection

**File:** `src/components/onboarding/ManualSelectionCard.jsx`

```jsx
import { useState, useEffect } from 'react';
import { useAuth } from '../../hooks/useAuth';

export default function ManualSelectionCard({ onBack }) {
  const [genres, setGenres] = useState([]);
  const [selectedGenres, setSelectedGenres] = useState([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const { token } = useAuth();

  useEffect(() => {
    fetchGenres();
  }, []);

  const fetchGenres = async () => {
    try {
      const response = await fetch(
        'http://localhost:8080/api/test/phase1/genres?primaryOnly=true'
      );
      const data = await response.json();
      setGenres(data.genres || []);
    } catch (error) {
      console.error('Failed to fetch genres:', error);
    } finally {
      setLoading(false);
    }
  };

  const toggleGenre = (genreName) => {
    if (selectedGenres.includes(genreName)) {
      setSelectedGenres(selectedGenres.filter(g => g !== genreName));
    } else {
      if (selectedGenres.length < 15) {
        setSelectedGenres([...selectedGenres, genreName]);
      }
    }
  };

  const handleSave = async () => {
    if (selectedGenres.length < 3) {
      alert('Please select at least 3 genres');
      return;
    }

    setSaving(true);

    try {
      // Add each genre with equal weight
      const promises = selectedGenres.map(genreName =>
        fetch('http://localhost:8080/api/v1/preferences/genres', {
          method: 'POST',
          headers: {
            'Authorization': `Bearer ${token}`,
            'Content-Type': 'application/json'
          },
          body: JSON.stringify({
            genreName: genreName,
            weight: 1.0
          })
        })
      );

      await Promise.all(promises);

      // Success! Navigate to profile
      window.location.href = '/profile';
    } catch (error) {
      console.error('Failed to save preferences:', error);
      alert('Failed to save. Please try again.');
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return <div className="loading">Loading genres...</div>;
  }

  return (
    <div className="manual-selection-card">
      <button className="back-button" onClick={onBack}>
        ← Back
      </button>

      <div className="card-content">
        <h1>Select Your Favorite Genres</h1>
        <p>Choose 3-15 genres you enjoy listening to</p>

        <div className="selection-counter">
          {selectedGenres.length} / 15 selected
          {selectedGenres.length < 3 && (
            <span className="hint"> (minimum 3)</span>
          )}
        </div>

        <div className="genre-grid">
          {genres.map(genre => (
            <button
              key={genre.id}
              className={`genre-chip ${selectedGenres.includes(genre.name) ? 'selected' : ''}`}
              onClick={() => toggleGenre(genre.name)}
              disabled={!selectedGenres.includes(genre.name) && selectedGenres.length >= 15}
            >
              {genre.displayName}
              {selectedGenres.includes(genre.name) && (
                <span className="check">✓</span>
              )}
            </button>
          ))}
        </div>

        <button
          className="save-button"
          onClick={handleSave}
          disabled={selectedGenres.length < 3 || saving}
        >
          {saving ? 'Saving...' : `Save ${selectedGenres.length} Preferences`}
        </button>
      </div>
    </div>
  );
}
```

**Styling:** `src/components/onboarding/ManualSelectionCard.css`

```css
.manual-selection-card {
  min-height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  padding: 20px;
}

.card-content {
  max-width: 1000px;
  margin: 0 auto;
  background: white;
  border-radius: 20px;
  padding: 40px;
}

.card-content h1 {
  text-align: center;
  margin-bottom: 10px;
}

.card-content p {
  text-align: center;
  color: #666;
  margin-bottom: 30px;
}

.selection-counter {
  text-align: center;
  font-size: 18px;
  font-weight: bold;
  margin-bottom: 30px;
  color: #667eea;
}

.hint {
  color: #999;
  font-weight: normal;
  font-size: 14px;
}

.genre-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 15px;
  margin-bottom: 40px;
}

.genre-chip {
  padding: 15px 20px;
  border: 2px solid #ddd;
  border-radius: 25px;
  background: white;
  cursor: pointer;
  font-size: 16px;
  transition: all 0.2s;
  position: relative;
}

.genre-chip:hover {
  border-color: #667eea;
  transform: translateY(-2px);
}

.genre-chip.selected {
  background: #667eea;
  color: white;
  border-color: #667eea;
}

.genre-chip:disabled {
  opacity: 0.3;
  cursor: not-allowed;
}

.genre-chip .check {
  margin-left: 5px;
  font-weight: bold;
}

.save-button {
  width: 100%;
  padding: 18px;
  background: #1db954;
  color: white;
  border: none;
  border-radius: 30px;
  font-size: 18px;
  font-weight: bold;
  cursor: pointer;
  transition: background 0.2s;
}

.save-button:hover:not(:disabled) {
  background: #1ed760;
}

.save-button:disabled {
  background: #ccc;
  cursor: not-allowed;
}

.back-button {
  background: rgba(255,255,255,0.2);
  color: white;
  border: none;
  padding: 10px 20px;
  border-radius: 20px;
  cursor: pointer;
  margin-bottom: 20px;
  font-size: 16px;
}

.back-button:hover {
  background: rgba(255,255,255,0.3);
}
```

---

### Component 4: User Profile - Music Section

**File:** `src/components/profile/MusicPreferencesSection.jsx`

```jsx
import { useState, useEffect } from 'react';
import { useAuth } from '../../hooks/useAuth';
import { useNavigate } from 'react-router-dom';

export default function MusicPreferencesSection() {
  const [preferences, setPreferences] = useState([]);
  const [loading, setLoading] = useState(true);
  const { token } = useAuth();
  const navigate = useNavigate();

  useEffect(() => {
    fetchPreferences();
  }, []);

  const fetchPreferences = async () => {
    try {
      const response = await fetch(
        'http://localhost:8080/api/v1/preferences/genres?limit=20',
        {
          headers: {
            'Authorization': `Bearer ${token}`
          }
        }
      );
      const data = await response.json();
      setPreferences(data.preferences || []);
    } catch (error) {
      console.error('Failed to fetch preferences:', error);
    } finally {
      setLoading(false);
    }
  };

  if (loading) {
    return <div className="loading">Loading preferences...</div>;
  }

  if (preferences.length === 0) {
    return (
      <div className="empty-preferences">
        <h3>No music preferences yet</h3>
        <p>Add your music taste to find better matches</p>
        <button onClick={() => navigate('/onboarding/music')}>
          Add Music Preferences
        </button>
      </div>
    );
  }

  return (
    <div className="music-preferences-section">
      <div className="section-header">
        <h2>Music Taste 🎵</h2>
        <button
          className="edit-button"
          onClick={() => navigate('/preferences/edit')}
        >
          Edit
        </button>
      </div>

      <div className="top-genres">
        <h3>Top Genres</h3>
        <div className="genre-chips">
          {preferences.slice(0, 5).map((pref, index) => (
            <div key={pref.id} className="genre-chip-display">
              <span className="rank">#{index + 1}</span>
              <span className="genre-name">{pref.genreDisplayName}</span>
              <span className="weight">{Math.round(pref.weight * 100)}%</span>
            </div>
          ))}
        </div>
      </div>

      {preferences.length > 5 && (
        <div className="other-genres">
          <h3>Also Enjoys</h3>
          <div className="genre-tags">
            {preferences.slice(5, 15).map(pref => (
              <span key={pref.id} className="genre-tag">
                {pref.genreDisplayName}
              </span>
            ))}
          </div>
        </div>
      )}

      <div className="preference-source">
        <small>
          {preferences[0]?.source === 'spotify_derived' ? (
            <>📊 From Spotify listening history</>
          ) : (
            <>✏️ Manually selected</>
          )}
        </small>
      </div>
    </div>
  );
}
```

**Styling:** `src/components/profile/MusicPreferencesSection.css`

```css
.music-preferences-section {
  background: white;
  border-radius: 12px;
  padding: 25px;
  margin-bottom: 20px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.1);
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.section-header h2 {
  margin: 0;
  font-size: 24px;
}

.edit-button {
  background: #667eea;
  color: white;
  border: none;
  padding: 8px 20px;
  border-radius: 20px;
  cursor: pointer;
  font-size: 14px;
}

.edit-button:hover {
  background: #764ba2;
}

.top-genres h3 {
  font-size: 16px;
  color: #666;
  margin-bottom: 15px;
}

.genre-chips {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.genre-chip-display {
  display: flex;
  align-items: center;
  gap: 15px;
  padding: 12px;
  background: #f5f5f5;
  border-radius: 8px;
}

.rank {
  font-weight: bold;
  color: #667eea;
  min-width: 30px;
}

.genre-name {
  flex: 1;
  font-size: 16px;
}

.weight {
  font-weight: bold;
  color: #1db954;
  font-size: 14px;
}

.other-genres {
  margin-top: 25px;
}

.other-genres h3 {
  font-size: 16px;
  color: #666;
  margin-bottom: 15px;
}

.genre-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.genre-tag {
  background: #e8eaf6;
  color: #667eea;
  padding: 6px 15px;
  border-radius: 15px;
  font-size: 14px;
}

.preference-source {
  margin-top: 20px;
  padding-top: 15px;
  border-top: 1px solid #eee;
  text-align: center;
}

.preference-source small {
  color: #999;
  font-size: 13px;
}

.empty-preferences {
  text-align: center;
  padding: 40px 20px;
  background: #f9f9f9;
  border-radius: 12px;
}

.empty-preferences h3 {
  margin-bottom: 10px;
  color: #333;
}

.empty-preferences p {
  color: #666;
  margin-bottom: 20px;
}

.empty-preferences button {
  background: #1db954;
  color: white;
  border: none;
  padding: 12px 30px;
  border-radius: 25px;
  cursor: pointer;
  font-size: 16px;
}

.empty-preferences button:hover {
  background: #1ed760;
}
```

---

## State Management

For Phase 2, you'll need centralized state for:

### Option 1: React Context (Simple)

```jsx
// src/contexts/PreferencesContext.jsx
import { createContext, useContext, useState, useEffect } from 'react';
import { useAuth } from '../hooks/useAuth';

const PreferencesContext = createContext();

export function PreferencesProvider({ children }) {
  const [preferences, setPreferences] = useState([]);
  const [loading, setLoading] = useState(true);
  const { token } = useAuth();

  const fetchPreferences = async () => {
    if (!token) return;

    try {
      const response = await fetch(
        'http://localhost:8080/api/v1/preferences/genres',
        { headers: { Authorization: `Bearer ${token}` } }
      );
      const data = await response.json();
      setPreferences(data.preferences || []);
    } catch (error) {
      console.error('Failed to fetch preferences:', error);
    } finally {
      setLoading(false);
    }
  };

  const addPreference = async (genreName, weight = 1.0) => {
    const response = await fetch(
      'http://localhost:8080/api/v1/preferences/genres',
      {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${token}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({ genreName, weight })
      }
    );

    if (response.ok) {
      await fetchPreferences(); // Refresh
    }
  };

  const removePreference = async (genreName) => {
    const response = await fetch(
      `http://localhost:8080/api/v1/preferences/genres/${genreName}`,
      {
        method: 'DELETE',
        headers: { Authorization: `Bearer ${token}` }
      }
    );

    if (response.ok) {
      await fetchPreferences(); // Refresh
    }
  };

  const syncSpotify = async () => {
    const response = await fetch(
      'http://localhost:8080/api/v1/preferences/genres/sync',
      {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}` }
      }
    );

    if (response.ok) {
      await fetchPreferences(); // Refresh
    }
  };

  useEffect(() => {
    fetchPreferences();
  }, [token]);

  return (
    <PreferencesContext.Provider value={{
      preferences,
      loading,
      fetchPreferences,
      addPreference,
      removePreference,
      syncSpotify
    }}>
      {children}
    </PreferencesContext.Provider>
  );
}

export const usePreferences = () => useContext(PreferencesContext);
```

### Option 2: Zustand (Recommended)

```bash
npm install zustand
```

```jsx
// src/stores/preferencesStore.js
import { create } from 'zustand';

export const usePreferencesStore = create((set, get) => ({
  preferences: [],
  loading: false,
  error: null,

  fetchPreferences: async (token) => {
    set({ loading: true, error: null });
    try {
      const response = await fetch(
        'http://localhost:8080/api/v1/preferences/genres?limit=20',
        { headers: { Authorization: `Bearer ${token}` } }
      );
      const data = await response.json();
      set({ preferences: data.preferences || [], loading: false });
    } catch (error) {
      set({ error: error.message, loading: false });
    }
  },

  addPreference: async (token, genreName, weight = 1.0) => {
    try {
      const response = await fetch(
        'http://localhost:8080/api/v1/preferences/genres',
        {
          method: 'POST',
          headers: {
            Authorization: `Bearer ${token}`,
            'Content-Type': 'application/json'
          },
          body: JSON.stringify({ genreName, weight })
        }
      );
      if (response.ok) {
        await get().fetchPreferences(token);
      }
    } catch (error) {
      set({ error: error.message });
    }
  },

  removePreference: async (token, genreName) => {
    try {
      const response = await fetch(
        `http://localhost:8080/api/v1/preferences/genres/${genreName}`,
        {
          method: 'DELETE',
          headers: { Authorization: `Bearer ${token}` }
        }
      );
      if (response.ok) {
        await get().fetchPreferences(token);
      }
    } catch (error) {
      set({ error: error.message });
    }
  },

  syncSpotify: async (token) => {
    set({ loading: true });
    try {
      const response = await fetch(
        'http://localhost:8080/api/v1/preferences/genres/sync',
        {
          method: 'POST',
          headers: { Authorization: `Bearer ${token}` }
        }
      );
      if (response.ok) {
        await get().fetchPreferences(token);
      }
    } catch (error) {
      set({ error: error.message, loading: false });
    }
  }
}));
```

---

## Spotify OAuth Configuration

### Environment Variables

Create `.env` file:

```env
VITE_SPOTIFY_CLIENT_ID=your_spotify_client_id
VITE_SPOTIFY_CLIENT_SECRET=your_spotify_client_secret
VITE_SPOTIFY_REDIRECT_URI=http://localhost:3000/auth/spotify/callback
VITE_API_BASE_URL=http://localhost:8080
```

### Spotify OAuth Callback Handler

**File:** `src/pages/auth/SpotifyCallback.jsx`

```jsx
import { useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';

export default function SpotifyCallback() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const { token } = useAuth();

  useEffect(() => {
    const code = searchParams.get('code');
    const error = searchParams.get('error');

    if (error) {
      console.error('Spotify auth error:', error);
      navigate('/onboarding/music?error=spotify_auth_failed');
      return;
    }

    if (code) {
      exchangeCodeForToken(code);
    }
  }, [searchParams]);

  const exchangeCodeForToken = async (code) => {
    try {
      // Send code to your backend
      const response = await fetch(
        'http://localhost:8080/api/auth/spotify/callback',
        {
          method: 'POST',
          headers: {
            'Authorization': `Bearer ${token}`,
            'Content-Type': 'application/json'
          },
          body: JSON.stringify({ code })
        }
      );

      if (response.ok) {
        // Backend handles storing Spotify tokens
        // Now sync preferences
        navigate('/onboarding/music?spotify_connected=true');
      } else {
        throw new Error('Failed to connect Spotify');
      }
    } catch (error) {
      console.error('Failed to exchange code:', error);
      navigate('/onboarding/music?error=connection_failed');
    }
  };

  return (
    <div className="callback-loading">
      <h2>Connecting to Spotify...</h2>
      <div className="spinner"></div>
    </div>
  );
}
```

---

## Testing Checklist

### Manual Testing

✅ **Onboarding Flow - Spotify**
- [ ] "Connect Spotify" button redirects to Spotify
- [ ] After auth, callback handles code exchange
- [ ] Sync button triggers preference extraction
- [ ] Preferences appear on profile
- [ ] Top 5 genres displayed with percentages

✅ **Onboarding Flow - Manual**
- [ ] Genre grid loads all primary genres
- [ ] Selection/deselection works
- [ ] Max 15 genres enforced
- [ ] Min 3 genres enforced
- [ ] Save button creates preferences
- [ ] Redirect to profile after save

✅ **Profile Display**
- [ ] Top 5 genres shown with rankings
- [ ] Additional genres shown as tags
- [ ] Source indicator correct (Spotify vs Manual)
- [ ] Edit button navigates to edit page

✅ **Edit Preferences**
- [ ] Can add new genres
- [ ] Can remove genres
- [ ] Can re-sync Spotify
- [ ] Manual additions survive Spotify re-sync
- [ ] Changes reflected immediately

### API Testing

```bash
# Test 1: Get preferences (requires JWT)
curl -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  http://localhost:8080/api/v1/preferences/genres

# Test 2: Add manual preference (requires JWT)
curl -X POST \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"genreName":"rock","weight":0.9}' \
  http://localhost:8080/api/v1/preferences/genres

# Test 3: Remove preference (requires JWT)
curl -X DELETE \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  http://localhost:8080/api/v1/preferences/genres/rock

# Test 4: Sync Spotify (requires JWT + Spotify connection)
curl -X POST \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  http://localhost:8080/api/v1/preferences/genres/sync
```

---

## Responsive Design

All components should be mobile-first:

```css
/* Mobile first (default) */
.genre-grid {
  grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
}

/* Tablet */
@media (min-width: 768px) {
  .genre-grid {
    grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  }
}

/* Desktop */
@media (min-width: 1024px) {
  .genre-grid {
    grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  }
}
```

---

## Accessibility

Ensure WCAG 2.1 AA compliance:

```jsx
// Good: Accessible genre chip
<button
  className="genre-chip"
  onClick={() => toggleGenre(genre.name)}
  aria-pressed={selectedGenres.includes(genre.name)}
  aria-label={`Select ${genre.displayName} genre`}
>
  {genre.displayName}
  {selectedGenres.includes(genre.name) && (
    <span aria-hidden="true">✓</span>
  )}
</button>

// Good: Screen reader announcements
<div
  role="status"
  aria-live="polite"
  className="sr-only"
>
  {selectedGenres.length} genres selected
</div>
```

---

## Error Handling

Handle all error states gracefully:

```jsx
const [error, setError] = useState(null);

// Display errors
{error && (
  <div className="error-banner" role="alert">
    <strong>Error:</strong> {error}
    <button onClick={() => setError(null)}>Dismiss</button>
  </div>
)}

// Network errors
try {
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}: ${response.statusText}`);
  }
} catch (error) {
  if (error.message.includes('Failed to fetch')) {
    setError('Network error. Please check your connection.');
  } else {
    setError(error.message);
  }
}
```

---

## Performance Optimization

### Lazy Loading

```jsx
import { lazy, Suspense } from 'react';

const ManualSelectionCard = lazy(() =>
  import('./components/onboarding/ManualSelectionCard')
);

function App() {
  return (
    <Suspense fallback={<div>Loading...</div>}>
      <ManualSelectionCard />
    </Suspense>
  );
}
```

### Memoization

```jsx
import { useMemo } from 'react';

const sortedPreferences = useMemo(() => {
  return preferences.sort((a, b) => b.weight - a.weight);
}, [preferences]);
```

---

## Summary: What to Build

### Required Components

1. ✅ **MusicPreferencesPage** - Onboarding entry point
2. ✅ **SpotifyConnectCard** - Spotify OAuth flow
3. ✅ **ManualSelectionCard** - Manual genre picker
4. ✅ **MusicPreferencesSection** - Profile display
5. ✅ **EditPreferencesPage** - Manage preferences

### Required State Management

- ✅ Preferences store (Zustand or Context)
- ✅ Auth integration
- ✅ Error handling

### Required API Integration

- ✅ GET preferences
- ✅ POST add preference
- ✅ DELETE remove preference
- ✅ POST sync Spotify

### Optional Enhancements

- Drag-to-rank genre ordering
- Weight sliders for manual preferences
- Genre recommendations
- Spotify playlist preview
- Animated transitions

---

## Next Steps

After completing Phase 2 frontend:

1. ✅ Test onboarding flow (both paths)
2. ✅ Verify Spotify OAuth works
3. ✅ Test preference management
4. ✅ Move to **Phase 3** - Matching Algorithm UI

Phase 2 is the foundation for matching - users can't match without preferences! 🎵💑
