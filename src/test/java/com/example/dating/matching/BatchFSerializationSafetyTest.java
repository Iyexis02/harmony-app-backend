package com.example.dating.matching;

import com.example.dating.DatingApplication;
import com.example.dating.models.matching.dao.UserBehavioralProfile;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.repositories.UserBehavioralProfileRepository;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.impl.EncryptionServiceImpl;
import com.example.dating.services.matching.BehavioralProfileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies Batch F — Logging Hygiene and Serialization Safety.
 *
 * <p>Three invariants are asserted:
 *
 * <ol>
 *   <li><strong>Corrupted JSON is never silently overwritten with "{}".</strong>
 *       Before the fix, {@code deserializeDoubleMap()} returned an empty {@code HashMap}
 *       on parse failure, which was then serialized back to {@code "{}"} and persisted —
 *       erasing all learned genre weights.  After the fix the method throws, the
 *       {@code @Transactional} boundary rolls the update back, and the stored value is
 *       unchanged.
 *
 *   <li><strong>Concurrent updates with corrupted JSON both drop safely.</strong>
 *       Two threads race to call {@code updateAfterSwipe()} on the same profile that
 *       has invalid {@code learnedGenreWeights} JSON.  Neither thread should corrupt
 *       the profile; both should silently drop the update via the retry orchestrator's
 *       generic-exception handler.
 *
 *   <li><strong>Encryption failure message says "Encryption failed", not "Decryption
 *       failed".</strong>  The first catch block in {@code EncryptionServiceImpl.encrypt()}
 *       previously threw {@code new RuntimeException("Decryption failed", e)}, creating
 *       a misleading diagnostic during debugging.
 * </ol>
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class BatchFSerializationSafetyTest {

    private static final String CORRUPTED_JSON = "not-valid-json{{{{";

    @Autowired
    private BehavioralProfileService behavioralProfileService;

    @Autowired
    private UserBehavioralProfileRepository behavioralProfileRepository;

    @Autowired
    private UserJpaRepository userRepository;

    private UserEntity swiper;
    private UserEntity swiped;

    @BeforeEach
    void setUp() {
        swiper = userRepository.findByEmail("batchf.swiper@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("batchf.swiper@test.com")
                                .name("BatchFSwiper")
                                .build()));

        swiped = userRepository.findByEmail("batchf.swiped@test.com")
                .orElseGet(() -> userRepository.save(
                        UserEntity.builder()
                                .email("batchf.swiped@test.com")
                                .name("BatchFSwiped")
                                .build()));

        behavioralProfileRepository.findByUserId(swiper.getId())
                .ifPresent(p -> behavioralProfileRepository.deleteById(p.getId()));
    }

    @AfterEach
    void tearDown() {
        behavioralProfileRepository.findByUserId(swiper.getId())
                .ifPresent(p -> behavioralProfileRepository.deleteById(p.getId()));
    }

    // -------------------------------------------------------------------------
    // 1. Single-thread: corrupted genre weights — update dropped, value preserved
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Corrupted learnedGenreWeights: update is dropped, stored value is NOT replaced with '{}'")
    void updateAfterSwipe_withCorruptedGenreWeights_dropsUpdateWithoutCorruption() {
        // Arrange: persist a profile whose learnedGenreWeights is corrupted JSON
        UserEntity user = userRepository.findById(swiper.getId())
                .orElseThrow(() -> new AssertionError("Swiper must exist"));

        UserBehavioralProfile corruptedProfile = UserBehavioralProfile.builder()
                .user(user)
                .totalLikes(5)
                .totalPasses(0)
                .confidenceLevel(0.10)
                .learnedGenreWeights(CORRUPTED_JSON)
                .build();
        behavioralProfileRepository.save(corruptedProfile);

        // Act: attempt a like — deserialization will throw, retry orchestrator drops it
        behavioralProfileService.updateAfterSwipe(swiper.getId(), swiped.getId(), "like", 80.0);

        // Assert: profile is unchanged — NOT overwritten with "{}" or any other value
        UserBehavioralProfile stored = behavioralProfileRepository
                .findByUserId(swiper.getId())
                .orElseThrow(() -> new AssertionError("Profile must still exist after dropped update"));

        assertEquals(CORRUPTED_JSON, stored.getLearnedGenreWeights(),
                "Corrupted learnedGenreWeights must NOT be overwritten with '{}' — " +
                "if this fails, the serialization safety fix in BehavioralProfileService is missing");

        assertEquals(5, stored.getTotalLikes(),
                "totalLikes must remain 5 — the update must have been fully dropped");
    }

    @Test
    @DisplayName("Corrupted topLikedRelationshipGoals: update is dropped, stored value is NOT replaced with '{}'")
    void updateAfterSwipe_withCorruptedGoalMap_dropsUpdateWithoutCorruption() {
        UserEntity user = userRepository.findById(swiper.getId())
                .orElseThrow(() -> new AssertionError("Swiper must exist"));

        UserBehavioralProfile corruptedProfile = UserBehavioralProfile.builder()
                .user(user)
                .totalLikes(3)
                .totalPasses(0)
                .confidenceLevel(0.06)
                .topLikedRelationshipGoals(CORRUPTED_JSON)
                .build();
        behavioralProfileRepository.save(corruptedProfile);

        behavioralProfileService.updateAfterSwipe(swiper.getId(), swiped.getId(), "like", 70.0);

        UserBehavioralProfile stored = behavioralProfileRepository
                .findByUserId(swiper.getId())
                .orElseThrow(() -> new AssertionError("Profile must still exist after dropped update"));

        assertEquals(CORRUPTED_JSON, stored.getTopLikedRelationshipGoals(),
                "Corrupted topLikedRelationshipGoals must NOT be overwritten with '{}'");

        assertEquals(3, stored.getTotalLikes(),
                "totalLikes must remain 3 — the update must have been fully dropped");
    }

    // -------------------------------------------------------------------------
    // 2. Concurrent: both threads drop safely, neither corrupts the profile
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Concurrent updates with corrupted JSON: both threads drop safely, profile not corrupted")
    void concurrentUpdateAfterSwipe_withCorruptedJson_bothThreadsDropWithoutCorruption() throws Exception {
        UserEntity user = userRepository.findById(swiper.getId())
                .orElseThrow(() -> new AssertionError("Swiper must exist"));

        UserBehavioralProfile corruptedProfile = UserBehavioralProfile.builder()
                .user(user)
                .totalLikes(10)
                .totalPasses(0)
                .confidenceLevel(0.20)
                .learnedGenreWeights(CORRUPTED_JSON)
                .build();
        behavioralProfileRepository.save(corruptedProfile);

        int threadCount = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);

        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(); // synchronise start so both threads race the deserialization
                behavioralProfileService.updateAfterSwipe(
                        swiper.getId(), swiped.getId(), "like", 65.0);
                return null;
            }));
        }

        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        // Neither thread should have propagated an unhandled exception
        int errorCount = 0;
        for (Future<Void> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                System.err.println("Thread threw an unexpected exception: " + e.getCause());
                errorCount++;
            }
        }
        assertEquals(0, errorCount, "Neither thread should surface an unhandled exception to the caller");

        // Profile must not be corrupted by either thread
        UserBehavioralProfile stored = behavioralProfileRepository
                .findByUserId(swiper.getId())
                .orElseThrow(() -> new AssertionError("Profile must still exist"));

        assertEquals(CORRUPTED_JSON, stored.getLearnedGenreWeights(),
                "Concurrent threads must NOT overwrite corrupted JSON with '{}' — " +
                "both updates must have been fully dropped");

        assertEquals(10, stored.getTotalLikes(),
                "totalLikes must remain 10 — no partial update must have been committed");
    }

    // -------------------------------------------------------------------------
    // 3. EncryptionServiceImpl: error message says "Encryption failed"
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("EncryptionServiceImpl.encrypt() throws RuntimeException with 'Encryption failed', not 'Decryption failed'")
    void encrypt_whenCipherKeyIsInvalid_throwsWithCorrectMessage() throws Exception {
        EncryptionServiceImpl svc = new EncryptionServiceImpl(
                "xhnXQGxzJn/nCBAfZfPrvafxfrmAKDTZeL5r+gJ+q70="); // valid 32-byte base64 key

        // Corrupt the secretKey via reflection to trigger InvalidKeyException inside encrypt()
        Field keyField = EncryptionServiceImpl.class.getDeclaredField("secretKey");
        keyField.setAccessible(true);
        // AES requires 16, 24, or 32 byte keys — 1 byte triggers InvalidKeyException in cipher.init()
        keyField.set(svc, new SecretKeySpec(new byte[1], "AES"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> svc.encrypt("test payload"),
                "encrypt() must throw when the cipher key is invalid");

        assertEquals("Encryption failed", ex.getMessage(),
                "Exception message must be 'Encryption failed', not 'Decryption failed' — " +
                "this was a copy-paste bug in the first catch block of EncryptionServiceImpl.encrypt()");
    }
}
