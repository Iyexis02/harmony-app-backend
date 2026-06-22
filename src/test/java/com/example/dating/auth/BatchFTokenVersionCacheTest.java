package com.example.dating.auth;

import com.example.dating.DatingApplication;
import com.example.dating.enums.user.AuthProvider;
import com.example.dating.enums.user.RegistrationStage;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.repositories.UserJpaRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.junit.jupiter.api.AfterEach;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Batch F — Per-Request Token Version Validation Cache
 *
 * <p>Verifies that the Caffeine cache added to {@code SecurityConfig.jwtDecoder} behaves
 * correctly under both single-threaded and concurrent access:
 *
 * <ol>
 *   <li>A stale token is still accepted <em>within</em> the cache TTL after a password reset
 *       (grace window — the documented acceptable trade-off).</li>
 *   <li>A stale token is rejected once the cache TTL has expired and the DB is re-queried.</li>
 *   <li>A new token issued with the bumped version is accepted after the TTL expires.</li>
 *   <li>20 concurrent threads all decode the same valid token without a cache race.</li>
 *   <li>20 concurrent threads racing to prime the cache for the same userId all succeed
 *       (Caffeine's per-key loader lock prevents duplicate DB queries).</li>
 * </ol>
 *
 * <p>{@code application-test.yml} sets {@code jwt.token-version.cache-ttl-seconds=1} so
 * TTL-expiry tests only need a 1.1 s sleep instead of 30 s.
 */
@SpringBootTest(classes = DatingApplication.class)
@ActiveProfiles("test")
class BatchFTokenVersionCacheTest {

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Value("${jwt.secret.key}")
    private String secret;

    private final List<String> savedUserIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        savedUserIds.forEach(userJpaRepository::deleteById);
        savedUserIds.clear();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

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

    private UserEntity saveUser(int tokenVersion) {
        UserEntity entity = UserEntity.builder()
                .id(UUID.randomUUID().toString())
                .email("batchf-test-" + UUID.randomUUID() + "@example.com")
                .authProvider(AuthProvider.EMAIL)
                .emailVerified(true)
                .tokenVersion(tokenVersion)
                .registrationStage(RegistrationStage.FINISHED)
                .createdAt(LocalDateTime.now())
                .build();
        UserEntity saved = userJpaRepository.save(entity);
        savedUserIds.add(saved.getId());
        return saved;
    }

    // ─── Test 1: grace window ─────────────────────────────────────────────────

    @Test
    @DisplayName("Stale token accepted within cache TTL after password reset (grace window)")
    void staleToken_acceptedWithinCacheTtl_afterPasswordReset() {
        UserEntity user = saveUser(3);
        String oldToken = buildVersionedToken(user.getId(), 3);

        // Prime the cache: first successful decode writes userId -> 3 into the cache.
        jwtDecoder.decode(oldToken);

        // Simulate password reset: DB now holds version=4, cache still holds version=3.
        user.setTokenVersion(4);
        userJpaRepository.save(user);

        // Within the 1s TTL the cache returns the stale version=3, so the old token passes.
        assertThatCode(() -> jwtDecoder.decode(oldToken))
                .as("Stale token must be accepted within the cache grace window")
                .doesNotThrowAnyException();
    }

    // ─── Test 2: rejection after TTL ─────────────────────────────────────────

    @Test
    @DisplayName("Stale token rejected after cache TTL expires following password reset")
    void staleToken_rejectedAfterCacheTtlExpires_afterPasswordReset() throws InterruptedException {
        UserEntity user = saveUser(3);
        String oldToken = buildVersionedToken(user.getId(), 3);

        // Prime the cache.
        jwtDecoder.decode(oldToken);

        // Simulate password reset.
        user.setTokenVersion(4);
        userJpaRepository.save(user);

        // Wait for the 1 s test TTL to expire.
        Thread.sleep(1_100);

        // Cache miss → DB queried → version=4; JWT version=3 → rejected.
        assertThatThrownBy(() -> jwtDecoder.decode(oldToken))
                .as("Stale token must be rejected once the cache TTL has expired")
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("invalidated");
    }

    // ─── Test 3: new token accepted after TTL ─────────────────────────────────

    @Test
    @DisplayName("New token with bumped version accepted after cache TTL expires")
    void newToken_withBumpedVersion_acceptedAfterCacheTtlExpires() throws InterruptedException {
        UserEntity user = saveUser(3);
        String oldToken = buildVersionedToken(user.getId(), 3);

        // Prime the cache with old version.
        jwtDecoder.decode(oldToken);

        // Simulate password reset and issue a new token at version=4.
        user.setTokenVersion(4);
        userJpaRepository.save(user);
        String newToken = buildVersionedToken(user.getId(), 4);

        // Wait for the cache to expire so re-query returns version=4.
        Thread.sleep(1_100);

        // New token: JWT version=4 == DB version=4 → accepted.
        assertThatCode(() -> jwtDecoder.decode(newToken))
                .as("New token issued after password reset must be accepted after cache expiry")
                .doesNotThrowAnyException();
    }

    // ─── Test 4: concurrent valid tokens ─────────────────────────────────────

    @Test
    @DisplayName("20 concurrent valid tokens for the same user are all accepted (no cache race)")
    void concurrent_validTokens_allAccepted_noCacheRace() throws InterruptedException {
        int threadCount = 20;
        UserEntity user = saveUser(7);
        String token = buildVersionedToken(user.getId(), 7);

        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(threadCount);

        AtomicInteger accepted = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    jwtDecoder.decode(token);
                    accepted.incrementAndGet();
                } catch (BadJwtException e) {
                    rejected.incrementAndGet();
                } catch (Exception ignored) {
                    rejected.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        boolean completed = doneLatch.await(15, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(completed).as("All threads must finish within 15 s").isTrue();
        assertThat(rejected.get()).as("No valid token should be rejected under concurrent load").isZero();
        assertThat(accepted.get())
                .as("All %d threads must accept the valid token", threadCount)
                .isEqualTo(threadCount);
    }

    // ─── Test 5: concurrent cache priming ────────────────────────────────────

    @Test
    @DisplayName("20 concurrent threads priming cache for same userId all succeed (Caffeine per-key lock)")
    void concurrent_cachePriming_sameUser_allConsistent() throws InterruptedException {
        int threadCount = 20;
        UserEntity user = saveUser(9);
        String token = buildVersionedToken(user.getId(), 9);

        // All threads race to decode with a cold cache. Caffeine's per-key loader lock
        // ensures exactly one DB lookup runs; the rest block and then read the cached value.
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(threadCount);

        AtomicInteger accepted = new AtomicInteger(0);
        AtomicInteger failed   = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    jwtDecoder.decode(token);
                    accepted.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        boolean completed = doneLatch.await(15, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(completed).as("All threads must finish within 15 s").isTrue();
        assertThat(failed.get()).as("No thread should fail during concurrent cache priming").isZero();
        assertThat(accepted.get())
                .as("All %d threads must accept the token with consistent cache state", threadCount)
                .isEqualTo(threadCount);
    }
}
