# Mock Users Guide - Croatian Test Dataset

## Overview
This guide explains the **31 test users** created across Croatian cities with varying profile completion levels and music preferences for comprehensive matching algorithm testing.

## How to Load Mock Data

### Method 1: Run the Application
```bash
./mvnw.cmd spring-boot:run
```

The `UserSeedDataLoader` will automatically run on startup and create test users if fewer than 5 users exist in the database.

### Method 2: Clear and Reload
If you need to reload data:
```bash
# Stop the application
# Clear your database
# Restart the application
```

## User Distribution

### By Profile Completion Level
- **8 users** - FINISHED (100% complete profiles)
- **4 users** - PRIVACY_SETTINGS (87-92% complete)
- **4 users** - DATING_PREFERENCES (62-70% complete)
- **4 users** - MUSIC_PREFERENCES (42-50% complete)
- **3 users** - PHOTOS (25-30% complete)
- **3 users** - BASIC_PROFILE (12-18% complete)
- **3 users** - STARTED (0-5% complete)
- **2 users** - EDGE CASES (special test cases)

### By City
- **Zagreb**: 6 users
- **Split**: 4 users
- **Rijeka**: 4 users
- **Osijek**: 4 users
- **Zadar**: 2 users
- **Pula**: 3 users
- **Dubrovnik**: 3 users
- **Karlovac**: 2 users
- **Varaždin**: 2 users
- **Šibenik**: 2 users

---

## Complete Profiles (FINISHED - 100%)

### 1. Marko Horvat (Rock Enthusiast)
- **Email:** marko.horvat@test.hr
- **Password:** password123
- **Age:** 30 (born 1995)
- **Gender:** Male | Orientation: Straight
- **Location:** Zagreb, Croatia
- **Music:** Rock (0.95), Alternative Rock (0.90), Indie Rock (0.85), Punk Rock (0.75), Grunge (0.80)
- **Match Potential:** High with rock/indie fans, low with pop/jazz

### 2. Ana Kovačić (Pop Lover)
- **Email:** ana.kovacic@test.hr
- **Password:** password123
- **Age:** 27 (born 1998)
- **Gender:** Female | Orientation: Straight
- **Location:** Split, Croatia
- **Music:** Pop (0.95), Dance-Pop (0.90), Indie-Pop (0.80), Synth-Pop (0.75), K-Pop (0.70)
- **Match Potential:** High with pop fans, medium with indie-pop fans

### 3. Luka Babić (Electronic Music Fan)
- **Email:** luka.babic@test.hr
- **Password:** password123
- **Age:** 29 (born 1996)
- **Gender:** Male | Orientation: Bisexual
- **Location:** Rijeka, Croatia
- **Music:** Electronic (0.95), House (0.90), Techno (0.88), Ambient (0.85), Trance (0.80)
- **Match Potential:** High with electronic fans, low with most others

### 4. Petra Jurić (Indie Fan)
- **Email:** petra.juric@test.hr
- **Password:** password123
- **Age:** 28 (born 1997)
- **Gender:** Female | Orientation: Straight
- **Location:** Zadar, Croatia
- **Music:** Indie (0.95), Indie-Rock (0.90), Indie-Pop (0.85), Alternative (0.80), Singer-Songwriter (0.75)
- **Match Potential:** High with rock and pop-indie fans

### 5. Ivan Knežević (Jazz Lover)
- **Email:** ivan.knezevic@test.hr
- **Password:** password123
- **Age:** 37 (born 1988)
- **Gender:** Male | Orientation: Straight
- **Location:** Osijek, Croatia
- **Music:** Jazz (0.95), Smooth Jazz (0.90), Bebop (0.85), Blues (0.75), Soul (0.70)
- **Match Potential:** Low with most, some overlap with eclectic users

### 6. Sara Tomić (Eclectic Music Lover)
- **Email:** sara.tomic@test.hr
- **Password:** password123
- **Age:** 32 (born 1993)
- **Gender:** Female | Orientation: Bisexual
- **Location:** Pula, Croatia
- **Music:** Indie (0.85), Indie-Folk (0.80), Indie-Rock (0.75), Alternative (0.70), Electronic (0.65), Jazz (0.60)
- **Match Potential:** **HIGH with many users** - Best for general matching tests

