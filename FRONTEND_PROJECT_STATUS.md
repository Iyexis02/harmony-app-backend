# Dating App Project - Current Status & Context

**Last Updated**: 2025-11-26
**Project**: Music-based Dating Application
**Status**: 🟢 Active Development

---

## 📋 Quick Overview

This is a full-stack music-based dating application that matches users based on their Spotify music preferences, location, and lifestyle compatibility.

### Tech Stack

**Frontend** (Next.js - This Repository):
- Next.js 16.0.1 (App Router)
- React 19.2.0
- TypeScript
- Radix UI + Tailwind CSS (shadcn/ui)
- React Hook Form + Zod validation
- NextAuth v4 (Spotify OAuth)
- Cloudinary (photo storage)
- Google Maps API (location)

**Backend** (Spring Boot - Separate Repository):
- Spring Boot (Java)
- Running at: `http://localhost:8080`
- API Base: `/api/v1`
- See `CLAUDE.md` for complete API specification

---

## 🎯 Recent Work Completed (2025-11-26)

### 1. Cloudinary Photo Upload Fix ✅

**Problem**: CORS 400 errors during photo upload in onboarding flow

**Root Cause**:
- Code was sending `transformation` parameter in unsigned upload request
- Cloudinary API only allows specific parameters for unsigned uploads:
    - Required: `file`, `upload_preset`
    - Optional: `callback`, `public_id`, `folder`, `tags`, `context`, `face_coordinates`, `custom_coordinates`, `regions`

**Solution**:
- Removed invalid `transformation` parameter from `lib/cloudinary.ts:17`
- Transformations should be configured in Cloudinary upload preset instead

**Files Modified**:
- `lib/cloudinary.ts` - Removed transformation parameter from FormData

### 2. Frontend-Backend Type Alignment ✅

**Problem**: TypeScript types didn't match Spring Boot DTOs exactly

**Fixes Applied**:

1. **Fixed Typo** (`types/onboarding.d.ts:153`)
    - `PrivacySettingPRequestDto` → `PrivacySettingsRequestDto`

2. **Updated SexualOrientation Enum** (`app/enums/user/userEnum.ts:22-33`)
    - ✅ Added: `DEMISEXUAL`, `QUESTIONING`
    - Now: STRAIGHT, GAY, LESBIAN, BISEXUAL, PANSEXUAL, ASEXUAL, DEMISEXUAL, QUEER, QUESTIONING, OTHER

3. **Updated RelationshipGoal Enum** (`app/enums/user/userEnum.ts:52-60`)
    - ❌ Removed: `NOT_SURE` (not in backend spec)
    - Reordered to match backend: CASUAL_DATING, SERIOUS_RELATIONSHIP, FRIENDSHIP, SOMETHING_CASUAL, MARRIAGE, FIGURING_IT_OUT, PREFER_NOT_TO_SAY

4. **Fixed Photo Validation** (`app/onboarding/components/steps/PhotosStep.tsx`)
    - Changed minimum photos: 2 → 1 (matching backend constraint)
    - Updated validation schema, UI text, and button logic

### 3. Genres Endpoint for Onboarding Pre-selection ✅

**Feature**: New API endpoint to fetch pre-selected genres for music preferences onboarding step

**Implementation**:
- Added `GET /api/v1/user/genres` endpoint to UserController
- Endpoint fetches user's top Spotify artists and extracts unique genres
- Returns deduplicated, alphabetically sorted list of genres
- Supports optional parameters: `limit` (default: 20) and `time_range` (default: medium_term)

**Files Modified**:
1. **SpotifyService.java** - Added `getGenresFromTopArtists()` method signature
2. **SpotifyServiceImpl.java** (lines 195-212) - Implemented genre extraction logic
   - Fetches top artists via Spotify API
   - Extracts genres using Java Streams
   - Deduplicates and sorts alphabetically
   - Returns empty list on errors (graceful degradation)
3. **UserController.java** (lines 135-166) - Added `/genres` GET endpoint
   - JWT authentication required
   - Automatic Spotify token refresh
   - Query parameter validation with defaults

