# Phase 1: Database Foundation - Implementation Summary

## What Was Created

### 1. **New Entities** (5 total)
Located in: `src/main/java/com/example/dating/models/matching/dao/`

#### CanonicalGenre
- **Purpose**: Master list of normalized music genres
- **Key Features**:
  - Hierarchical structure (parent-child relationships)
  - Spotify alias mapping for fuzzy matching
  - Primary/secondary classification for UI
  - Display order for sorting

#### UserGenrePreference
- **Purpose**: Links users to genres with preference weights
- **Key Features**:
  - Weight (0.0-1.0) indicates preference strength
  - Source tracking (spotify_derived, manual_selection, inferred, hybrid)
  - Confidence score for data quality
  - Rank for "top N genres" queries

#### UserMatchScore
- **Purpose**: Pre-computed compatibility scores between users
- **Key Features**:
  - Individual dimension scores (music, personality, lifestyle, location)
  - Overall weighted score (0-100)
  - Match explanation (JSON) for UI display
  - Algorithm version tracking for A/B testing

#### Match
- **Purpose**: Mutual matches between users (both swiped right)
- **Key Features**:
  - Bidirectional relationship (userA/userB)
  - Status tracking (active, unmatched, deleted, blocked)
  - Conversation metrics (started, message count, timestamps)
  - Match source tracking (mutual_swipe, algorithm_boost, super_like)

#### UserSwipe
- **Purpose**: Tracks all swipe actions for learning and filtering
- **Key Features**:
  - Action type (like, pass, super_like, block)
  - Match score at swipe time (for behavioral learning)
  - Dimension scores snapshot (to analyze what drives swipes)
  - Platform tracking (iOS, Android, web)

### 2. **New Repositories** (5 total)
Located in: `src/main/java/com/example/dating/repositories/`

All repositories extend `JpaRepository` and include custom query methods:

#### CanonicalGenreRepository
- `findByName()` - Exact name lookup
- `findAllPrimaryGenres()` - Get UI-displayable genres
- `searchByName()` - Fuzzy search for autocomplete
- `findBySpotifyAlias()` - Map Spotify genres to canonical

#### UserGenrePreferenceRepository
- `findByUserIdOrderByWeightDesc()` - Get user's genres sorted by preference
- `findTopNByUserId()` - Get user's top N genres
- `deleteByUserIdAndSource()` - Refresh Spotify data

#### UserMatchScoreRepository
- `findTopMatchesByUserId()` - Main matching query (paginated, sorted)
- `findMatchesAboveThreshold()` - Filter by minimum score
- `findStaleMatches()` - Find scores needing recalculation

#### MatchRepository
- `findMatchBetweenUsers()` - Bidirectional match lookup
- `findActiveMatchesByUserId()` - Get user's current matches
- `findMatchesWithConversations()` - Filter by conversation status
- `areUsersMatched()` - Quick boolean check

#### UserSwipeRepository
- `hasUserSwipedOn()` - Prevent duplicate profiles
- `findAllSwipedUserIds()` - Exclude from future matches
- `findUsersWhoLiked()` - Check for mutual likes
- `calculateSwipeThroughRate()` - Analytics

### 3. **Genre Seed Data Loader**
Located in: `src/main/java/com/example/dating/config/GenreSeedDataLoader.java`

- **Runs on application startup** (only if database is empty)
- **150+ genres** organized hierarchically
- **Based on Spotify's genre taxonomy**
- **Covers**:
  - Top-level genres (Rock, Pop, Hip-Hop, Electronic, Jazz, Classical, etc.)
  - Subgenres (Indie Rock, Synth Pop, Trap, House, etc.)
  - Aliases for fuzzy matching ("indie rock", "indie_rock", "indie")

### 4. **Optional Flyway Migration** (for future use)
Located in: `src/main/resources/db/migration/V1_0__Create_Matching_Tables.sql`

- Complete SQL schema definition
- Can be used when switching from `ddl-auto: update` to Flyway
- Includes comments and constraints

---

## How It Works

### Current Setup (Development)
Your app uses **`spring.jpa.hibernate.ddl-auto: update`**, which means:
- ✅ Hibernate automatically creates tables from entity classes
- ✅ No manual SQL needed
- ✅ Schema updates automatically when entities change
- ⚠️ Not recommended for production (use Flyway instead)

### What Happens on Startup

1. **Hibernate Creates Tables**
   - Reads entity classes
   - Generates `CREATE TABLE` statements
   - Creates indexes and foreign keys
   - Tables: `canonical_genres`, `user_genre_preferences`, `user_match_scores`, `matches`, `user_swipes`

