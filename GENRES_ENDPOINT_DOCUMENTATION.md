# Genres Endpoint Implementation Documentation

**Date**: 2025-11-26
**Feature**: Pre-selected Genres for Onboarding
**Backend Implementation**: Complete ✅

---

## Overview

A new endpoint has been added to fetch pre-selected music genres based on a user's Spotify listening history. This endpoint is specifically designed to support the onboarding flow's music preferences step, where users can select their favorite genres.

The endpoint extracts genres from the user's top Spotify artists, providing personalized genre suggestions that can be pre-selected in the UI.

---

## API Endpoint

### GET `/api/v1/user/genres`

Fetches a list of unique, sorted genres extracted from the user's top Spotify artists.

#### Request

**Method**: `GET`
**URL**: `http://localhost:8080/api/v1/user/genres`

**Headers**:
```
Authorization: Bearer {JWT_TOKEN}
```

**Query Parameters** (all optional):

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `limit` | Integer | `20` | Number of top artists to analyze (max: 50) |
| `time_range` | String | `medium_term` | Time range for top artists analysis |

**Valid `time_range` values**:
- `short_term`: Last 4 weeks
- `medium_term`: Last 6 months (default)
- `long_term`: All time

#### Response

**Status**: `200 OK`

**Content-Type**: `application/json`

**Body**: Array of strings (genres)

```json
[
  "alternative rock",
  "dance pop",
  "electropop",
  "indie pop",
  "pop",
  "rock",
  "synth-pop"
]
```

**Characteristics**:
- Genres are **unique** (no duplicates)
- Genres are **sorted alphabetically**
- Genres use **lowercase** formatting
- Returns **empty array** `[]` if no genres found or error occurs

#### Error Responses

| Status Code | Description |
|------------|-------------|
| `401 UNAUTHORIZED` | Invalid or missing JWT token |
| `404 NOT FOUND` | User not found in database |
| `500 INTERNAL_SERVER_ERROR` | Spotify API error or server error |

---

## Implementation Details

### Backend Architecture

The implementation follows a clean service-layer architecture:

```
UserController.java
    ↓ calls
SpotifyService.getGenresFromTopArtists()
    ↓ calls
SpotifyService.getTopArtists()
    ↓ fetches from
Spotify Web API (/me/top/artists)
    ↓ extracts
Genres from each artist
    ↓ processes
Deduplicate, sort, return
```

### Files Modified

#### 1. `SpotifyService.java` (Interface)
- **Location**: `src/main/java/com/example/dating/services/SpotifyService.java`
- **Change**: Added method signature
```java
List<String> getGenresFromTopArtists(String spotifyToken, Integer limit, String timeRange) throws JsonProcessingException;
```

#### 2. `SpotifyServiceImpl.java` (Implementation)
- **Location**: `src/main/java/com/example/dating/services/impl/SpotifyServiceImpl.java`
- **Change**: Implemented genre extraction logic (lines 195-212)
- **Logic**:
  1. Fetches top artists using existing `getTopArtists()` method
  2. Extracts genres from each artist using Java Streams
  3. Flattens all genre arrays into a single stream
  4. Removes duplicates with `.distinct()`
  5. Sorts alphabetically with `.sorted()`
  6. Returns as immutable list
  7. Returns empty list on errors (graceful degradation)

#### 3. `UserController.java` (REST Endpoint)
- **Location**: `src/main/java/com/example/dating/controllers/UserController.java`
- **Change**: Added `/genres` GET endpoint (lines 135-166)
- **Features**:
  - JWT authentication required
  - Automatic Spotify token refresh via `userService.getValidSpotifyToken()`
  - Query parameter validation with defaults
  - Comprehensive error handling

---

## Frontend Integration Guide

### TypeScript Server Action Example

Create a new server action in your frontend:

```typescript
// app/serverActions/spotify.ts (or similar file)

import { getSession } from "next-auth/react";

export async function fetchSuggestedGenres(
  limit: number = 20,
  timeRange: "short_term" | "medium_term" | "long_term" = "medium_term"
): Promise<string[]> {
  try {
    const session = await getSession();

    if (!session?.accessToken) {
      throw new Error("Not authenticated");
    }

    const params = new URLSearchParams({
      limit: limit.toString(),
      time_range: timeRange,
    });

    const response = await fetch(
      `${process.env.BACKEND_API_URL}/api/v1/user/genres?${params}`,
      {
        method: "GET",
        headers: {
          Authorization: `Bearer ${session.accessToken}`,
          "Content-Type": "application/json",
        },
      }
    );

    if (!response.ok) {
      throw new Error(`Failed to fetch genres: ${response.status}`);
    }

    const genres: string[] = await response.json();
    return genres;

  } catch (error) {
    console.error("Error fetching suggested genres:", error);
    return [];
  }
}
```

### React Component Usage

Use the server action in your MusicPreferencesStep component:

```typescript
// app/onboarding/components/steps/MusicPreferencesStep.tsx

import { useEffect, useState } from "react";
import { fetchSuggestedGenres } from "@/app/serverActions/spotify";

export function MusicPreferencesStep() {
  const [suggestedGenres, setSuggestedGenres] = useState<string[]>([]);
  const [selectedGenres, setSelectedGenres] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    async function loadSuggestedGenres() {
      setLoading(true);
      const genres = await fetchSuggestedGenres(50, "medium_term");
      setSuggestedGenres(genres);

      // Auto-select suggested genres
      setSelectedGenres(genres.slice(0, 5)); // Pre-select top 5

      setLoading(false);
    }

    loadSuggestedGenres();
  }, []);

  // Rest of your component...
}
```

### Pre-selecting Genres in React Hook Form

If using React Hook Form (as per your project setup):