### 7. Dino Šarić (Hip Hop Fan)
- **Email:** dino.saric@test.hr
- **Password:** password123
- **Age:** 31 (born 1994)
- **Gender:** Male | Orientation: Straight
- **Location:** Dubrovnik, Croatia
- **Music:** Hip-Hop (0.95), Trap (0.92), Drill (0.85), R&B (0.80), Soul (0.70)
- **Match Potential:** Low with most, some soul overlap with jazz

### 8. Tomislav Pavlović (Metalhead)
- **Email:** tomislav.pavlovic@test.hr
- **Password:** password123
- **Age:** 34 (born 1991)
- **Gender:** Male | Orientation: Straight
- **Location:** Karlovac, Croatia
- **Music:** Metal (0.95), Death Metal (0.90), Progressive Metal (0.88), Thrash Metal (0.82)
- **Match Potential:** **LOW with most users** - Best for low-match testing

---

## Nearly Complete Profiles (PRIVACY_SETTINGS - 87-92%)

### 9. Maja Petrović
- **Email:** maja.petrovic@test.hr
- **Age:** 29 | Female | Straight | Zagreb
- **Music:** Pop (0.85), Indie-Pop (0.80), Rock (0.70)

### 10. Filip Novak
- **Email:** filip.novak@test.hr
- **Age:** 33 | Male | Straight | Split
- **Music:** Electronic (0.90), House (0.85), Indie (0.65)

### 11. Ivana Marić
- **Email:** ivana.maric@test.hr
- **Age:** 26 | Female | Bisexual | Rijeka
- **Music:** Indie (0.90), Folk (0.80), Singer-Songwriter (0.75)

### 12. Mateo Božić
- **Email:** mateo.bozic@test.hr
- **Age:** 30 | Male | Straight | Osijek
- **Music:** Rock (0.88), Alternative Rock (0.82), Metal (0.70)

---

## Mid-Level Completion (DATING_PREFERENCES - 62-70%)

### 13. Antonija Lovrić
- **Email:** antonija.lovric@test.hr
- **Age:** 28 | Female | Straight | Zadar
- **Music:** Pop (0.80), Dance-Pop (0.75)

### 14. Karlo Vidović
- **Email:** karlo.vidovic@test.hr
- **Age:** 31 | Male | Straight | Pula
- **Music:** Hip-Hop (0.85), R&B (0.75), Pop (0.65)

### 15. Laura Jurković
- **Email:** laura.jurkovic@test.hr
- **Age:** 27 | Female | Straight | Varaždin
- **Music:** Indie (0.82), Alternative (0.78)

### 16. David Bošnjak
- **Email:** david.bosnjak@test.hr
- **Age:** 32 | Male | Gay | Dubrovnik
- **Music:** Electronic (0.88), Techno (0.85), House (0.82)

---

## Music Preferences Only (MUSIC_PREFERENCES - 42-50%)

### 17. Elena Perić
- **Email:** elena.peric@test.hr
- **Age:** 29 | Female | Straight | Šibenik
- **Music:** Jazz (0.90), Blues (0.80)

### 18. Roko Stipić
- **Email:** roko.stipic@test.hr
- **Age:** 30 | Male | Straight | Zagreb
- **Music:** Rock (0.92), Punk Rock (0.85), Alternative Rock (0.80)

### 19. Lucija Barišić
- **Email:** lucija.barisic@test.hr
- **Age:** 26 | Female | Lesbian | Split
- **Music:** Indie-Pop (0.88), Synth-Pop (0.82), Pop (0.75)

### 20. Jakov Martinović
- **Email:** jakov.martinovic@test.hr
- **Age:** 33 | Male | Straight | Rijeka
- **Music:** Metal (0.93), Thrash Metal (0.88)

---

## Early Stage (PHOTOS - 25-30%)

### 21. Mia Šimić
- **Email:** mia.simic@test.hr
- **Age:** 28 | Female | Straight | Osijek
- **Music:** None yet

### 22. Leon Grgić
- **Email:** leon.grgic@test.hr
- **Age:** 31 | Male | Straight | Karlovac
- **Music:** None yet

### 23. Nika Kralj
- **Email:** nika.kralj@test.hr
- **Age:** 27 | Female | Bisexual | Zadar
- **Music:** None yet

---

## Minimal Data (BASIC_PROFILE - 12-18%)

### 24. Bruno Matić
- **Email:** bruno.matic@test.hr
- **Age:** 29 | Male | Straight | Varaždin
- **Music:** None

