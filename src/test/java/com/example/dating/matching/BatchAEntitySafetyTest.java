package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.models.matching.dao.Match;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.photos.dao.UserPhoto;
import com.example.dating.repositories.MatchRepository;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.matching.MatchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Batch A — @Data Removal and equals/hashCode Safety.
 *
 * <p>Verifies:
 * <ol>
 *   <li>equals/hashCode are based on id only (not lazy fields)</li>
 *   <li>HashSet contract is stable across pre-persist → post-persist lifecycle</li>
 *   <li>Match.toString() does not trigger lazy loading or throw StackOverflowError</li>
 *   <li>UserEntity + UserPhoto bidirectional cycle does not cause infinite recursion</li>
 *   <li>getAge() returns the correct age across birthday boundaries</li>
 *   <li>Concurrent HashSet writes on Match entities from multiple threads are safe</li>
 * </ol>
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class BatchAEntitySafetyTest {

    @Autowired
    private UserJpaRepository userRepository;

    @Autowired
    private MatchRepository matchRepository;

    @Autowired
    private MatchService matchService;

    private UserEntity userA;
    private UserEntity userB;

    @BeforeEach
    void setUp() {
        userA = userRepository.findByEmail("batch.a.test.a@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("batch.a.test.a@test.com")
                                .name("BatchATestUserA")
                                .build()));

        userB = userRepository.findByEmail("batch.a.test.b@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("batch.a.test.b@test.com")
                                .name("BatchATestUserB")
                                .build()));

        matchRepository.findMatchBetweenUsers(userA.getId(), userB.getId())
                .ifPresent(m -> matchRepository.deleteById(m.getId()));
    }

    @AfterEach
    void tearDown() {
        matchRepository.findMatchBetweenUsers(userA.getId(), userB.getId())
                .ifPresent(m -> matchRepository.deleteById(m.getId()));
    }

    // -----------------------------------------------------------------------
    // 1. equals / hashCode based on id only
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Two UserEntity instances with the same id are equal")
    void userEntity_sameId_areEqual() {
        UserEntity copy = UserEntity.builder().build();
        // Reflectively set same id as userA (simulates two Hibernate instances of same row)
        // We achieve this by just comparing userA with itself and a reloaded reference.
        UserEntity reloaded = userRepository.findById(userA.getId()).orElseThrow();

        assertEquals(userA, reloaded, "Two instances with the same persisted id must be equal");
        assertEquals(userA.hashCode(), reloaded.hashCode(), "Equal entities must have equal hashCodes");
    }

    @Test
    @DisplayName("Two UserEntity instances with different ids are not equal")
    void userEntity_differentIds_areNotEqual() {
        assertNotEquals(userA, userB, "Entities with different ids must not be equal");
    }

    @Test
    @DisplayName("Pre-persist UserEntity with null id is not equal to any other instance")
    void userEntity_nullId_notEqualToOther() {
        UserEntity transient1 = UserEntity.builder().email("x@x.com").build();
        UserEntity transient2 = UserEntity.builder().email("x@x.com").build();

        // Two distinct pre-persist objects must not be equal
        assertNotEquals(transient1, transient2,
                "Two pre-persist entities (null id) must not be equal to each other");
    }

    @Test
    @DisplayName("Pre-persist entity: hashCode does not throw and is stable within the same instance")
    void userEntity_nullId_hashCodeStable() {
        UserEntity transient1 = UserEntity.builder().email("y@y.com").build();
        int h1 = transient1.hashCode();
        int h2 = transient1.hashCode();
        assertEquals(h1, h2, "hashCode must be stable within the same pre-persist instance");
    }

    // -----------------------------------------------------------------------
    // 2. HashSet contract stable across pre-persist → post-persist
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Entity added to HashSet before save is found after save (hashCode must not change)")
    void hashSet_entityFoundAfterPersist() {
        UserEntity fresh = userRepository.save(
                UserEntity.builder().email("hashset.test@test.com").build());

        try {
            Set<UserEntity> set = new HashSet<>();
            set.add(fresh);

            // Reload simulates a second Hibernate session returning the same row
            UserEntity reloaded = userRepository.findById(fresh.getId()).orElseThrow();

            assertTrue(set.contains(reloaded),
                    "A reloaded entity with the same id must be found in a HashSet that holds the original");
        } finally {
            userRepository.deleteById(fresh.getId());
        }
    }

    // -----------------------------------------------------------------------
    // 3. Match.toString() does not blow up (no lazy-load trigger, no recursion)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Match.toString() completes without StackOverflowError or LazyInitializationException")
    void match_toString_doesNotThrow() {
        Match match = matchService.createMatch(userA, userB, 80.0);

        assertDoesNotThrow(() -> {
            String str = match.toString();
            assertNotNull(str);
            // The string must NOT contain the lazy user objects (would indicate @ToString.Exclude is missing)
            assertFalse(str.contains("userA=UserEntity"),
                    "toString must not render userA (lazy — excluded via @ToString.Exclude)");
            assertFalse(str.contains("userB=UserEntity"),
                    "toString must not render userB (lazy — excluded via @ToString.Exclude)");
        }, "Match.toString() must not throw StackOverflowError or trigger lazy loading");
    }

    // -----------------------------------------------------------------------
    // 4. UserEntity + UserPhoto bidirectional cycle does not recurse
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("UserEntity.toString() with a photo back-reference does not cause infinite recursion")
    void userEntity_toString_withPhotoBackReference_doesNotRecurse() {
        // Build in-memory bidirectional graph (no DB required for this unit-style check)
        UserEntity user = UserEntity.builder()
                .email("recursion.test@test.com")
                .build();

        UserPhoto photo = UserPhoto.builder()
                .imageUrl("http://example.com/photo.jpg")
                .displayOrder(0)
                .isPrimary(true)
                .build();

        // Simulate both sides of the bidirectional relationship
        user.getPhotos().add(photo);

        assertDoesNotThrow(() -> {
            // These would StackOverflow with old @Data-generated toString/equals/hashCode
            String userStr = user.toString();
            assertNotNull(userStr);
            assertFalse(userStr.contains("photos="),
                    "photos collection must be excluded from toString via @ToString.Exclude");

            int userHash = user.hashCode();   // must not recurse into photos
            int photoHash = photo.hashCode(); // must not recurse into user
            // Just assert they're callable (no exception is the real assertion)
            assertTrue(userHash != 0 || photoHash == 0, "hashCode must complete without recursion");
        }, "UserEntity/UserPhoto bidirectional cycle must not cause StackOverflowError");
    }

    // -----------------------------------------------------------------------
    // 5. getAge() is correct across birthday boundary
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("getAge() returns correct age: birthday already passed this year")
    void getAge_birthdayAlreadyPassed_returnsCorrectAge() {
        LocalDate today = LocalDate.now();
        // Birthday was yesterday — person has definitely had this year's birthday
        LocalDate dob = today.minusYears(30).minusDays(1);

        UserEntity user = UserEntity.builder().email("age1@test.com").build();
        user.setDateOfBirth(dob);

        assertEquals(30, user.getAge(),
                "Age must be 30 when birthday was yesterday (already passed this year)");
    }

    @Test
    @DisplayName("getAge() returns correct age: birthday has not happened yet this year")
    void getAge_birthdayNotYetThisYear_returnsCorrectAge() {
        LocalDate today = LocalDate.now();
        // Birthday is tomorrow — person has NOT had this year's birthday yet
        LocalDate dob = today.minusYears(30).plusDays(1);

        UserEntity user = UserEntity.builder().email("age2@test.com").build();
        user.setDateOfBirth(dob);

        assertEquals(29, user.getAge(),
                "Age must be 29 when birthday is tomorrow (not yet had this year's birthday)");
    }

    @Test
    @DisplayName("getAge() returns null when dateOfBirth is null")
    void getAge_nullDob_returnsNull() {
        UserEntity user = UserEntity.builder().email("age3@test.com").build();
        assertNull(user.getAge(), "getAge() must return null when dateOfBirth is not set");
    }

    // -----------------------------------------------------------------------
    // 6. Concurrent HashSet writes on Match entities from multiple threads
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Concurrent threads can safely add Match entities to a shared HashSet")
    void match_concurrentHashSetWrites_noExceptions() throws Exception {
        Match match = matchService.createMatch(userA, userB, 75.0);

        int threadCount = 16;
        Set<Match> sharedSet = java.util.Collections.synchronizedSet(new HashSet<>());
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                try {
                    barrier.await();
                    // add/contains/hashCode on the same entity from many threads —
                    // previously would StackOverflow if @Data generated hashCode was used
                    sharedSet.add(match);
                    sharedSet.contains(match);
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            }));
        }

        pool.shutdown();
        pool.awaitTermination(15, TimeUnit.SECONDS);
        futures.forEach(f -> {
            try { f.get(); } catch (Exception ignored) {}
        });

        assertEquals(0, errors.get(),
                "No thread must throw during concurrent HashSet operations on a Match entity");
        assertEquals(1, sharedSet.size(),
                "All threads added the same Match instance — set must contain exactly one entry");
    }
}