```typescript
import { useEffect } from "react";
import { useForm } from "react-hook-form";
import { fetchSuggestedGenres } from "@/app/serverActions/spotify";

export function MusicPreferencesStep() {
  const form = useForm<MusicPreferencesFormData>({
    defaultValues: {
      favoriteGenres: [],
      // ... other fields
    },
  });

  useEffect(() => {
    async function prefillGenres() {
      const suggestedGenres = await fetchSuggestedGenres(30, "medium_term");

      // Set the form value with suggested genres
      if (suggestedGenres.length > 0) {
        form.setValue("favoriteGenres", suggestedGenres.slice(0, 5));
      }
    }

    prefillGenres();
  }, [form]);

  // Rest of component...
}
```

---

## Example Use Cases

### Use Case 1: Pre-fill Onboarding with Top 5 Genres

**Request**:
```bash
curl -X GET "http://localhost:8080/api/v1/user/genres?limit=30&time_range=medium_term" \
  -H "Authorization: Bearer eyJhbGc..."
```

**Response**:
```json
[
  "alternative rock",
  "indie pop",
  "modern rock",
  "pop",
  "rock"
]
```

**Frontend Action**: Pre-select these 5 genres as chips in the UI

---

### Use Case 2: Show Full Genre Palette

**Request**:
```bash
curl -X GET "http://localhost:8080/api/v1/user/genres?limit=50&time_range=long_term" \
  -H "Authorization: Bearer eyJhbGc..."
```

**Response**:
```json
[
  "alternative rock",
  "art pop",
  "bedroom pop",
  "dance pop",
  "electro house",
  "electropop",
  "indie pop",
  "indie rock",
  "modern rock",
  "pop",
  "pop rock",
  "progressive house",
  "rock",
  "synth-pop",
  "tropical house"
]
```

**Frontend Action**: Display as selectable chips, with first 3-5 pre-selected

---

### Use Case 3: Quick Onboarding (Recent Listening)

**Request**:
```bash
curl -X GET "http://localhost:8080/api/v1/user/genres?limit=20&time_range=short_term" \
  -H "Authorization: Bearer eyJhbGc..."
```

**Response**:
```json
[
  "dance pop",
  "electronic",
  "house",
  "techno"
]
```

**Frontend Action**: Show user's current music mood

---

## Testing

### Manual Testing with cURL

```bash
# Get your JWT token from NextAuth session
# Replace {YOUR_JWT_TOKEN} with actual token

# Test with defaults
curl -X GET "http://localhost:8080/api/v1/user/genres" \
  -H "Authorization: Bearer {YOUR_JWT_TOKEN}"

# Test with custom parameters
curl -X GET "http://localhost:8080/api/v1/user/genres?limit=50&time_range=long_term" \
  -H "Authorization: Bearer {YOUR_JWT_TOKEN}"

# Test with short-term (recent listening)
curl -X GET "http://localhost:8080/api/v1/user/genres?time_range=short_term" \
  -H "Authorization: Bearer {YOUR_JWT_TOKEN}"
```

### Expected Behavior

✅ **Success Cases**:
- Returns array of unique genres
- Genres are alphabetically sorted
- Genres use lowercase
- Empty array if user has no top artists
- Handles Spotify token refresh automatically

❌ **Error Cases**:
- Returns 401 if JWT token invalid/expired
- Returns 404 if user doesn't exist
- Returns 500 if Spotify API fails
- Returns empty array `[]` on graceful errors

---

## Technical Notes

### Why Extract from Top Artists?

Spotify's API provides genres at the **artist level**, not the track level. This implementation:
1. Fetches the user's top artists from Spotify
2. Extracts the `genres` array from each artist
3. Deduplicates and sorts

This provides more accurate genre representation than analyzing individual tracks.

### Performance Considerations

- **Caching**: Consider caching genres in the frontend for the session duration
- **Default limit**: Set to 20 artists (balanced between accuracy and performance)
- **Increase limit**: Use `limit=50` for more comprehensive genre discovery
- **Time range**: `medium_term` provides good balance of recent + historical taste

### Genre Formatting

Spotify returns genres in lowercase with spaces or hyphens:
- `"alternative rock"`
- `"indie-pop"`
- `"singer-songwriter"`

You may want to format these for display (capitalize first letter, etc.)

---

## Integration Checklist for Frontend

- [ ] Create server action to call `/api/v1/user/genres`
- [ ] Add error handling for 401/404/500 responses
- [ ] Display genres as selectable chips/tags in MusicPreferencesStep
- [ ] Pre-select suggested genres (recommend top 3-5)
- [ ] Allow users to deselect or add custom genres
- [ ] Ensure selected genres are included in `MusicPreferencesRequestDto.favoriteGenres`
- [ ] Test with users who have different music tastes
- [ ] Add loading state while fetching genres
- [ ] Handle empty response gracefully (show manual selection)

---

## Related Endpoints

This endpoint complements the existing Spotify endpoints:

| Endpoint | Purpose |
|----------|---------|
| `GET /api/v1/user` | Get full Spotify user profile |
| `GET /api/v1/user/artists` | Get top artists with full details |
| `GET /api/v1/user/tracks` | Get top tracks with full details |
| `GET /api/v1/user/genres` | **NEW** - Get unique genres from top artists |

---

## Questions or Issues?

If you encounter any issues during frontend integration:

1. **Check JWT Token**: Ensure NextAuth session is valid
2. **Check Network**: Verify backend is running on `http://localhost:8080`
3. **Check Spotify Token**: Backend automatically refreshes, but verify in logs
4. **Check Browser Console**: Look for CORS or network errors

---

**Last Updated**: 2025-11-26
**Backend Status**: ✅ Deployed and Ready
**Frontend Status**: ⏳ Pending Integration