**Documentation**:
- Created `GENRES_ENDPOINT_DOCUMENTATION.md` with complete integration guide
- Includes frontend TypeScript examples, use cases, and testing instructions

**Frontend Integration Required**:
- Create server action to fetch genres from `/api/v1/user/genres`
- Pre-select suggested genres in MusicPreferencesStep component
- See `GENRES_ENDPOINT_DOCUMENTATION.md` for complete integration guide

---

## 🔐 Environment Configuration

**File**: `.env.local`

### Current Variables (✅ All Configured)

```env
# Spotify OAuth
SPOTIFY_CLIENT_ID="8d4025fc147e43f0897932570cb1ba00"
SPOTIFY_CLIENT_SECRET="1508a70e9c004543a4e2c139f1755438"
SPOTIFY_API_BASE_URL="https://api.spotify.com/v1"

# NextAuth
NEXTAUTH_URL="http://127.0.0.1:3000"
NEXTAUTH_SECRET="p2qK8CB2lJhHUQJ4VCprNm9XHgeXj1ZOLpTybBc2qWo="

# Backend API
BACKEND_API_URL="http://localhost:8080"
JWT_SECRET="8a552fdaabeca3d5a871c0eda32b21d2"

# Google Maps
GOOGLE_MAPS_API_KEY="AIzaSyC1ykjFhu1pzRJS81HM1a1_ig8SagzPEpQ"

# Cloudinary
NEXT_PUBLIC_CLOUDINARY_CLOUD_NAME="dpe1yjcph"
NEXT_PUBLIC_CLOUDINARY_UPLOAD_PRESET="dating-app-unsigned"
CLOUDINARY_API_KEY="[configured]"
CLOUDINARY_API_SECRET="[configured]"
```

**Status**: 🟢 All services configured and ready

---

## 🏗️ Architecture Overview

### Onboarding Flow (8 Steps)

The user registration process follows this sequence:

```mermaid
stateDiagram-v2
    [*] --> STARTED
    STARTED --> BASIC_PROFILE
    BASIC_PROFILE --> LOCATION_INFO
    LOCATION_INFO --> PHOTOS
    PHOTOS --> MUSIC_PREFERENCES
    MUSIC_PREFERENCES --> LIFESTYLE
    LIFESTYLE --> PERSONALITY
    PERSONALITY --> DATING_PREFERENCES
    DATING_PREFERENCES --> PRIVACY_SETTINGS
    PRIVACY_SETTINGS --> FINISHED
    FINISHED --> [*]
```

| Step | Endpoint | Description |
|------|----------|-------------|
| 1. BASIC_PROFILE | `PUT /api/v1/onboarding/basic-info` | Name, DOB, gender, sexual orientation |
| 2. LOCATION_INFO | `PUT /api/v1/onboarding/location` | City, country, lat/long coordinates |
| 3. PHOTOS | `PUT /api/v1/onboarding/photos` | 1-6 photos via Cloudinary |
| 4. MUSIC_PREFERENCES | `PUT /api/v1/onboarding/music-preferences` | Genres, concert frequency, importance |
| 5. LIFESTYLE | `PUT /api/v1/onboarding/lifestyle` | Education, occupation, habits |
| 6. PERSONALITY | `PUT /api/v1/onboarding/personality` | Bio, interests, MBTI |
| 7. DATING_PREFERENCES | `PUT /api/v1/onboarding/dating-preferences` | Age range, distance, goals |
| 8. PRIVACY_SETTINGS | `PUT /api/v1/onboarding/privacy-settings` | Visibility, Spotify settings |

### API Endpoints Summary

**Base URL**: `http://localhost:8080/api/v1`

#### Onboarding Endpoints

**Update (PUT)**:
- `/onboarding/basic-info` - BasicProfileRequestDto
- `/onboarding/location` - LocationDto
- `/onboarding/photos` - PhotosRequestDto
- `/onboarding/music-preferences` - MusicPreferencesRequestDto
- `/onboarding/lifestyle` - LifestyleRequestDto
- `/onboarding/personality` - PersonalityRequestDto
- `/onboarding/dating-preferences` - DatingPreferencesRequestDto
- `/onboarding/privacy-settings` - PrivacySettingsRequestDto