2. **GenreSeedDataLoader Runs**
   - Checks if `canonical_genres` table is empty
   - If empty: Seeds 150+ genres with relationships
   - If not empty: Skips (won't duplicate data)

---

## How to Test Phase 1

### Step 1: Start Your Application
```bash
mvn clean install
mvn spring-boot:run
```

### Step 2: Check Database Tables
Connect to PostgreSQL and verify tables exist:

```sql
-- List all tables
\dt

-- Check canonical_genres
SELECT COUNT(*) FROM canonical_genres;
-- Should return ~150+

SELECT * FROM canonical_genres WHERE is_primary = true ORDER BY display_order;
-- Should show main genres (Rock, Pop, Hip-Hop, etc.)

-- Check relationships
SELECT
    child.display_name as "Child Genre",
    parent.display_name as "Parent Genre"
FROM canonical_genres child
LEFT JOIN canonical_genres parent ON child.parent_genre_id = parent.id
WHERE child.parent_genre_id IS NOT NULL
ORDER BY parent.display_name, child.display_name;
-- Should show hierarchy (Indie Rock → Rock, etc.)
```

### Step 3: Test Repositories

Create a simple test controller or use Spring Boot Test:

```java
@SpringBootTest
class MatchingRepositoryTest {

    @Autowired
    private CanonicalGenreRepository genreRepository;

    @Test
    void testGenresSeeded() {
        long count = genreRepository.count();
        assert count > 100;
    }

    @Test
    void testFindByName() {
        Optional<CanonicalGenre> rock = genreRepository.findByName("rock");
        assert rock.isPresent();
        assert rock.get().getDisplayName().equals("Rock");
    }

    @Test
    void testSearchGenres() {
        List<CanonicalGenre> results = genreRepository.searchByName("indie");
        assert results.size() > 5; // indie, indie-rock, indie-pop, etc.
    }

    @Test
    void testHierarchy() {
        CanonicalGenre rock = genreRepository.findByName("rock").orElseThrow();
        List<CanonicalGenre> children = genreRepository.findByParentGenre(rock);
        assert children.size() > 5; // indie-rock, alt-rock, punk-rock, etc.
    }
}
```

### Step 4: Test Creating User Genre Preferences

```java
@Test
@Transactional
void testCreateUserGenrePreference() {
    // Get a user
    UserEntity user = userRepository.findById("some-user-id").orElseThrow();

    // Get a genre
    CanonicalGenre indieRock = genreRepository.findByName("indie-rock").orElseThrow();

    // Create preference
    UserGenrePreference pref = UserGenrePreference.builder()
        .user(user)
        .genre(indieRock)
        .weight(0.8)
        .source("manual_selection")
        .confidence(1.0)
        .rank(1)
        .build();

    UserGenrePreference saved = genrePreferenceRepository.save(pref);
    assert saved.getId() != null;

    // Retrieve
    List<UserGenrePreference> userPrefs =
        genrePreferenceRepository.findByUserIdOrderByWeightDesc(user.getId());
    assert userPrefs.size() == 1;
    assert userPrefs.get(0).getGenre().getName().equals("indie-rock");
}
```

---

## What's Next?

### Phase 2: Genre Extraction & Storage
**Goal**: Populate genre preferences for all existing usersLe

**Tasks**:
1. Create `GenreExtractionService`
2. Implement Spotify genre extraction (from top artists)
3. Implement manual genre extraction (from `UserMusicPreferences.favoriteGenres`)
4. Create migration script to process existing users

### Phase 3: Scoring Services
**Goal**: Calculate match scores between users

**Tasks**:
1. Create `MusicScoringService` (genre Jaccard similarity)
2. Create `PersonalityScoringService` (MBTI + interests)
3. Create `LifestyleScoringService` (habits compatibility)
4. Create `LocationScoringService` (distance-based)
5. Create `MatchingService` (orchestrates all scoring)

---

## Migration to Flyway (Optional - For Production)

When ready to use proper database migrations:

### Step 1: Add Flyway Dependency
Add to `pom.xml`:
```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
```

### Step 2: Update application.yml
```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate  # Change from "update" to "validate"
  flyway:
    enabled: true
    baseline-on-migrate: true
    locations: classpath:db/migration
```

### Step 3: Run Flyway
```bash
mvn flyway:migrate
```

The migration file `V1_0__Create_Matching_Tables.sql` is already created and ready to use.

---

## Troubleshooting

### Tables Not Created
- Check that application started successfully
- Check logs for Hibernate DDL statements (`show-sql: true`)
- Verify database connection in `application.yml`

### Genres Not Seeded
- Check if `GenreSeedDataLoader` ran (look for log: "Seeding canonical genres...")
- If it says "already exist", genres are already there
- To re-seed: Delete all from `canonical_genres` and restart

### Foreign Key Errors
- Ensure `users` table exists (from your existing entities)
- Check that user IDs are valid UUIDs
- Verify cascade settings are correct

---

## Summary

✅ **5 new entities** created for matching algorithm
✅ **5 new repositories** with custom query methods
✅ **150+ genres** seeded hierarchically
✅ **Database schema** ready via Hibernate auto-DDL
✅ **Flyway migration** prepared for future use
✅ **Ready for Phase 2** (Genre Extraction)

**Next Steps**: Test the repositories, verify genres are seeded, then proceed to Phase 2.
