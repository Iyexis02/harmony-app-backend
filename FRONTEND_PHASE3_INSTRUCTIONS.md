# Frontend Implementation Guide: Phase 3 - Matching Algorithm (Coming Soon)

## ⚠️ Status: Backend Not Yet Implemented

**Phase 3 backend is planned but not yet built.** This document provides a **roadmap** of what will be built and what UI components you'll need.

**Current Progress:**
- ✅ Phase 1: Database Foundation (Complete)
- ✅ Phase 2: Genre Extraction (Complete)
- ⏳ Phase 3: Matching Algorithm (Coming Soon)
- ⏸️ Phase 4: Recommendations (Future)

---

## Overview

Phase 3 is the **Matching Algorithm** - the core feature that compares two users and calculates their compatibility score based on music preferences.

**What users see:**
- Match score with other users (0-100%)
- Match breakdown (which genres overlap)
- Compatibility insights
- Swipe interface with match scores
- Match notifications

---

## What Will Be Built (Backend - Coming)

### Matching Algorithm Components

The backend will implement:

✅ **Match Score Calculator**
- Input: User A preferences + User B preferences
- Output: Overall match score (0-100%)
- Considers: Genre overlap, weight similarity, confidence

✅ **Dimension Scoring**
- Music compatibility (primary)
- Genre diversity similarity
- Subgenre overlap bonus
- Time-range alignment (recent vs historical taste)

✅ **Batch Matching**
- Pre-calculate scores for all potential matches
- Store in `user_match_scores` table
- Update when preferences change

✅ **Match Filtering**
- Minimum score threshold
- Geographic filters
- Age filters
- Already-swiped exclusion

---

## Expected API Endpoints (Planned)

### Base URL
```
http://localhost:8080/api/v1/matching
```

### 1. Get Match Score Between Two Users

```http
GET /api/v1/matching/score/{otherUserId}

Headers:
Authorization: Bearer <JWT_TOKEN>

Response:
{
  "userId": "current-user-uuid",
  "otherUserId": "other-user-uuid",
  "overallScore": 82.5,
  "musicScore": 85.0,
  "breakdown": {
    "sharedGenres": [
      {
        "genre": "Rock",
        "userWeight": 0.85,
        "otherWeight": 0.90,
        "overlap": 0.85
      },
      {
        "genre": "Jazz",
        "userWeight": 0.72,
        "otherWeight": 0.65,
        "overlap": 0.65
      }
    ],
    "userOnlyGenres": ["Electronic"],
    "otherOnlyGenres": ["Classical"]
  },
  "insights": [
    "You both love Rock music",
    "Strong Jazz connection",
    "Different tastes in Electronic vs Classical"
  ],
  "compatibilityLevel": "Very High",
  "calculatedAt": "2025-12-29T12:00:00"
}
```

### 2. Get Potential Matches (Recommendations)

```http
GET /api/v1/matching/potential?limit=20&minScore=60

Headers:
Authorization: Bearer <JWT_TOKEN>

Query Parameters:
- limit: number (default 20)
- minScore: number (default 50, range 0-100)
- excludeSwiped: boolean (default true)
- maxDistance: number (km, optional)

Response:
{
  "matches": [
    {
      "userId": "match-uuid-1",
      "name": "Sarah",
      "age": 25,
      "photos": ["url1", "url2"],
      "matchScore": 87.5,
      "topSharedGenres": ["Rock", "Jazz", "Indie"],
      "distance": 5.2,
      "previewInsight": "You both love Rock and Jazz"
    },
    {
      "userId": "match-uuid-2",
      "name": "Mike",
      "age": 27,
      "photos": ["url1", "url2"],
      "matchScore": 82.0,
      "topSharedGenres": ["Electronic", "Hip Hop"],
      "distance": 8.7,
      "previewInsight": "Strong Electronic music connection"
    }
  ],
  "total": 2,
  "hasMore": false
}
```

### 3. Record Swipe Action

```http
POST /api/v1/matching/swipe

Headers:
Authorization: Bearer <JWT_TOKEN>
Content-Type: application/json

Body:
{
  "swipedUserId": "other-user-uuid",
  "action": "like",  // or "pass"
  "matchScore": 82.5
}

Response:
{
  "swipeId": "swipe-uuid",
  "action": "like",
  "resultedInMatch": true,  // if mutual like
  "match": {
    "matchId": "match-uuid",
    "userId": "other-user-uuid",
    "name": "Sarah",
    "matchScore": 82.5,
    "matchedAt": "2025-12-29T12:00:00"
  }
}
```