### 25. Klara Filipović
- **Email:** klara.filipovic@test.hr
- **Age:** 26 | Female | Straight | Pula
- **Music:** None

### 26. Nikola Vukić
- **Email:** nikola.vukic@test.hr
- **Age:** 32 | Male | Straight | Šibenik
- **Music:** None

---

## Just Registered (STARTED - 0-5%)

### 27. Tena Pavić
- **Email:** tena.pavic@test.hr
- **Age:** 28 | Female | Straight | Zagreb
- **Music:** None

### 28. Ante Radić
- **Email:** ante.radic@test.hr
- **Age:** 30 | Male | Straight | Split
- **Music:** None

### 29. Dora Belić
- **Email:** dora.belic@test.hr
- **Age:** 27 | Female | Straight | Rijeka
- **Music:** None

---

## Edge Cases (Special Test Users)

### 30. Josip Novosel (No Preferences)
- **Email:** josip.novosel@test.hr
- **Age:** 31 | Male | Straight | Dubrovnik
- **Profile Completion:** 75% (PERSONALITY stage)
- **Music:** **NONE** - Tests zero-overlap matching

### 31. Nikolina Đurić (One Genre Only)
- **Email:** nikolina.duric@test.hr
- **Age:** 28 | Female | Straight | Osijek
- **Profile Completion:** 55% (LIFESTYLE stage)
- **Music:** Rock (1.0) - Tests single-genre matching

### 32. Antonio Herceg (15 Genres)
- **Email:** antonio.herceg@test.hr
- **Age:** 34 | Male | Straight | Zagreb
- **Profile Completion:** 95% (PRIVACY_SETTINGS)
- **Music:** 15 genres - Tests broad taste matching

---

## Testing Scenarios

### High Match Scores (Expected 70%+)

```bash
# Rock fans
Marko Horvat (rock) + Petra Jurić (indie-rock) = ~75-80%
Marko Horvat (rock) + Roko Stipić (rock/punk) = ~85-90%

# Pop fans
Ana Kovačić (pop) + Antonija Lovrić (pop/dance-pop) = ~75-80%
Ana Kovačić (pop) + Lucija Barišić (indie-pop/synth-pop) = ~65-70%

# Electronic fans
Luka Babić (electronic/house) + Filip Novak (electronic/house) = ~85-90%
Luka Babić (electronic) + David Bošnjak (electronic/techno) = ~80-85%

# Eclectic user (matches well with many)
Sara Tomić (eclectic) + Petra Jurić (indie) = ~75-80%
Sara Tomić (eclectic) + Ivana Marić (indie/folk) = ~80-85%
```

### Low Match Scores (Expected <40%)

```bash
# Metal vs Pop
Tomislav Pavlović (metal) + Ana Kovačić (pop) = ~10-15%

# Jazz vs Hip Hop
Ivan Knežević (jazz) + Dino Šarić (hip-hop) = ~15-20%

# No preferences
Josip Novosel (none) + anyone = 0%

# Electronic vs Jazz
Luka Babić (electronic) + Ivan Knežević (jazz) = ~20-25%
```

### Medium Match Scores (Expected 40-70%)

```bash
# Some overlap
Sara Tomić (eclectic+jazz) + Ivan Knežević (jazz) = ~50-55%
Petra Jurić (indie) + Ana Kovačić (indie-pop) = ~60-65%
Filip Novak (electronic+indie) + Petra Jurić (indie) = ~50-55%
```

### Edge Case Testing

```bash
# No preferences
Josip Novosel (no music) + anyone = Should handle gracefully (0% score)

# Single genre overlap
Nikolina Đurić (rock only) + Marko Horvat (rock + more) = ~45-50%

# Many genres
Antonio Herceg (15 genres) + Sara Tomić (eclectic) = ~65-70%
Antonio Herceg (15 genres) + Marko Horvat (rock focused) = ~40-45%
```

---

## API Testing Examples

### Get Match Score Between Users
```bash
# Find user IDs first (check logs or database)
curl "http://localhost:8080/api/test/phase3/score?userId1=USER1_ID&userId2=USER2_ID"
```

### Get Potential Matches for User
```bash
# Sara Tomić should match well with many users
curl "http://localhost:8080/api/test/phase3/potential?userId=SARA_ID&limit=10&minScore=50"
```