**Retrieve (GET)**:
- `/onboarding/profile` - CompleteProfileResponseDto
- `/onboarding/progress` - OnboardingProgressDto
- `/onboarding/music-preferences` - MusicPreferencesResponseDto
- `/onboarding/lifestyle` - LifestyleResponseDto
- `/onboarding/personality` - PersonalityResponseDto
- `/onboarding/dating-preferences` - DatingPreferencesResponseDto
- `/onboarding/privacy-settings` - PrivacySettingsResponseDto
- `/onboarding/photos` - PhotoResponseDto[]

#### Authentication

All onboarding endpoints require:
```
Authorization: Bearer {JWT_TOKEN}
```

The JWT token is obtained from NextAuth session after Spotify OAuth login.

---

## 📦 Key Data Transfer Objects (DTOs)

### Request DTOs (Frontend → Backend)

#### BasicProfileRequestDto
```typescript
{
  name: string;                      // required
  dateOfBirth: string;               // ISO 8601: "1995-06-15", required, 18+
  gender: Gender;                    // enum, required
  sexualOrientation: SexualOrientation; // enum, required
}
```

#### LocationDto
```typescript
{
  latitude?: number;                 // optional (BigDecimal on backend)
  longitude?: number;                // optional (BigDecimal on backend)
  locationCity: string;              // required
  locationCountry: string;           // required
}
```

#### PhotosRequestDto
```typescript
{
  photos: PhotoUploadRequestDto[];   // min: 1, max: 6, one must be primary
}

// PhotoUploadRequestDto
{
  imageUrl: string;                  // Cloudinary URL, required
  displayOrder: number;              // 0-based index, required
  isPrimary: boolean;                // exactly one must be true, required
  caption?: string;                  // optional, max 255 chars
}
```

#### MusicPreferencesRequestDto
```typescript
{
  favoriteGenres: string[];          // required, not empty
  concertFrequency: ConcertFrequency; // enum, required
  musicImportance: MusicImportance;  // enum, required
  favoriteDecades?: string[];        // optional
  openToNewGenres: boolean;          // required
  listeningTimes?: string[];         // optional
  hoursPerDay?: number;              // optional, 0-24
}
```

#### LifestyleRequestDto
```typescript
{
  education?: EducationLevel;        // enum, optional
  occupation?: string;               // optional
  company?: string;                  // optional
  relationshipStatus: RelationshipStatus; // enum, required
  wantsKids?: KidsPreference;        // enum, optional
  smokingHabits?: SmokingHabits;     // enum, optional
  drinkingHabits?: DrinkingHabits;   // enum, optional
  exerciseFrequency?: ExerciseFrequency; // enum, optional
  religion?: string;                 // optional
  politicalViews?: string;           // optional
}
```

#### PersonalityRequestDto
```typescript
{
  bio: string;                       // required, max 500 chars
  interests?: string[];              // optional
  mbti?: MBTI;                       // enum, optional
  lookingForText?: string;           // optional, max 500 chars
  favoriteQuote?: string;            // optional, max 300 chars
  conversationStarters?: string;     // optional, max 500 chars
}
```

#### DatingPreferencesRequestDto
```typescript
{
  minAge: number;                    // required, min: 18, must be < maxAge
  maxAge: number;                    // required, max: 100, must be > minAge
  maxDistanceKm: number;             // required, min: 1
  interestedInGenders: Gender[];     // required, not empty
  relationshipGoal: RelationshipGoal; // enum, required
  dealBreakers?: string[];           // optional
  showMe?: string;                   // optional
  musicMatchImportance?: number;     // optional, 0-100
}
```

#### PrivacySettingsRequestDto
```typescript
{
  isProfilePublic: boolean;          // required
  showAge: boolean;                  // required
  showDistance: boolean;             // required
  showLastActive: boolean;           // required
  discoverable: boolean;             // required
  showLikedByYou?: boolean;          // optional
  showSpotifyProfile: boolean;       // required
  showMusicStats: boolean;           // required
  incognitoMode?: boolean;           // optional
  readReceipts: boolean;             // required
}
```

