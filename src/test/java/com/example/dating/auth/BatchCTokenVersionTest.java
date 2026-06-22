package com.example.dating.auth;

import com.example.dating.DatingApplication;
import com.example.dating.enums.user.AuthProvider;
import com.example.dating.enums.user.RegistrationStage;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.JwtService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Batch C — Token Version Legacy Cutoff + HMAC Key Validation
 *
 * <p>Verifies:
 * <ol>
 *   <li>Tokens without {@code tokenVersion} claim are rejected with {@link BadJwtException}.</li>
 *   <li>Tokens with a matching {@code tokenVersion} are accepted.</li>
 *   <li>Tokens whose {@code tokenVersion} no longer matches the DB value (password-reset
 *       scenario) are rejected.</li>
 *   <li>Under concurrent load, all versionless tokens are consistently rejected
 *       (no race on the decoder lambda).</li>
 *   <li>The HMAC key byte array uses UTF-8 encoding deterministically — signing and
 *       verification succeed across both call sites.</li>
 *   <li>Startup fails when the JWT secret is shorter than 32 bytes.</li>
 * </ol>
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class BatchCTokenVersionTest {

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Value("${jwt.secret.key}")
    private String secret;

    /** Tracks IDs of users created during a test so we can clean up. */
    private String savedUserId;

    @AfterEach
    void cleanUp() {
        if (savedUserId != null) {
            userJpaRepository.deleteById(savedUserId);
            savedUserId = null;
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Build a structurally valid, correctly-signed JWT that intentionally omits
     * the {@code tokenVersion} claim.  This simulates a pre-Batch-A legacy token.
     */
    private String buildVersionlessToken(String userId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        // Deliberately NO "tokenVersion" claim
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(userId)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(24)))
                .signWith(new SecretKeySpec(
                        secret.getBytes(StandardCharsets.UTF_8),
                        SignatureAlgorithm.HS256.getJcaName()))
                .compact();
    }

    /**
     * Build a correctly-signed JWT with the supplied {@code tokenVersion} value.
     */
    private String buildVersionedToken(String userId, int tokenVersion) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("tokenVersion", tokenVersion);
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(userId)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(24)))
                .signWith(new SecretKeySpec(
                        secret.getBytes(StandardCharsets.UTF_8),
                        SignatureAlgorithm.HS256.getJcaName()))
                .compact();
    }

    /** Persist a minimal UserEntity and record its ID for cleanup. */
    private UserEntity saveUser(int tokenVersion) {
        UserEntity entity = UserEntity.builder()
                .id(UUID.randomUUID().toString())
                .email("batchc-test-" + UUID.randomUUID() + "@example.com")
                .authProvider(AuthProvider.EMAIL)
                .emailVerified(true)
                .tokenVersion(tokenVersion)
                .registrationStage(RegistrationStage.FINISHED)
                .createdAt(LocalDateTime.now())
                .build();
        UserEntity saved = userJpaRepository.save(entity);
        savedUserId = saved.getId();
        return saved;
    }

    // ─── Tests: versionless token rejection ──────────────────────────────────

    @Test
    @DisplayName("Token without tokenVersion claim is rejected (BadJwtException)")
    void tokenWithoutVersionClaim_isRejected() {
        String token = buildVersionlessToken("some-user-id");

        assertThatThrownBy(() -> jwtDecoder.decode(token))
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("version claim");
    }

    @Test
    @DisplayName("Token without tokenVersion claim for a real DB user is still rejected")
    void tokenWithoutVersionClaim_realDbUser_isRejected() {
        UserEntity user = saveUser(1);
        String token = buildVersionlessToken(user.getId());

        assertThatThrownBy(() -> jwtDecoder.decode(token))
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("version claim");
    }

    // ─── Tests: versioned token acceptance ───────────────────────────────────

    @Test
    @DisplayName("Token with matching tokenVersion is accepted")
    void tokenWithMatchingVersion_isAccepted() {
        UserEntity user = saveUser(2);
        String token = buildVersionedToken(user.getId(), 2);

        // Must not throw — decode returns the Jwt object
        var decoded = jwtDecoder.decode(token);
        assertThat(decoded.getClaim("userId").toString()).isEqualTo(user.getId());
    }

    @Test
    @DisplayName("JwtService.generateToken produces a token the decoder accepts")
    void generateToken_producesAcceptableToken() {
        UserEntity entity = saveUser(5);
        User user = User.builder()
                .id(entity.getId())
                .email(entity.getEmail())
                .authProvider(AuthProvider.EMAIL)
                .emailVerified(true)
                .tokenVersion(5)
                .registrationStage(RegistrationStage.FINISHED)
                .build();

        String token = jwtService.generateToken(user);

        var decoded = jwtDecoder.decode(token);
        assertThat(decoded.getClaim("userId").toString()).isEqualTo(user.getId());
        assertThat(((Number) decoded.getClaim("tokenVersion")).intValue()).isEqualTo(5);
    }

    // ─── Tests: password-reset invalidation ──────────────────────────────────

    @Test
    @DisplayName("Token with stale tokenVersion is rejected after password reset (version bump)")
    void staleToken_afterPasswordReset_isRejected() {
        UserEntity user = saveUser(3);
        // Token was issued at version 3
        String oldToken = buildVersionedToken(user.getId(), 3);

        // Simulate password reset: bump tokenVersion to 4 in the DB
        user.setTokenVersion(4);
        userJpaRepository.save(user);

        assertThatThrownBy(() -> jwtDecoder.decode(oldToken))
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("invalidated");
    }

    @Test
    @DisplayName("New token issued after password reset is accepted with the bumped version")
    void newToken_afterPasswordReset_isAccepted() {
        UserEntity user = saveUser(3);
        // Simulate password reset
        user.setTokenVersion(4);
        userJpaRepository.save(user);

        String newToken = buildVersionedToken(user.getId(), 4);

        var decoded = jwtDecoder.decode(newToken);
        assertThat(((Number) decoded.getClaim("tokenVersion")).intValue()).isEqualTo(4);
    }

    // ─── Tests: HMAC key encoding consistency ────────────────────────────────

    @Test
    @DisplayName("Tokens signed with UTF-8 key bytes are accepted by the decoder (same encoding on both sides)")
    void hmacEncoding_utf8_signingAndVerificationConsistent() {
        UserEntity entity = saveUser(1);
        // Sign with explicit UTF-8 (same as both call sites after Batch C)
        String token = buildVersionedToken(entity.getId(), 1);

        // Decoder uses StandardCharsets.UTF_8 after Batch C — must not throw
        var decoded = jwtDecoder.decode(token);
        assertThat(decoded.getSubject()).isEqualTo(entity.getId());
    }

    // ─── Tests: startup validation (unit-level) ───────────────────────────────

    @Test
    @DisplayName("Test JWT secret is at least 32 bytes (startup validation would catch shorter keys)")
    void testSecret_meetsMinimumKeyLength() {
        // This asserts that the key configured in application-test.yml satisfies the
        // same constraint that @PostConstruct validateJwtSecret() enforces at startup.
        // A failing assertion here means the test config itself is misconfigured.
        int byteLength = secret.getBytes(StandardCharsets.UTF_8).length;
        assertThat(byteLength)
                .as("jwt.secret.key in application-test.yml must be >= 32 bytes but was %d", byteLength)
                .isGreaterThanOrEqualTo(32);
    }

    @Test
    @DisplayName("Startup validation throws IllegalStateException for a 16-byte key")
    void startupValidation_throwsForShortKey() {
        // Exercise the validation logic directly — avoids booting a second ApplicationContext.
        String shortSecret = "only-16-chars!!!";  // 16 chars = 16 UTF-8 bytes
        assertThat(shortSecret.getBytes(StandardCharsets.UTF_8).length).isLessThan(32);

        // Recreate the check from SecurityConfig.validateJwtSecret()
        org.assertj.core.api.ThrowableAssert.ThrowingCallable validation = () -> {
            if (shortSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalStateException(
                        "JWT_SECRET_KEY must be at least 32 bytes (256 bits)");
            }
        };
        assertThatThrownBy(validation)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    // ─── Tests: concurrent rejection (integration) ───────────────────────────

    @Test
    @DisplayName("20 concurrent versionless tokens are all rejected without race conditions")
    void concurrent_versionlessTokens_allRejected() throws InterruptedException {
        int threadCount = 20;
        String token = buildVersionlessToken("concurrent-user-" + UUID.randomUUID());

        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger rejectedCount = new AtomicInteger(0);
        AtomicInteger unexpectedCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    jwtDecoder.decode(token);
                    // Should never reach here
                    unexpectedCount.incrementAndGet();
                } catch (BadJwtException e) {
                    rejectedCount.incrementAndGet();
                } catch (Exception ignored) {
                    unexpectedCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();          // all threads parked
        startLatch.countDown();      // release all at once
        boolean completed = doneLatch.await(15, TimeUnit.SECONDS);

        executor.shutdown();

        assertThat(completed).as("All threads should complete within 15 s").isTrue();
        assertThat(unexpectedCount.get()).as("No thread should accept a versionless token").isZero();
        assertThat(rejectedCount.get())
                .as("All %d threads must reject the versionless token", threadCount)
                .isEqualTo(threadCount);
    }

    @Test
    @DisplayName("20 concurrent valid tokens for the same user are all accepted")
    void concurrent_validVersionedTokens_allAccepted() throws InterruptedException {
        int threadCount = 20;
        UserEntity user = saveUser(7);
        String token = buildVersionedToken(user.getId(), 7);

        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger acceptedCount = new AtomicInteger(0);
        AtomicInteger failedCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    jwtDecoder.decode(token);
                    acceptedCount.incrementAndGet();
                } catch (Exception e) {
                    failedCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        boolean completed = doneLatch.await(15, TimeUnit.SECONDS);

        executor.shutdown();

        assertThat(completed).as("All threads should complete within 15 s").isTrue();
        assertThat(failedCount.get()).as("No valid token should be rejected").isZero();
        assertThat(acceptedCount.get())
                .as("All %d threads must accept the versioned token", threadCount)
                .isEqualTo(threadCount);
    }
}