### 4. Get My Matches

```http
GET /api/v1/matching/matches?status=active

Headers:
Authorization: Bearer <JWT_TOKEN>

Query Parameters:
- status: "active" | "unmatched" | "all"

Response:
{
  "matches": [
    {
      "matchId": "match-uuid",
      "userId": "other-user-uuid",
      "name": "Sarah",
      "photos": ["url"],
      "matchScore": 87.5,
      "matchedAt": "2025-12-29T10:00:00",
      "conversationStarted": false,
      "lastMessage": null,
      "sharedGenres": ["Rock", "Jazz"]
    }
  ],
  "total": 15
}
```

### 5. Get Match Analytics

```http
GET /api/v1/matching/analytics

Headers:
Authorization: Bearer <JWT_TOKEN>

Response:
{
  "totalSwipes": 50,
  "totalLikes": 30,
  "totalPasses": 20,
  "swipeThroughRate": 0.6,
  "totalMatches": 15,
  "matchRate": 0.5,
  "averageMatchScore": 78.5,
  "topSharedGenre": "Rock",
  "mostCompatibleUser": {
    "userId": "uuid",
    "name": "Sarah",
    "score": 92.0
  }
}
```

---

## UI Components Needed

### Component 1: Swipe Card Interface

**File:** `src/pages/discover/SwipeCard.jsx`

**Purpose:** Tinder-style swipe interface with match score

**Mockup:**
```
┌─────────────────────────────────────┐
│                                     │
│          [Profile Photo]            │
│                                     │
│  Sarah, 25              🎵 87%      │
│  📍 5 km away                       │
│                                     │
│  ┌─────────────────────────────┐   │
│  │ Top Shared Genres:          │   │
│  │ • Rock (you: 85%, her: 90%) │   │
│  │ • Jazz (you: 72%, her: 65%) │   │
│  │ • Indie (you: 45%, her: 55%)│   │
│  └─────────────────────────────┘   │
│                                     │
│  "You both love Rock and Jazz!"    │
│                                     │
│  [❌ Pass]          [💚 Like]       │
└─────────────────────────────────────┘
```

**Features:**
- Swipeable card (left = pass, right = like)
- Match score prominently displayed
- Top 3 shared genres
- Distance indicator
- Profile photo gallery
- "See full profile" button

---

### Component 2: Match Score Breakdown Modal

**File:** `src/components/matching/MatchScoreBreakdown.jsx`

**Purpose:** Detailed breakdown of match score

**Mockup:**
```
┌─────────────────────────────────────┐
│ Match with Sarah                   │
│                                     │
│  ┌─────────────────────┐            │
│  │   Overall Score     │            │
│  │       87%           │            │
│  │   Very High Match   │            │
│  └─────────────────────┘            │
│                                     │
│  What You Share:                    │
│  ┌─────────────────────────────┐   │
│  │ 🎸 Rock                      │   │
│  │ You: ████████░ 85%           │   │
│  │ Her: █████████ 90%           │   │
│  │ Overlap: 85%                 │   │
│  └─────────────────────────────┘   │
│  ┌─────────────────────────────┐   │
│  │ 🎺 Jazz                      │   │
│  │ You: ███████░░ 72%           │   │
│  │ Her: ██████░░░ 65%           │   │
│  │ Overlap: 65%                 │   │
│  └─────────────────────────────┘   │
│                                     │
│  Your Unique Interests:             │
│  • Electronic                       │
│                                     │
│  Her Unique Interests:              │
│  • Classical                        │
│                                     │
│  [Close]                            │
└─────────────────────────────────────┘
```

---

### Component 3: Matches List

**File:** `src/pages/matches/MatchesList.jsx`

**Purpose:** View all mutual matches

**Mockup:**
```
┌─────────────────────────────────────┐
│ Your Matches (15)                   │
│                                     │
│ ┌─────────────────────────────┐    │
│ │ [Photo] Sarah, 25  🎵 87%   │    │
│ │ Matched 2 hours ago         │    │
│ │ Shared: Rock, Jazz, Indie   │    │
│ │ [Message]                   │    │
│ └─────────────────────────────┘    │
│                                     │
│ ┌─────────────────────────────┐    │
│ │ [Photo] Mike, 27   🎵 82%   │    │
│ │ Matched yesterday           │    │
│ │ Shared: Electronic, Hip Hop │    │
│ │ [Message]                   │    │
│ └─────────────────────────────┘    │
│                                     │
│ ┌─────────────────────────────┐    │
│ │ [Photo] Emma, 24   🎵 76%   │    │
│ │ Matched 3 days ago          │    │
│ │ Shared: Pop, Indie          │    │
│ │ [Message]                   │    │
│ └─────────────────────────────┘    │
└─────────────────────────────────────┘
```