### Response DTOs (Backend → Frontend)

#### CompleteProfileResponseDto
```typescript
{
  id: string;                        // UUID
  email: string;
  name: string;
  dateOfBirth: string;               // ISO date
  age: number;                       // calculated
  gender: Gender;
  sexualOrientation: SexualOrientation;
  registrationStage: RegistrationStage;

  locationCity: string;
  locationCountry: string;
  latitude?: number;
  longitude?: number;

  photos: PhotoResponseDto[];
  primaryPhotoUrl?: string;

  musicPreferences?: MusicPreferencesResponseDto;
  lifestyle?: LifestyleResponseDto;
  personality?: PersonalityResponseDto;
  datingPreferences?: DatingPreferencesResponseDto;
  privacySettings?: PrivacySettingsResponseDto;

  progress: OnboardingProgressDto;
}
```

#### OnboardingProgressDto
```typescript
{
  currentStage: RegistrationStage;
  completionPercentage: number;      // 0-100
  stepsCompleted: {
    BASIC_PROFILE: boolean;
    LOCATION_INFO: boolean;
    PHOTOS: boolean;
    MUSIC_PREFERENCES: boolean;
    LIFESTYLE: boolean;
    PERSONALITY: boolean;
    DATING_PREFERENCES: boolean;
    PRIVACY_SETTINGS: boolean;
  };
  nextStep: string | null;
}
```

---

## 🎨 Enums Reference

### Gender
```
MALE, FEMALE, NON_BINARY, OTHER, PREFER_NOT_TO_SAY
```

### SexualOrientation
```
STRAIGHT, GAY, LESBIAN, BISEXUAL, PANSEXUAL, ASEXUAL,
DEMISEXUAL, QUEER, QUESTIONING, OTHER
```

### RegistrationStage
```
STARTED, BASIC_PROFILE, LOCATION_INFO, PHOTOS, MUSIC_PREFERENCES,
LIFESTYLE, PERSONALITY, DATING_PREFERENCES, PRIVACY_SETTINGS, FINISHED
```

### ConcertFrequency
```
NEVER, RARELY, FEW_TIMES_A_YEAR, MONTHLY, WEEKLY, MULTIPLE_TIMES_A_WEEK
```

### MusicImportance
```
NOT_IMPORTANT, SOMEWHAT_IMPORTANT, IMPORTANT, VERY_IMPORTANT, LIFE_IS_MUSIC
```

### EducationLevel
```
HIGH_SCHOOL, SOME_COLLEGE, ASSOCIATES_DEGREE, BACHELORS_DEGREE,
MASTERS_DEGREE, DOCTORATE, TRADE_SCHOOL, PREFER_NOT_TO_SAY
```

### RelationshipStatus
```
SINGLE, DIVORCED, WIDOWED, SEPARATED, PREFER_NOT_TO_SAY
```

### KidsPreference
```
WANTS_KIDS, DOESNT_WANT_KIDS, HAS_KIDS, HAS_KIDS_WANTS_MORE,
HAS_KIDS_DOESNT_WANT_MORE, OPEN_TO_KIDS, NOT_SURE, PREFER_NOT_TO_SAY
```

### SmokingHabits
```
NON_SMOKER, SOCIAL_SMOKER, REGULAR_SMOKER, TRYING_TO_QUIT, PREFER_NOT_TO_SAY
```

### DrinkingHabits
```
NON_DRINKER, SOCIAL_DRINKER, MODERATE_DRINKER, REGULAR_DRINKER, PREFER_NOT_TO_SAY
```

### ExerciseFrequency
```
NEVER, RARELY, ONCE_A_WEEK, FEW_TIMES_A_WEEK, DAILY, MULTIPLE_TIMES_DAILY
```

### RelationshipGoal
```
CASUAL_DATING, SERIOUS_RELATIONSHIP, FRIENDSHIP, SOMETHING_CASUAL,
MARRIAGE, FIGURING_IT_OUT, PREFER_NOT_TO_SAY
```

