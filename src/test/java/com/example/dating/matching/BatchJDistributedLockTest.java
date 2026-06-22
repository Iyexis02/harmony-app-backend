package com.example.dating.matching;

import com.example.dating.config.DistributedLockService;
import com.example.dating.services.impl.SpotifyTokenServiceImpl;
import com.example.dating.services.matching.SpotifyGenreSyncService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Batch J — Distributed Locking for Multi-Instance Deployment.
 *
 * <p>Verifies:
 * <ol>
 *   <li>DistributedLockService uses Redis {@code SETNX} ({@code setIfAbsent}) on {@code tryLock}
 *       and a Lua conditional-delete script on {@code unlock} (not a plain {@code delete}).</li>
 *   <li>DistributedLockService falls back to in-memory ConcurrentHashMap when Redis
 *       throws at runtime — lock is acquired, rejected on second call, released on
 *       unlock.</li>
 *   <li>In-memory fallback is thread-safe: 20 concurrent {@code tryLock} calls for the
 *       same key result in exactly 1 successful acquisition.</li>
 *   <li>Structural: {@link SpotifyTokenServiceImpl} no longer has a
 *       {@code ConcurrentHashMap tokenRefreshLocks} field; it now owns
 *       {@code distributedLockService}.</li>
 *   <li>Structural: {@link SpotifyGenreSyncService} no longer has a
 *       {@code ConcurrentHashMap syncInProgress} field; it now owns
 *       {@code distributedLockService}.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class BatchJDistributedLockTest {

    // -----------------------------------------------------------------------
    // 1. DistributedLockService delegates to Redis SETNX + Lua conditional delete
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("tryLock uses Redis setIfAbsent; unlock uses Lua conditional delete (not plain delete)")
    void distributedLockService_usesRedisSetnxAndLuaDelete() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(1L);

        DistributedLockService service = new DistributedLockService(redisTemplate);
        String key = "lock:test:setnx-key";
        Duration ttl = Duration.ofSeconds(30);

        String ownerId = service.tryLock(key, ttl);
        assertThat(ownerId).as("tryLock must return a non-null owner ID").isNotNull();
        verify(valueOps).setIfAbsent(eq(key), anyString(), eq(ttl));

        service.unlock(key, ownerId);
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(key)), eq(ownerId));
        verify(redisTemplate, never()).delete(eq(key));
    }

    // -----------------------------------------------------------------------
    // 2. Falls back to in-memory ConcurrentHashMap when Redis throws
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Falls back to local ConcurrentHashMap when Redis is unavailable")
    void distributedLockService_fallsBackToLocalMapWhenRedisThrows() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis unavailable"));

        DistributedLockService service = new DistributedLockService(redisTemplate);
        String key = "lock:test:fallback-key";
        Duration ttl = Duration.ofSeconds(30);

        // First call: lock acquired via in-memory fallback
        String ownerId = service.tryLock(key, ttl);
        assertThat(ownerId)
                .as("First tryLock on new key must succeed via fallback")
                .isNotNull();

        // Second call same key: rejected because fallback map already has the entry
        assertThat(service.tryLock(key, ttl))
                .as("Second tryLock on same key must be rejected")
                .isNull();

        // After unlock: entry cleared from fallback — next tryLock must succeed again
        service.unlock(key, ownerId);
        assertThat(service.tryLock(key, ttl))
                .as("tryLock must succeed after unlock clears the fallback entry")
                .isNotNull();
    }

    // -----------------------------------------------------------------------
    // 3. In-memory fallback is thread-safe under contention
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("20 concurrent tryLock calls result in exactly 1 acquisition (fallback mode)")
    void distributedLockService_concurrent_exactlyOneAcquiredViaFallback() throws InterruptedException {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis unavailable"));

        DistributedLockService service = new DistributedLockService(redisTemplate);
        String key = "lock:concurrent-test";
        Duration ttl = Duration.ofSeconds(30);
        int threads = 20;

        CountDownLatch start   = new CountDownLatch(1);
        CountDownLatch done    = new CountDownLatch(threads);
        AtomicInteger acquired = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    if (service.tryLock(key, ttl) != null) {
                        acquired.incrementAndGet();
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
                .as("All %d threads must finish within 5 s", threads)
                .isTrue();
        assertThat(acquired.get())
                .as("Exactly 1 of %d concurrent tryLock calls must succeed", threads)
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // 4. SpotifyTokenServiceImpl structural check — ConcurrentHashMap field gone
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("SpotifyTokenServiceImpl no longer has tokenRefreshLocks; owns distributedLockService")
    void spotifyTokenServiceImpl_noConcurrentHashMapTokenRefreshLocks() throws Exception {
        // tokenRefreshLocks must not exist
        assertThatThrownBy(() ->
                SpotifyTokenServiceImpl.class.getDeclaredField("tokenRefreshLocks"))
                .isInstanceOf(NoSuchFieldException.class);

        // distributedLockService must exist with correct type
        Field distLock = SpotifyTokenServiceImpl.class.getDeclaredField("distributedLockService");
        distLock.setAccessible(true);
        assertThat(distLock.getType())
                .as("distributedLockService must be of type DistributedLockService")
                .isEqualTo(DistributedLockService.class);
    }

    // -----------------------------------------------------------------------
    // 5. SpotifyGenreSyncService structural check — ConcurrentHashMap field gone
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("SpotifyGenreSyncService no longer has syncInProgress; owns distributedLockService")
    void spotifyGenreSyncService_noConcurrentHashMapSyncInProgress() throws Exception {
        // syncInProgress must not exist
        assertThatThrownBy(() ->
                SpotifyGenreSyncService.class.getDeclaredField("syncInProgress"))
                .isInstanceOf(NoSuchFieldException.class);

        // distributedLockService must exist with correct type
        Field distLock = SpotifyGenreSyncService.class.getDeclaredField("distributedLockService");
        distLock.setAccessible(true);
        assertThat(distLock.getType())
                .as("distributedLockService must be of type DistributedLockService")
                .isEqualTo(DistributedLockService.class);
    }
}