---

### Component 4: Match Notification

**File:** `src/components/matching/MatchNotification.jsx`

**Purpose:** Popup when mutual match occurs

**Mockup:**
```
┌─────────────────────────────────────┐
│         🎉 IT'S A MATCH! 🎉         │
│                                     │
│      [Your Photo]  [Her Photo]     │
│                                     │
│  You and Sarah both like each other!│
│                                     │
│       Match Score: 87%              │
│   You both love Rock and Jazz!      │
│                                     │
│  [Send Message]    [Keep Swiping]  │
└─────────────────────────────────────┘
```

---

### Component 5: Match Analytics Dashboard

**File:** `src/pages/analytics/MatchAnalytics.jsx`

**Purpose:** Personal matching statistics

**Mockup:**
```
┌─────────────────────────────────────┐
│ Your Matching Stats                 │
│                                     │
│ ┌──────────┐ ┌──────────┐          │
│ │ 50       │ │ 15       │          │
│ │ Swipes   │ │ Matches  │          │
│ └──────────┘ └──────────┘          │
│                                     │
│ ┌──────────┐ ┌──────────┐          │
│ │ 60%      │ │ 78.5%    │          │
│ │ Like Rate│ │ Avg Score│          │
│ └──────────┘ └──────────┘          │
│                                     │
│ Most Compatible Match:              │
│ ┌─────────────────────────────┐    │
│ │ [Photo] Sarah - 92% match   │    │
│ └─────────────────────────────┘    │
│                                     │
│ Most Shared Genre:                  │
│ 🎸 Rock - 80% of your matches       │
└─────────────────────────────────────┘
```

---

## User Flows

### Flow 1: Discover and Swipe

```
1. User opens "Discover" tab
2. App fetches potential matches (score >= 60%)
3. First match card appears
4. User sees: Photo, name, age, match score (87%)
5. User taps match score → breakdown modal opens
6. User sees: "You both love Rock (85% overlap)"
7. User closes modal
8. User swipes right (Like)
9. If mutual like:
   → "It's a Match!" popup appears
   → User can message or keep swiping
10. If not mutual yet:
   → Card disappears, next card appears
11. Repeat
```

### Flow 2: View Match Details

```
1. User opens "Matches" tab
2. List of mutual matches appears
3. User taps on "Sarah - 87%"
4. Full profile opens
5. User sees:
   - Photos
   - Bio
   - Match score breakdown
   - Shared genres visualization
   - Message button
6. User taps "Message"
7. Chat opens
```

### Flow 3: Check Analytics

```
1. User opens "Profile" → "Stats"
2. Analytics dashboard loads
3. User sees:
   - Total swipes, matches, like rate
   - Average match score
   - Most compatible match
   - Most shared genre
4. User taps "Most Compatible Match"
5. That user's profile opens
```

---

## Expected Data Structures

### Match Score Object

```typescript
interface MatchScore {
  userId: string;
  otherUserId: string;
  overallScore: number; // 0-100
  musicScore: number;
  breakdown: {
    sharedGenres: SharedGenre[];
    userOnlyGenres: string[];
    otherOnlyGenres: string[];
  };
  insights: string[];
  compatibilityLevel: 'Low' | 'Medium' | 'High' | 'Very High';
  calculatedAt: string;
}

interface SharedGenre {
  genre: string;
  userWeight: number;
  otherWeight: number;
  overlap: number;
}
```

### Potential Match Object

```typescript
interface PotentialMatch {
  userId: string;
  name: string;
  age: number;
  photos: string[];
  matchScore: number;
  topSharedGenres: string[];
  distance?: number;
  previewInsight: string;
}
```

### Match Object

```typescript
interface Match {
  matchId: string;
  userId: string;
  name: string;
  photos: string[];
  matchScore: number;
  matchedAt: string;
  conversationStarted: boolean;
  lastMessage?: string;
  sharedGenres: string[];
}
```

---

## Matching Algorithm Logic (For Understanding)

### Score Calculation (Simplified)