### MBTI
```
ISFJ, ISFP, ISTJ, ISTP, INFJ, INFP, INTJ, INTP,
ESFJ, ESFP, ESTJ, ESTP, ENFJ, ENFP, ENTJ, ENTP
```

---

## 📁 Key Files & Locations

### Frontend (Next.js)

**Type Definitions**:
- `types/onboarding.d.ts` - All DTO type definitions
- `app/enums/user/userEnum.ts` - All enum definitions

**Server Actions** (API Communication):
- `app/serverActions/onboarding.ts` - All onboarding API calls
- `serverActions.ts` - Spotify API integration

**Onboarding Components**:
- `app/onboarding/components/steps/BasicProfileStep.tsx`
- `app/onboarding/components/steps/LocationStep.tsx`
- `app/onboarding/components/steps/PhotosStep.tsx`
- `app/onboarding/components/steps/MusicPreferencesStep.tsx`
- `app/onboarding/components/steps/LifestyleStep.tsx`
- `app/onboarding/components/steps/PersonalityStep.tsx`
- `app/onboarding/components/steps/DatingPreferencesStep.tsx`
- `app/onboarding/components/steps/PrivacySettingsStep.tsx`

**Utilities**:
- `lib/cloudinary.ts` - Photo upload/delete functions
- `app/hooks/useAutoSave.ts` - Auto-save functionality (2s debounce)

**API Routes**:
- `app/api/auth/[...nextauth]/route.ts` - NextAuth configuration
- `app/api/cloudinary/delete/route.ts` - Delete photos from Cloudinary
- `app/api/places/autocomplete/route.ts` - Google Places autocomplete

**Documentation**:
- `CLAUDE.md` - Complete backend API specification
- `PROJECT_STATUS.md` - This file (project status & sync document)

### Backend (Spring Boot)

**Expected Package Structure**:
```
src/main/java/com/dating/
├── controller/
│   └── OnboardingController.java
├── dto/
│   ├── request/
│   │   ├── BasicProfileRequestDto.java
│   │   ├── LocationDto.java
│   │   ├── PhotosRequestDto.java
│   │   ├── PhotoUploadRequestDto.java
│   │   ├── MusicPreferencesRequestDto.java
│   │   ├── LifestyleRequestDto.java
│   │   ├── PersonalityRequestDto.java
│   │   ├── DatingPreferencesRequestDto.java
│   │   └── PrivacySettingsRequestDto.java
│   └── response/
│       ├── CompleteProfileResponseDto.java
│       ├── OnboardingProgressDto.java
│       └── PhotoResponseDto.java
├── entity/
│   ├── User.java
│   ├── MusicPreferences.java
│   ├── Lifestyle.java
│   ├── Personality.java
│   ├── DatingPreferences.java
│   ├── PrivacySettings.java
│   └── Photo.java
├── enums/
│   ├── Gender.java
│   ├── SexualOrientation.java
│   ├── RegistrationStage.java
│   ├── ConcertFrequency.java
│   ├── MusicImportance.java
│   ├── EducationLevel.java
│   ├── RelationshipStatus.java
│   ├── KidsPreference.java
│   ├── SmokingHabits.java
│   ├── DrinkingHabits.java
│   ├── ExerciseFrequency.java
│   ├── RelationshipGoal.java
│   └── MBTI.java
├── service/
│   └── OnboardingService.java
└── repository/
    ├── UserRepository.java
    ├── MusicPreferencesRepository.java
    ├── LifestyleRepository.java
    ├── PersonalityRepository.java
    ├── DatingPreferencesRepository.java
    ├── PrivacySettingsRepository.java
    └── PhotoRepository.java
```

---

## ✅ Implementation Status

### Frontend (Next.js) - ✅ Complete

