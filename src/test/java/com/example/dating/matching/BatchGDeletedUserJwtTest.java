package com.example.dating.matching;

import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.security.SecurityConfig;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Batch G — Return 401 for Deleted Users' JWTs.
 *
 * <p>Before this fix, when the JWT decoder looked up the DB token version and
 * got {@code null} (user deleted or never existed), it skipped the version check
 * and accepted the token. The request then reached the controller, which threw
 * {@code UserNotFoundException} → HTTP 404 "User not found". That 404 leaks the
 * fact that the account existed. After this fix, {@code null dbVersion} causes a
 * {@link BadJwtException} at the decoder layer → Spring Security returns 401
 * with no information about the user's history.
 *
 * <p>Tests:
 * <ol>
 *   <li>Active user with matching token version → decode succeeds.</li>
 *   <li>Deleted user (repository returns empty) → {@code BadJwtException}.</li>
 *   <li>Exception message for deleted user does not hint at user existence.</li>
 *   <li>Live user with mismatched token version → {@code BadJwtException}
 *       ("Token has been invalidated") — pre-existing path still works.</li>
 *   <li>20-thread concurrent: all deleted-user tokens rejected, zero pass through.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class BatchGDeletedUserJwtTest {

    /**
     * Same value as {@code application-test.yml jwt.secret.key}.
     * Must be ≥ 32 bytes to pass {@link SecurityConfig#validateJwtSecret()}.
     */
    private static final String TEST_SECRET = "test-jwt-secret-key-for-testing-purposes-only";

    @Mock
    private UserJpaRepository userJpaRepository;

    private JwtDecoder decoder;

    @BeforeEach
    void buildDecoder() {
        SecurityConfig config = new SecurityConfig();
        ReflectionTestUtils.setField(config, "secret", TEST_SECRET);
        ReflectionTestUtils.setField(config, "tokenVersionCacheTtlSeconds", 30);
        decoder = config.jwtDecoder(userJpaRepository);
    }

    // ── Test 1: active user with matching version is accepted ─────────────

    @Test
    @DisplayName("Active user with matching tokenVersion → decode succeeds")
    void activeUser_matchingVersion_accepted() {
        String userId = UUID.randomUUID().toString();
        int version = 7;

        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setTokenVersion(version);
        when(userJpaRepository.findById(userId)).thenReturn(Optional.of(user));

        String token = buildToken(userId, version);

        // Must not throw — the decoded Jwt is returned normally.
        var jwt = decoder.decode(token);
        assertThat(jwt.getClaim("userId").toString()).isEqualTo(userId);
    }

    // ── Test 2: deleted user (empty Optional) → BadJwtException ───────────

    @Test
    @DisplayName("Deleted user (findById empty) → BadJwtException thrown")
    void deletedUser_emptyOptional_throwsBadJwtException() {
        String userId = UUID.randomUUID().toString();
        when(userJpaRepository.findById(userId)).thenReturn(Optional.empty());

        String token = buildToken(userId, 3);

        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(BadJwtException.class);
    }

    // ── Test 3: exception message for deleted user reveals nothing ─────────

    @Test
    @DisplayName("BadJwtException for deleted user does not contain 'User not found'")
    void deletedUser_exceptionMessage_revealsNoExistence() {
        String userId = UUID.randomUUID().toString();
        when(userJpaRepository.findById(userId)).thenReturn(Optional.empty());

        String token = buildToken(userId, 1);

        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(BadJwtException.class)
                .hasMessage("Invalid token")
                .message()
                .doesNotContainIgnoringCase("user")
                .doesNotContainIgnoringCase("found")
                .doesNotContainIgnoringCase("exist")
                .doesNotContainIgnoringCase("deleted");
    }

    // ── Test 4: live user with version mismatch → still rejected ──────────

    @Test
    @DisplayName("Live user with mismatched tokenVersion → BadJwtException('Token has been invalidated')")
    void liveUser_versionMismatch_throwsBadJwtException() {
        String userId = UUID.randomUUID().toString();

        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setTokenVersion(5);  // DB version is 5
        when(userJpaRepository.findById(userId)).thenReturn(Optional.of(user));

        // Token carries version 3 — mismatch
        String token = buildToken(userId, 3);

        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(BadJwtException.class)
                .hasMessage("Token has been invalidated");
    }

    // ── Test 5: 20-thread concurrent stress — all deleted tokens rejected ──

    /**
     * 20 threads simultaneously present tokens for a deleted user.
     * Every single decode attempt must throw {@link BadJwtException}.
     * Zero tokens may slip through to the controller layer.
     *
     * <p>This would fail before Batch G because the {@code null dbVersion} path
     * accepted the token unconditionally, so all 20 threads would have returned
     * a decoded Jwt instead of throwing.
     */
    @Test
    @DisplayName("20 concurrent deleted-user tokens → all rejected, zero pass through")
    void deletedUser_concurrent_allRejected() throws InterruptedException {
        String userId = UUID.randomUUID().toString();
        // userJpaRepository is lenient mock — all findById calls return empty
        when(userJpaRepository.findById(anyString())).thenReturn(Optional.empty());

        String token = buildToken(userId, 2);

        int threads = 20;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger rejected = new AtomicInteger(0);
        AtomicInteger passed   = new AtomicInteger(0);
        AtomicInteger errors   = new AtomicInteger(0);

        Thread[] pool = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            pool[i] = new Thread(() -> {
                try {
                    barrier.await();
                    decoder.decode(token);
                    // If we reach here the token was incorrectly accepted
                    passed.incrementAndGet();
                } catch (BadJwtException e) {
                    rejected.incrementAndGet();
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
            pool[i].setDaemon(true);
            pool[i].start();
        }

        for (Thread t : pool) {
            t.join(5_000);
        }

        assertThat(errors.get())
                .as("unexpected non-BadJwtException errors in threads")
                .isZero();
        assertThat(passed.get())
                .as("deleted-user tokens must never pass the decoder")
                .isZero();
        assertThat(rejected.get())
                .as("all %d threads must receive BadJwtException", threads)
                .isEqualTo(threads);
    }

    // ── Helper ─────────────────────────────────────────────────────────────

    /**
     * Builds a signed JWT carrying {@code userId} and {@code tokenVersion} claims,
     * using the same HMAC-SHA256 key as the decoder under test.
     */
    private String buildToken(String userId, int tokenVersion) {
        SecretKeySpec key = new SecretKeySpec(
                TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");

        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("tokenVersion", tokenVersion);

        return Jwts.builder()
                .setClaims(claims)
                .setSubject(userId)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 3_600_000L))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }
}