```
1. Get User A preferences: [Rock: 0.85, Jazz: 0.72, Electronic: 0.45]
2. Get User B preferences: [Rock: 0.90, Jazz: 0.65, Classical: 0.80]

3. Find shared genres:
   - Rock: min(0.85, 0.90) = 0.85
   - Jazz: min(0.72, 0.65) = 0.65

4. Calculate overlap score:
   overlap = (0.85 + 0.65) / 2 = 0.75

5. Calculate coverage penalty:
   userGenres = 3
   sharedGenres = 2
   coverage = 2 / 3 = 0.67

6. Final score:
   score = overlap × coverage × 100
   score = 0.75 × 0.67 × 100 = 50%

   (Actual algorithm is more sophisticated)
```

### Compatibility Levels

```
0-40%:   Low Match
41-60%:  Medium Match
61-80%:  High Match
81-100%: Very High Match
```

---

## State Management (Zustand Example)

```jsx
// src/stores/matchingStore.js
import { create } from 'zustand';

export const useMatchingStore = create((set, get) => ({
  potentialMatches: [],
  matches: [],
  currentMatchIndex: 0,
  loading: false,

  fetchPotentialMatches: async (token, minScore = 60) => {
    set({ loading: true });
    try {
      const response = await fetch(
        `http://localhost:8080/api/v1/matching/potential?minScore=${minScore}`,
        { headers: { Authorization: `Bearer ${token}` } }
      );
      const data = await response.json();
      set({ potentialMatches: data.matches, loading: false });
    } catch (error) {
      set({ loading: false });
    }
  },

  swipe: async (token, userId, action) => {
    const currentMatch = get().potentialMatches[get().currentMatchIndex];

    try {
      const response = await fetch(
        'http://localhost:8080/api/v1/matching/swipe',
        {
          method: 'POST',
          headers: {
            Authorization: `Bearer ${token}`,
            'Content-Type': 'application/json'
          },
          body: JSON.stringify({
            swipedUserId: userId,
            action,
            matchScore: currentMatch.matchScore
          })
        }
      );

      const data = await response.json();

      // Move to next card
      set({ currentMatchIndex: get().currentMatchIndex + 1 });

      // If mutual match, add to matches
      if (data.resultedInMatch) {
        set({ matches: [...get().matches, data.match] });
      }

      return data;
    } catch (error) {
      console.error('Swipe failed:', error);
    }
  },

  fetchMatches: async (token) => {
    try {
      const response = await fetch(
        'http://localhost:8080/api/v1/matching/matches',
        { headers: { Authorization: `Bearer ${token}` } }
      );
      const data = await response.json();
      set({ matches: data.matches });
    } catch (error) {
      console.error('Failed to fetch matches:', error);
    }
  }
}));
```

---

## Animations and Interactions

### Swipe Animation (React Spring Example)

```jsx
import { useSpring, animated } from 'react-spring';
import { useDrag } from '@use-gesture/react';

function SwipeCard({ user, onSwipe }) {
  const [{ x, rotate }, api] = useSpring(() => ({
    x: 0,
    rotate: 0
  }));

  const bind = useDrag(({ down, movement: [mx], velocity, direction: [xDir] }) => {
    const trigger = velocity > 0.2;

    if (!down && trigger) {
      // Swiped!
      const dir = xDir < 0 ? 'left' : 'right';
      onSwipe(dir === 'right' ? 'like' : 'pass');
    }

    api.start({
      x: down ? mx : 0,
      rotate: down ? mx / 10 : 0,
      immediate: down
    });
  });

  return (
    <animated.div
      {...bind()}
      style={{
        x,
        rotate: rotate.to(r => `${r}deg`)
      }}
      className="swipe-card"
    >
      {/* Card content */}
    </animated.div>
  );
}
```

---

## Testing Strategy

### Unit Tests

```jsx
// matchingUtils.test.js
import { calculateMatchScore } from './matchingUtils';

test('calculates match score correctly', () => {
  const userPrefs = [
    { genre: 'rock', weight: 0.85 },
    { genre: 'jazz', weight: 0.72 }
  ];

  const otherPrefs = [
    { genre: 'rock', weight: 0.90 },
    { genre: 'jazz', weight: 0.65 }
  ];

  const score = calculateMatchScore(userPrefs, otherPrefs);
  expect(score).toBeGreaterThan(70);
  expect(score).toBeLessThan(90);
});
```

### Integration Tests

```jsx
// SwipeCard.test.jsx
import { render, fireEvent } from '@testing-library/react';
import SwipeCard from './SwipeCard';