- ✅ All 8 onboarding steps fully implemented
- ✅ TypeScript types aligned with backend DTOs
- ✅ All enums match backend specification
- ✅ Server actions configured with proper authentication
- ✅ Cloudinary photo upload working (after recent fix)
- ✅ Spotify OAuth integration active
- ✅ Google Maps location integration
- ✅ Auto-save functionality (BasicProfile, MusicPreferences)
- ✅ Form validation with Zod schemas
- ✅ Error handling and loading states
- ✅ Responsive UI with Tailwind CSS
- ✅ Progress tracking and navigation

### Backend (Spring Boot) - ✅ Complete

**See `BACKEND_PROJECT_STATUS.md` for complete backend documentation**

**Implemented**:
- ✅ AuthController - Spotify OAuth login
- ✅ OnboardingController - All 8 PUT endpoints
- ✅ OnboardingController - All 8 GET endpoints
- ✅ UserController - Spotify data endpoints (profile, artists, tracks, genres)
- ✅ JPA entities with proper relationships
- ✅ Validation annotations matching frontend constraints
- ✅ JWT authentication filter
- ✅ Service layer with business logic
- ✅ Spotify token encryption and auto-refresh
- ✅ Database schema (PostgreSQL)

**Verified**:
1. ✅ All enum values match (SexualOrientation, RelationshipGoal, etc.)
2. ✅ Photo validation set to minimum 1 photo
3. ✅ Field constraints match (bio max 500 chars, etc.)
4. ✅ JWT authentication working correctly
5. ✅ CORS configured for localhost:3000

**Latest Additions**:
- ✅ `GET /api/v1/user/genres` - Fetch pre-selected genres for onboarding

---

## 🔄 Sync Mechanism for Multiple Claude Instances

**See `SYNC_STRATEGY.md` for complete synchronization protocol**

Since Claude instances can't directly sync, use this workflow:

### 1. Always Read Status Files Before Starting

**Frontend Claude (VS Code)**:
```
Read FRONTEND_PROJECT_STATUS.md and BACKEND_PROJECT_STATUS.md,
then help me with [FRONTEND TASK]
```

**Backend Claude (IntelliJ/VS Code)**:
```
Read BACKEND_PROJECT_STATUS.md and FRONTEND_PROJECT_STATUS.md,
then help me with [BACKEND TASK]
```

### 2. Update Your Status File After Major Changes

**Frontend Claude**: Updates `FRONTEND_PROJECT_STATUS.md`
**Backend Claude**: Updates `BACKEND_PROJECT_STATUS.md`

Whenever you make significant progress:
- Add updates to "Recent Work Completed" section
- Update implementation status checkboxes
- Add any new issues or blockers
- Reference the other status file if needed

### 3. Key Documentation Files

| File | Purpose | Who Updates |
|------|---------|-------------|
| `SYNC_STRATEGY.md` | Coordination protocol | Both (when workflow changes) |
| `FRONTEND_PROJECT_STATUS.md` | Frontend status | Frontend Claude |
| `BACKEND_PROJECT_STATUS.md` | Backend status | Backend Claude |
| `CLAUDE_SYNC_GUIDE.md` | General sync guide | Both |
| `[FEATURE]_DOCUMENTATION.md` | Feature-specific docs | Creator |

### 4. Cross-Reference Between Files

For backend work, always reference:
- `BACKEND_PROJECT_STATUS.md` - Backend implementation status
- `FRONTEND_PROJECT_STATUS.md` - What frontend expects
- Feature-specific documentation files

For frontend work, always reference:
- `FRONTEND_PROJECT_STATUS.md` - Frontend implementation status
- `BACKEND_PROJECT_STATUS.md` - Available API endpoints
- Feature-specific documentation files

---

## 🐛 Known Issues & Blockers

### None Currently! 🎉

All recent issues have been resolved:
- ✅ Cloudinary CORS 400 error - Fixed
- ✅ Type mismatches - Fixed
- ✅ Enum misalignments - Fixed
- ✅ Photo validation - Fixed

---

## 🚀 Next Steps / Roadmap

### Immediate (Testing Phase)

1. **Integration Testing**
    - Test complete onboarding flow with Spring Boot backend
    - Verify all 8 steps save correctly to database
    - Test photo upload end-to-end
    - Validate Spotify data prefill

