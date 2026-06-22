# Phase 2: Genre Extraction Service

## Overview

Phase 2 builds on Phase 1's database foundation by automatically extracting and managing user music genre preferences from Spotify listening data.

## What's New

### Services Created

1. **GenreExtractionService** - Core service for extracting and managing genre preferences
2. **GenreWeightCalculator** - Calculates weights and confidence scores
3. **SpotifyGenreSyncService** - Syncs Spotify data to user preferences

### API Endpoints

#### Production Endpoints (`/api/v1/preferences/genres`)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/` | Get current user's genre preferences |
| POST | `/sync` | Sync preferences from Spotify (requires auth) |
| POST | `/` | Add manual genre preference |
| DELETE | `/{genreName}` | Remove a genre preference |
| DELETE | `/spotify` | Clear all Spotify-derived preferences |

#### Test Endpoints (`/api/test/phase2`)

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/extract-mock` | Test extraction with mock data |
| GET | `/calculate-weight` | Test weight calculator |
| POST | `/add-manual` | Add manual preference (testing) |
| GET | `/top-genres` | Get top genres for a user |
| DELETE | `/clear` | Clear user preferences |
| POST | `/sync-spotify` | Test Spotify sync |
| GET | `/stats` | Get Phase 2 statistics |

---

## How It Works

### 1. Genre Extraction Flow

```
Spotify API → getGenresFromTopArtists() → List<String> genres
                                              ↓
                                    SpotifyGenreSyncService
                                              ↓
                                    GenreExtractionService
                                              ↓
                           findMatchingCanonicalGenres()
                                              ↓
                           calculateWeight() & calculateConfidence()
                                              ↓
                                 UserGenrePreference (saved)
```

### 2. Genre Matching Strategy

The system uses a 3-tier matching strategy:

1. **Exact Match**: Direct match with canonical genre name
2. **Spotify Alias Match**: Matches via spotifyAliases field
3. **Fuzzy Search**: Searches by normalized name

Example:
- Spotify genre: `"hip hop"` → Matches canonical genre `"hip-hop"` via alias
- Spotify genre: `"indie"` → Fuzzy matches `"indie-rock"`, `"indie-pop"`, etc.

### 3. Weight Calculation

Weights are calculated using:
- **Frequency**: How often the genre appears
- **Logarithmic Scaling**: Prevents over-weighting frequent genres
- **Time Range**: Recent data weighted higher than old data

Formula:
```
weight = (0.7 * log_scaled) + (0.3 * raw_ratio)
where:
  log_scaled = log(1 + frequency) / log(1 + total)
  raw_ratio = frequency / total
```

### 4. Confidence Scoring

Confidence indicates how certain we are about the match:

| Scenario | Confidence |
|----------|-----------|
| Exact match, frequent | 1.0 |
| Exact match, single occurrence | 0.7 |
| Ambiguous (multiple matches) | 1/matchCount |
| Manual selection | 1.0 |

### 5. Time Range Weighting

Spotify sync fetches data from 3 time ranges:

| Time Range | Period | Weight Multiplier |
|------------|--------|-------------------|
| short_term | Last 4 weeks | 3x |
| medium_term | Last 6 months | 2x |
| long_term | Several years | 1x |

This ensures recent listening habits have more influence than old data.

---

## Usage Examples

### Manual Testing (via `/api/test/phase2`)

#### 1. Test with Mock Data

```bash
curl -X POST "http://localhost:8080/api/test/phase2/extract-mock?userId=YOUR_USER_ID"
```

Response:
```json
{
  "userId": "abc123",
  "mockGenresCount": 17,
  "uniqueGenres": 7,
  "savedPreferences": 7,
  "preferences": [
    {
      "genre": "Rock",
      "weight": 0.856,
      "confidence": 1.0,
      "rank": 1,
      "source": "test_data"
    },
    ...
  ]
}
```

#### 2. Calculate Weights

```bash
curl "http://localhost:8080/api/test/phase2/calculate-weight?frequency=5&total=20"
```

Response:
```json
{
  "frequency": 5,
  "total": 20,
  "weight": 0.625,
  "confidence": 1.0,
  "percentage": "25.0%"
}
```

#### 3. Sync from Spotify (Real Data)

**Prerequisites**: User must have Spotify connected with valid access token

```bash
curl -X POST "http://localhost:8080/api/test/phase2/sync-spotify?userId=YOUR_USER_ID&quick=false"
```

Response:
```json
{
  "success": true,
  "userId": "abc123",
  "genreCount": 45,
  "top10Genres": [
    "Rock",
    "Indie Rock",
    "Alternative Rock",
    "Pop",
    ...
  ]
}
```

#### 4. Get Top Genres

```bash
curl "http://localhost:8080/api/test/phase2/top-genres?userId=YOUR_USER_ID&limit=10"
```

#### 5. Add Manual Preference

```bash
curl -X POST "http://localhost:8080/api/test/phase2/add-manual?userId=YOUR_USER_ID&genreName=jazz&weight=0.8"
```

#### 6. Get Statistics

```bash
curl "http://localhost:8080/api/test/phase2/stats"
```

Response:
```json
{
  "totalUsers": 10,
  "usersWithGenrePreferences": 5,
  "usersWithSpotifyConnected": 7,
  "coveragePercentage": "50.0%"
}
```

---

### Production Usage (via `/api/v1/preferences/genres`)

All production endpoints require JWT authentication.

#### 1. Get My Preferences

```bash
curl -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  "http://localhost:8080/api/v1/preferences/genres?limit=20"
```

#### 2. Sync from Spotify

```bash
curl -X POST \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  "http://localhost:8080/api/v1/preferences/genres/sync?quick=false"
```

#### 3. Add Manual Preference

```bash
curl -X POST \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"genreName": "jazz", "weight": 0.9}' \
  "http://localhost:8080/api/v1/preferences/genres"