test('calls onSwipe when swiped right', () => {
  const onSwipe = jest.fn();
  const { container } = render(
    <SwipeCard user={mockUser} onSwipe={onSwipe} />
  );

  const card = container.querySelector('.swipe-card');

  // Simulate swipe right
  fireEvent.drag(card, { clientX: 300 });

  expect(onSwipe).toHaveBeenCalledWith('like');
});
```

---

## Performance Considerations

### Optimize Large Lists

```jsx
// Use react-window for virtualized lists
import { FixedSizeList } from 'react-window';

function MatchesList({ matches }) {
  const Row = ({ index, style }) => (
    <div style={style}>
      <MatchCard match={matches[index]} />
    </div>
  );

  return (
    <FixedSizeList
      height={600}
      itemCount={matches.length}
      itemSize={120}
    >
      {Row}
    </FixedSizeList>
  );
}
```

### Preload Next Cards

```jsx
useEffect(() => {
  // Preload next 3 cards
  const nextCards = potentialMatches.slice(
    currentIndex + 1,
    currentIndex + 4
  );

  nextCards.forEach(card => {
    // Preload images
    const img = new Image();
    img.src = card.photos[0];
  });
}, [currentIndex, potentialMatches]);
```

---

## Accessibility

### Keyboard Navigation for Swipes

```jsx
function SwipeCard({ user, onSwipe }) {
  const handleKeyDown = (e) => {
    if (e.key === 'ArrowLeft') {
      onSwipe('pass');
    } else if (e.key === 'ArrowRight') {
      onSwipe('like');
    }
  };

  return (
    <div
      className="swipe-card"
      role="article"
      tabIndex={0}
      onKeyDown={handleKeyDown}
      aria-label={`${user.name}, ${user.age}, ${user.matchScore}% match`}
    >
      {/* Card content */}
    </div>
  );
}
```

### Screen Reader Announcements

```jsx
<div role="status" aria-live="polite">
  {matchScore && (
    <span className="sr-only">
      {matchScore}% compatibility with {userName}
    </span>
  )}
</div>
```

---

## Summary: What to Build (When Backend Ready)

### Required Pages

1. ⏳ **DiscoverPage** - Swipe interface
2. ⏳ **MatchesListPage** - View all matches
3. ⏳ **MatchProfilePage** - Detailed match view
4. ⏳ **AnalyticsPage** - Personal statistics

### Required Components

1. ⏳ **SwipeCard** - Swipeable card with match score
2. ⏳ **MatchScoreBreakdown** - Detailed score modal
3. ⏳ **MatchNotification** - "It's a Match!" popup
4. ⏳ **MatchCard** - List item for matches
5. ⏳ **GenreOverlapViz** - Visual genre comparison

### Required State Management

1. ⏳ Potential matches store
2. ⏳ Matches store
3. ⏳ Swipe history tracking
4. ⏳ Real-time match notifications

---

## Timeline Estimate

Once Phase 3 backend is complete:

**Week 1:** Basic swipe interface + match score display
**Week 2:** Match breakdown modal + matches list
**Week 3:** Match notifications + analytics
**Week 4:** Polish, animations, testing

---

## What You Can Do Now

While waiting for Phase 3 backend:

1. ✅ Complete Phase 2 frontend (onboarding + preferences)
2. ✅ Design mockups/wireframes for Phase 3 UI
3. ✅ Set up routing structure for discover/matches pages
4. ✅ Create placeholder components with mock data
5. ✅ Test Spotify OAuth integration thoroughly

---

## Questions for Backend Team

Before starting Phase 3 frontend:

1. What's the exact match score formula?
2. How are ties handled in recommendations?
3. Is there a minimum number of preferences needed?
4. Real-time updates or polling for new matches?
5. WebSocket for match notifications?
6. Distance calculation included?
7. Batch size for fetching potential matches?

---

## Next Steps

**Current Status:** Phase 2 (Genre Extraction) is complete

**Next:** Wait for Phase 3 backend development

**Meanwhile:** Focus on perfecting Phase 1 & 2 UI/UX

**When Ready:** Backend team will provide actual API endpoints, then you can build Phase 3 frontend following this guide! 🚀

---

## Contact Backend Team

For questions about Phase 3 backend development timeline or API design, contact the backend developers working on the matching algorithm implementation.

Phase 3 is the most exciting part - this is where users find their music soulmates! 🎵💕