2. **Error Handling**
    - Test network failure scenarios
    - Verify JWT token refresh
    - Test validation error responses

### Short Term (Core Features)

1. **User Profile Viewing**
    - Display complete user profile
    - Edit profile functionality
    - Photo management after onboarding

2. **Matching Algorithm**
    - Music taste compatibility scoring
    - Location-based filtering
    - Preference matching logic

3. **Discovery Feed**
    - Swipe interface (Tinder-style)
    - Like/Pass functionality
    - Match notifications

### Medium Term (Engagement Features)

1. **Messaging System**
    - Real-time chat (WebSocket)
    - Match conversations
    - Message notifications

2. **Spotify Integration**
    - Display top artists/tracks on profile
    - Music compatibility percentage
    - Shared playlists for matches

3. **Enhanced Matching**
    - Deal breaker filtering
    - Advanced preference algorithms
    - Music festival/concert matching

### Long Term (Growth Features)

1. **Social Features**
    - Concert buddy finder
    - Event discovery
    - Group chat for events

2. **Premium Features**
    - Unlimited likes
    - See who liked you
    - Advanced filters
    - Incognito mode

3. **Analytics & Insights**
    - Profile performance stats
    - Music taste analytics
    - Match compatibility insights

---

## 📝 Important Notes for Backend Development

### Authentication Flow

1. User authenticates with Spotify via NextAuth
2. Backend receives JWT token in Authorization header
3. Backend validates JWT and extracts user ID
4. All onboarding endpoints require valid JWT

### Data Validation Rules

**Critical Constraints**:
- `dateOfBirth`: Must be 18+ years old
- `photos`: Min 1, Max 6, exactly one `isPrimary: true`
- `bio`: Max 500 characters
- `minAge` < `maxAge`
- `favoriteGenres`: Array must not be empty
- `interestedInGenders`: Array must not be empty

### Database Relationships

```
User (1) ---> (1) MusicPreferences
User (1) ---> (1) Lifestyle
User (1) ---> (1) Personality
User (1) ---> (1) DatingPreferences
User (1) ---> (1) PrivacySettings
User (1) ---> (*) Photos
```

All relationships should be `@OneToOne` or `@OneToMany` with cascade operations.

### CORS Configuration

Frontend runs on: `http://127.0.0.1:3000`
Backend must allow:
```java
@CrossOrigin(origins = "http://127.0.0.1:3000", allowCredentials = "true")
```

Or configure globally in Spring Security.

---

## 🆘 Common Issues & Solutions

### Frontend Issues

**Issue**: "Not authenticated" error
**Solution**: Check NextAuth session, verify Spotify tokens are valid

**Issue**: Photo upload fails
**Solution**: Verify Cloudinary credentials, check upload preset is unsigned

**Issue**: Type errors in TypeScript
**Solution**: Ensure types match backend DTOs in `types/onboarding.d.ts`

### Backend Issues (Expected)

**Issue**: JWT validation fails
**Solution**: Verify JWT_SECRET matches between frontend and backend

**Issue**: CORS errors
**Solution**: Add frontend URL to CORS allowed origins

**Issue**: Validation errors
**Solution**: Check `@Valid` annotations and constraint messages match frontend

---

## 📞 Contact & Resources

**Documentation**:
- Frontend Types: `types/onboarding.d.ts`
- Backend Spec: `CLAUDE.md`
- API Endpoints: See "Architecture Overview" section above

**External Services**:
- Cloudinary Dashboard: https://cloudinary.com/console
- Spotify Developer: https://developer.spotify.com/dashboard
- Google Cloud Console: https://console.cloud.google.com

---

## 🔖 Version History

| Date | Version | Changes |
|------|---------|---------|
| 2025-11-26 | 1.0.0 | Initial project status document created |
| 2025-11-26 | 1.1.0 | Added Cloudinary fix, type alignment updates |

---

**Last Sync**: 2025-11-26
**Next Review**: Before major feature implementation

---

*This document serves as the source of truth for project status across all Claude instances (VS Code, IntelliJ, etc.). Update after significant changes.*