### Test Mutual Match Flow
```bash
# Step 1: Marko likes Petra
curl -X POST "http://localhost:8080/api/test/phase3/swipe?swiperId=MARKO_ID&swipedId=PETRA_ID&action=like&score=78"

# Step 2: Petra likes Marko back (creates match!)
curl -X POST "http://localhost:8080/api/test/phase3/swipe?swiperId=PETRA_ID&swipedId=MARKO_ID&action=like&score=78"

# Step 3: Check their matches
curl "http://localhost:8080/api/test/phase3/matches?userId=MARKO_ID"
```

---

## Expected Match Score Matrix (Approximate)

| User → | Marko (Rock) | Ana (Pop) | Luka (Elec) | Petra (Indie) | Ivan (Jazz) | Sara (Ecl) | Dino (Hip) | Toma (Metal) |
|--------|--------------|-----------|-------------|---------------|-------------|------------|------------|--------------|
| **Marko (Rock)** | 100 | 25 | 30 | 80 | 20 | 65 | 15 | 45 |
| **Ana (Pop)** | 25 | 100 | 35 | 65 | 15 | 55 | 35 | 10 |
| **Luka (Elec)** | 30 | 35 | 100 | 45 | 20 | 50 | 25 | 20 |
| **Petra (Indie)** | 80 | 65 | 45 | 100 | 25 | 75 | 20 | 30 |
| **Ivan (Jazz)** | 20 | 15 | 20 | 25 | 100 | 50 | 25 | 15 |
| **Sara (Ecl)** | 65 | 55 | 50 | 75 | 50 | 100 | 30 | 35 |
| **Dino (Hip)** | 15 | 35 | 25 | 20 | 25 | 30 | 100 | 10 |
| **Toma (Metal)** | 45 | 10 | 20 | 30 | 15 | 35 | 10 | 100 |

---

## Profile Completion Testing

### Test Different Completion Levels

```bash
# Full profile (100%)
curl "http://localhost:8080/api/test/phase3/potential?userId=MARKO_ID&limit=5"

# Nearly complete (90%)
curl "http://localhost:8080/api/test/phase3/potential?userId=MAJA_ID&limit=5"

# Mid-level (65%)
curl "http://localhost:8080/api/test/phase3/potential?userId=ANTONIJA_ID&limit=5"

# Music only (45%)
curl "http://localhost:8080/api/test/phase3/potential?userId=ELENA_ID&limit=5"

# No music (0%)
curl "http://localhost:8080/api/test/phase3/potential?userId=MIA_ID&limit=5"
# Should return empty or very low scores
```

---

## Location Testing (All Users in Croatia)

All users are located in Croatian cities with real GPS coordinates:

- **Zagreb (Capital)**: 45.8150°N, 15.9819°E
- **Split**: 43.5081°N, 16.4402°E
- **Rijeka**: 45.3271°N, 14.4422°E
- **Osijek**: 45.5550°N, 18.6955°E
- **Zadar**: 44.1194°N, 15.2314°E
- **Pula**: 44.8666°N, 13.8496°E
- **Dubrovnik**: 42.6507°N, 18.0944°E
- **Karlovac**: 45.4870°N, 15.5478°E
- **Varaždin**: 46.3044°N, 16.3366°E
- **Šibenik**: 43.7350°N, 15.9000°E

**Location-based filtering can be added in future phases.**

---

## Important Notes

⚠️ **All test users have the same password:** `password123`

⚠️ **This is for TESTING ONLY** - Remove UserSeedDataLoader before production!

⚠️ **Database Impact:** The loader only runs if user count ≤ 4.

⚠️ **Language:** All users have language set to "hr" (Croatian)

---

## Troubleshooting

### "Users already exist" message
- The seed loader has already run
- Check user count: `SELECT COUNT(*) FROM users;`
- To reload: clear database or delete users

### Missing genres
- Ensure GenreSeedDataLoader ran first
- Check: `SELECT COUNT(*) FROM canonical_genres;` (should be 100+)

### No matches found
- Ensure users have genre preferences
- Check minimum score threshold isn't too high
- Verify excludeSwiped isn't filtering everyone out

---

## Summary

**31 diverse Croatian users** across:
- 10 cities
- 7 completion levels
- 8 music taste profiles
- Multiple genders and orientations
- Ages 26-37

**Perfect for testing:**
- High, medium, and low match scenarios
- Profile completion filtering
- Genre preference variations
- Edge cases (no preferences, one genre, many genres)
- Mutual matching flow
- Location-based features (future)

🎯 **Ready to test the matching algorithm with real-world diversity!**
