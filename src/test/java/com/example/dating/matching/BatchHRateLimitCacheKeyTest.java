package com.example.dating.matching;

import com.example.dating.models.matching.dao.UserBehavioralProfile;
import com.example.dating.models.matching.dao.UserGenrePreference;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.repositories.UserGenrePreferenceRepository;
import com.example.dating.security.CaffeineRateLimiter;
import com.example.dating.security.RateLimiter;
import com.example.dating.security.RedisRateLimiter;
import com.example.dating.services.matching.BehavioralScoreCalculator;
import com.example.dating.services.matching.GenrePrefetchContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Batch H — Rate Limiter Redis Migration + Cache Key Fixes.
 *
 * <p>Verifies:
 * <ol>
 *   <li>CaffeineRateLimiter correctly allows up to capacity and then rejects.</li>
 *   <li>CaffeineRateLimiter is thread-safe: exactly {@code capacity} requests succeed
 *       when {@code threads > capacity} concurrent requests race the same key.</li>
 *   <li>RedisRateLimiter delegates to INCR + EXPIRE and rejects beyond capacity.</li>
 *   <li>BehavioralScoreCalculator cache key fix: two consecutive calls with the same
 *       profileId share the cached deserialized map (ObjectMapper.readValue called once).</li>
 *   <li>BehavioralScoreCalculator.invalidateCache() causes the next call to re-deserialize
 *       (ObjectMapper.readValue called a second time after invalidation).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class BatchHRateLimitCacheKeyTest {

    // -----------------------------------------------------------------------
    // 1. CaffeineRateLimiter — basic allow / reject
    // -----------------------------------------------------------------------

    @Test
    void caffeineRateLimiter_allowsUpToCapacityThenRejects() {
        CaffeineRateLimiter limiter = new CaffeineRateLimiter();
        int capacity = 5;
        Duration window = Duration.ofMinutes(1);
        String key = "basic-test-key";

        for (int i = 0; i < capacity; i++) {
            RateLimiter.ConsumeResult r = limiter.tryConsume(key, capacity, window);
            assertThat(r.consumed())
                    .as("Request %d of %d should be allowed", i + 1, capacity)
                    .isTrue();
        }

        RateLimiter.ConsumeResult overflow = limiter.tryConsume(key, capacity, window);
        assertThat(overflow.consumed()).isFalse();
        assertThat(overflow.retryAfterSeconds()).isPositive();
    }

    // -----------------------------------------------------------------------
    // 2. CaffeineRateLimiter — concurrent stress: exactly capacity threads win
    // -----------------------------------------------------------------------

    @Test
    void caffeineRateLimiter_concurrent_exactlyCapacityAllowed() throws InterruptedException {
        CaffeineRateLimiter limiter = new CaffeineRateLimiter();
        int capacity = 10;
        int threads  = 60;
        Duration window = Duration.ofMinutes(1);
        String key = "concurrent-test-key";

        CountDownLatch start   = new CountDownLatch(1);
        CountDownLatch done    = new CountDownLatch(threads);
        AtomicInteger  allowed = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    if (limiter.tryConsume(key, capacity, window).consumed()) {
                        allowed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }

        start.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS))
                .as("All threads should finish within 5s")
                .isTrue();

        assertThat(allowed.get())
                .as("Exactly capacity=%d requests should be allowed, not %d", capacity, allowed.get())
                .isEqualTo(capacity);
    }

    // -----------------------------------------------------------------------
    // 3. RedisRateLimiter — mock StringRedisTemplate, verify INCR + EXPIRE
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void redisRateLimiter_allowsUpToCapacityThenRejects() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        // Simulate a monotonically increasing counter
        AtomicLong counter = new AtomicLong(0);
        when(valueOps.increment(anyString())).thenAnswer(inv -> counter.incrementAndGet());
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);
        // TTL returned for the Retry-After calculation when over-limit
        when(redisTemplate.getExpire(anyString(), any(TimeUnit.class))).thenReturn(55_000L);

        RedisRateLimiter limiter = new RedisRateLimiter(redisTemplate);
        int capacity = 3;
        Duration window = Duration.ofMinutes(1);

        for (int i = 0; i < capacity; i++) {
            assertThat(limiter.tryConsume("redis-key", capacity, window).consumed())
                    .as("Request %d should be allowed", i + 1)
                    .isTrue();
        }

        RateLimiter.ConsumeResult overflow = limiter.tryConsume("redis-key", capacity, window);
        assertThat(overflow.consumed()).isFalse();
        assertThat(overflow.retryAfterSeconds()).isPositive();

        // TTL (55000 ms) → 55 s + 1 = 56 s
        assertThat(overflow.retryAfterSeconds()).isEqualTo(56L);

        // expire() must have been called exactly once — only on the first increment
        verify(redisTemplate, times(1)).expire(anyString(), any(Duration.class));
    }

    // -----------------------------------------------------------------------
    // BehavioralScoreCalculator tests — use Mockito injection
    // -----------------------------------------------------------------------

    @Mock
    private UserGenrePreferenceRepository genrePreferenceRepository;

    @Spy
    private ObjectMapper objectMapper;

    @Mock
    private GenrePrefetchContext genrePrefetchContext;

    @InjectMocks
    private BehavioralScoreCalculator calculator;

    // -----------------------------------------------------------------------
    // 4. Cache-key fix: same profileId → cache hit on second call
    // -----------------------------------------------------------------------

    @Test
    void behavioralScoreCalculator_cacheHitsOnSameProfileId() throws Exception {
        String genreJson = "{\"rock\":0.8,\"indie\":0.6}";
        UserBehavioralProfile profile = buildProfile("p-cache-hit", genreJson, 10);
        UserEntity candidate = mockCandidate("c-1");

        // First call: cache miss → ObjectMapper.readValue invoked
        calculator.calculate(candidate, profile);

        // Second call: same profileId, same object → cache hit, no re-deserialization
        calculator.calculate(candidate, profile);

        // readValue with the genre JSON string should have been called exactly once
        verify(objectMapper, times(1)).readValue(eq(genreJson), any(Class.class));
    }

    // -----------------------------------------------------------------------
    // 5. invalidateCache() → next call re-deserializes
    // -----------------------------------------------------------------------

    @Test
    void behavioralScoreCalculator_invalidateCacheForcesRedeserialization() throws Exception {
        String genreJson = "{\"pop\":0.9}";
        UserBehavioralProfile profile = buildProfile("p-invalidate", genreJson, 10);
        UserEntity candidate = mockCandidate("c-2");

        // First call: cache miss
        calculator.calculate(candidate, profile);

        // Invalidate the cache entry (simulates BehavioralProfileService saving an update)
        calculator.invalidateCache("p-invalidate");

        // Third call: cache miss again after invalidation
        calculator.calculate(candidate, profile);

        // readValue should be called twice: once before invalidation, once after
        verify(objectMapper, times(2)).readValue(eq(genreJson), any(Class.class));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private UserBehavioralProfile buildProfile(String id, String genreJson, int totalLikes) {
        UserBehavioralProfile profile = new UserBehavioralProfile();
        profile.setId(id);
        profile.setLearnedGenreWeights(genreJson);
        profile.setTotalLikes(totalLikes);
        profile.setLastUpdatedAt(LocalDateTime.now());
        profile.setConfidenceLevel(Math.min(1.0, totalLikes / 50.0));
        return profile;
    }

    private UserEntity mockCandidate(String id) {
        UserEntity candidate = mock(UserEntity.class);
        when(candidate.getId()).thenReturn(id);
        // Prefetch context returns empty → falls back to repo (which returns empty list)
        when(genrePrefetchContext.find(id)).thenReturn(Optional.empty());
        when(genrePreferenceRepository.findByUserIdWithGenreOrderByWeightDesc(id))
                .thenReturn(List.of());
        return candidate;
    }
}