```

#### 4. Remove Preference

```bash
curl -X DELETE \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  "http://localhost:8080/api/v1/preferences/genres/jazz"
```

---

## Database Schema

### UserGenrePreference Table

| Field | Type | Description |
|-------|------|-------------|
| id | UUID | Primary key |
| user_id | UUID | Foreign key to users |
| genre_id | UUID | Foreign key to canonical_genres |
| weight | DOUBLE | Preference weight (0.0-1.0) |
| confidence | DOUBLE | Match confidence (0.0-1.0) |
| source | VARCHAR | Source of preference |
| rank | INTEGER | Ranking among user's preferences |
| created_at | TIMESTAMP | When created |
| last_updated | TIMESTAMP | Last update time |

### Source Types

- `spotify_derived` - Extracted from Spotify data
- `manual_selection` - User manually selected
- `test_data` - Test/mock data

---

## Integration with Phase 1

Phase 2 leverages Phase 1's foundation:

- Uses `CanonicalGenre` for genre normalization
- Uses `CanonicalGenreRepository` for lookups
- Stores preferences in `UserGenrePreference`
- Maintains data integrity through foreign keys

---

## Next Steps: Phase 3

Phase 2 provides the genre preference data needed for Phase 3: Matching Algorithm

Phase 3 will:
- Calculate music compatibility scores between users
- Use genre overlap (Jaccard similarity)
- Weight by genre preference strength
- Combine with personality, lifestyle, and location scores

---

## Performance Considerations

1. **Batch Processing**: Use `SpotifyGenreSyncService.syncAllUsersWithSpotify()` for migrations
2. **Quick Sync**: Use `?quick=true` for faster, less comprehensive syncs
3. **Indexing**: Ensure `user_id` and `genre_id` are indexed in `user_genre_preferences`
4. **Caching**: Consider caching top genres per user

---

## Testing Checklist

- ✅ Genre extraction with mock data
- ✅ Weight calculator accuracy
- ✅ Spotify sync (requires real Spotify token)
- ✅ Manual preference management
- ✅ Preference removal
- ✅ Statistics endpoint

---

## Troubleshooting

### "No genres found for user"
- User's Spotify account has no listening history
- Access token is invalid/expired
- Spotify API is down

### "Genre not found"
- The genre name doesn't exist in `canonical_genres`
- Check available genres: `GET /api/test/phase1/genres`

### "No Spotify account connected"
- User needs to connect their Spotify account first
- Check `spotify_access_token` field in users table

---

## Security Notes

⚠️ **IMPORTANT**: Remove or secure `/api/test/phase2/**` endpoints in production!

These endpoints bypass authentication and are for testing only.

---

## Summary

Phase 2 successfully:
- ✅ Extracts genres from Spotify listening data
- ✅ Normalizes Spotify genres to canonical genres
- ✅ Calculates intelligent weights and confidence scores
- ✅ Provides API endpoints for manual management
- ✅ Supports both automatic (Spotify) and manual preferences
- ✅ Prepares data for Phase 3 matching algorithm

**Status**: ✅ Phase 2 Complete - Ready for Phase 3!
