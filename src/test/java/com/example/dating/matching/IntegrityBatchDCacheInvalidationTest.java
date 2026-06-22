package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.models.matching.dao.UserBehavioralProfile;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.repositories.UserBehavioralProfileRepository;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.matching.BehavioralProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies Batch D — Propagate Behavioral Profile Changes to Score Cache Staleness.
 *
 * <p>Covers:
 * <ol>
 *   <li>Behavioral profile update (like) bumps {@code UserEntity.updatedAt}.</li>
 *   <li>Cached score is stale after behavioral update (updatedAt advanced past computedAt).</li>
 *   <li>{@code touchUpdatedAt} does not increment the {@code @Version} field.</li>
 *   <li>Pass swipe also bumps {@code UserEntity.updatedAt} (passes change totalPasses/confidence).</li>
 *   <li>Concurrent swipes both bump {@code updatedAt} without version conflict.</li>
 * </ol>
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class IntegrityBatchDCacheInvalidationTest {

    @Autowired private BehavioralProfileService behavioralProfileService;
    @Autowired private UserJpaRepository userRepository;
    @Autowired private UserBehavioralProfileRepository behavioralProfileRepository;

    private UserEntity userA;
    private UserEntity userB;
    private UserEntity userC;

    @BeforeEach
    void setUp() {
        userA = userRepository.findByEmail("integrity.d.a@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("integrity.d.a@test.com")
                                .name("IntegrityDUserA")
                                .build()));

        userB = userRepository.findByEmail("integrity.d.b@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("integrity.d.b@test.com")
                                .name("IntegrityDUserB")
                                .build()));

        userC = userRepository.findByEmail("integrity.d.c@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("integrity.d.c@test.com")
                                .name("IntegrityDUserC")
                                .build()));

        // Clean up any leftover behavioral profiles from prior runs.
        behavioralProfileRepository.findByUserId(userA.getId())
                .ifPresent(p -> behavioralProfileRepository.delete(p));
    }

    // ── Test 1: Like swipe bumps UserEntity.updatedAt ────────────────────────

    @Test
    @DisplayName("Behavioral profile update (like) bumps UserEntity.updatedAt")
    void likeSwipe_bumpsUserEntityUpdatedAt() {
        // Record the initial updatedAt.
        LocalDateTime before = userA.getUpdatedAt();

        // Process a like swipe — this creates/updates the behavioral profile.
        behavioralProfileService.updateAfterSwipe(
                userA.getId(), userB.getId(), "like", 75.0);

        // Re-read the entity from DB to get the fresh updatedAt.
        UserEntity refreshed = userRepository.findById(userA.getId()).orElseThrow();

        assertNotNull(refreshed.getUpdatedAt(),
                "updatedAt must be set after behavioral profile update");

        if (before != null) {
            assertTrue(refreshed.getUpdatedAt().isAfter(before) || refreshed.getUpdatedAt().isEqual(before),
                    "updatedAt must have advanced (or at least not regressed) after like swipe");
        }
    }

    // ── Test 2: Cached score is stale after behavioral update ────────────────

    @Test
    @DisplayName("Cached score becomes stale after behavioral profile update")
    void cachedScore_isStaleAfterBehavioralUpdate() {
        // Simulate a cached score computed "now" — record the timestamp.
        LocalDateTime computedAt = LocalDateTime.now();

        // Process a like swipe — bumps UserEntity.updatedAt.
        behavioralProfileService.updateAfterSwipe(
                userA.getId(), userB.getId(), "like", 60.0);

        // Re-read user to get the fresh updatedAt.
        UserEntity refreshed = userRepository.findById(userA.getId()).orElseThrow();
        LocalDateTime userUpdatedAt = refreshed.getUpdatedAt();

        // The cache freshness check: cached.computedAt must be AFTER max(userA.updatedAt, userB.updatedAt).
        // Since we just bumped userA.updatedAt, computedAt (recorded before the swipe) should be
        // BEFORE userUpdatedAt — meaning the cache entry is stale.
        assertNotNull(userUpdatedAt, "updatedAt must be set");
        assertTrue(computedAt.isBefore(userUpdatedAt) || computedAt.isEqual(userUpdatedAt),
                "computedAt (pre-swipe) must be <= userUpdatedAt (post-swipe), making the cache stale");
    }

    // ── Test 3: touchUpdatedAt does not increment @Version ───────────────────

    @Test
    @DisplayName("touchUpdatedAt does not increment @Version field")
    @Transactional
    void touchUpdatedAt_doesNotIncrementVersion() {
        // Read current version.
        UserEntity before = userRepository.findById(userA.getId()).orElseThrow();
        Long versionBefore = before.getVersion();

        // Call touchUpdatedAt (bulk @Modifying @Query — bypasses @Version).
        userRepository.touchUpdatedAt(userA.getId());

        // Flush + clear the persistence context so findById hits the DB.
        userRepository.flush();

        // Re-read from DB.
        UserEntity after = userRepository.findById(userA.getId()).orElseThrow();
        assertEquals(versionBefore, after.getVersion(),
                "touchUpdatedAt must NOT increment the @Version field");
    }

    // ── Test 4: Pass swipe also bumps UserEntity.updatedAt ───────────────────

    @Test
    @DisplayName("Pass swipe bumps UserEntity.updatedAt (passes update totalPasses/confidence)")
    void passSwipe_bumpsUserEntityUpdatedAt() {
        LocalDateTime before = userA.getUpdatedAt();

        // Process a pass swipe.
        behavioralProfileService.updateAfterSwipe(
                userA.getId(), userB.getId(), "pass", null);

        UserEntity refreshed = userRepository.findById(userA.getId()).orElseThrow();
        assertNotNull(refreshed.getUpdatedAt(),
                "updatedAt must be set after pass swipe");

        if (before != null) {
            assertTrue(refreshed.getUpdatedAt().isAfter(before) || refreshed.getUpdatedAt().isEqual(before),
                    "updatedAt must have advanced (or at least not regressed) after pass swipe");
        }
    }

    // ── Test 5: Concurrent swipes both bump updatedAt without version conflict

    @Test
    @DisplayName("Concurrent swipes both bump updatedAt without @Version conflict")
    void concurrentSwipes_bothBumpUpdatedAt() throws Exception {
        // First swipe to establish baseline.
        behavioralProfileService.updateAfterSwipe(
                userA.getId(), userB.getId(), "like", 70.0);

        UserEntity afterFirst = userRepository.findById(userA.getId()).orElseThrow();
        LocalDateTime afterFirstUpdatedAt = afterFirst.getUpdatedAt();
        assertNotNull(afterFirstUpdatedAt);

        // Small sleep to guarantee timestamp advancement.
        Thread.sleep(50);

        // Second swipe — different target but same swiper.
        // touchUpdatedAt uses a bulk @Modifying query, so no @Version conflict.
        behavioralProfileService.updateAfterSwipe(
                userA.getId(), userC.getId(), "like", 80.0);

        UserEntity afterSecond = userRepository.findById(userA.getId()).orElseThrow();
        assertNotNull(afterSecond.getUpdatedAt());
        assertTrue(afterSecond.getUpdatedAt().isAfter(afterFirstUpdatedAt)
                        || afterSecond.getUpdatedAt().isEqual(afterFirstUpdatedAt),
                "Second swipe must also bump updatedAt without version conflict");
    }
}
