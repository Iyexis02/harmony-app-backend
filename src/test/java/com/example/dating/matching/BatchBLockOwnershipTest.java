package com.example.dating.matching;

import com.example.dating.config.DistributedLockService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Batch B — Lock Ownership Tracking in DistributedLockService.
 *
 * <p>The root problem: {@code unlock(key)} previously called {@code redisTemplate.delete(key)}
 * unconditionally. If the lock's TTL expired while the holder was still working (e.g. slow
 * Spotify response) and a second instance re-acquired the key, the original holder's
 * {@code finally} block would delete the new owner's lock — allowing a third instance in
 * and causing concurrent genre syncs or token refreshes for the same user.
 *
 * <p>Fix: {@code tryLock} stores a UUID as the Redis value (owner ID) and returns it to
 * the caller. {@code unlock(key, ownerId)} executes a Lua script that only deletes the key
 * when the stored value still matches the caller's owner ID. The in-memory fallback uses
 * {@link java.util.concurrent.ConcurrentHashMap#remove(Object, Object)} for the same guarantee.
 *
 * <p>Verifies:
 * <ol>
 *   <li>tryLock stores the returned owner UUID in Redis, not the literal string "locked".</li>
 *   <li>unlock uses a Lua conditional-delete script, not a plain {@code delete()} call.</li>
 *   <li>unlock with a mismatched (stale) owner ID is a no-op on the in-memory fallback —
 *       the real owner's lock remains held.</li>
 *   <li>In-memory fallback: each tryLock acquisition produces a distinct owner ID so
 *       consecutive sessions cannot accidentally cross-release.</li>
 *   <li>Concurrent: 20 threads racing via in-memory fallback — exactly 1 acquires
 *       a non-null, non-"locked" owner ID.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class BatchBLockOwnershipTest {

    // -----------------------------------------------------------------------
    // 1. tryLock stores the returned owner UUID in Redis (not "locked")
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("tryLock stores the returned owner UUID in Redis, not the literal 'locked'")
    void tryLock_storesReturnedOwnerUuidInRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        DistributedLockService service = new DistributedLockService(redis);
        String key = "lock:batch-b:uuid-test";

        String ownerId = service.tryLock(key, Duration.ofSeconds(30));

        assertThat(ownerId)
                .as("tryLock must return a non-null owner ID when Redis SETNX succeeds")
                .isNotNull();

        // Capture the exact value that was stored in Redis
        ArgumentCaptor<String> storedValue = ArgumentCaptor.forClass(String.class);
        verify(valueOps).setIfAbsent(eq(key), storedValue.capture(), any(Duration.class));

        assertThat(storedValue.getValue())
                .as("value stored in Redis must equal the returned owner ID")
                .isEqualTo(ownerId);

        assertThat(storedValue.getValue())
                .as("value must not be the literal string 'locked' — must be a UUID")
                .isNotEqualTo("locked");
    }

    // -----------------------------------------------------------------------
    // 2. unlock uses Lua conditional-delete, not a plain redisTemplate.delete()
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("unlock executes a Lua conditional-delete script, never calls redisTemplate.delete()")
    void unlock_usesLuaScriptNotDirectDelete() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        // Lua script returns 1 (key deleted successfully — owner ID matched)
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(1L);

        DistributedLockService service = new DistributedLockService(redis);
        String key = "lock:batch-b:lua-test";

        String ownerId = service.tryLock(key, Duration.ofSeconds(30));
        assertThat(ownerId).isNotNull();

        service.unlock(key, ownerId);

        // Lua script MUST be invoked with the correct key and owner ID
        verify(redis).execute(any(RedisScript.class), eq(List.of(key)), eq(ownerId));
        // Plain delete must NEVER be called — it has no ownership check
        verify(redis, never()).delete(eq(key));
    }

    // -----------------------------------------------------------------------
    // 3. unlock with a mismatched owner ID is a no-op (in-memory fallback)
    //
    // This is the core of the Batch B fix: simulates Instance A's lock TTL
    // expiring and being re-acquired by Instance B. When Instance A calls
    // unlock with its stale owner ID, Instance B's lock must remain intact.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("unlock with a stale/mismatched owner ID does not release the current lock holder")
    void unlock_mismatchedOwnerIsNoOpOnFallback() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        // Force fallback mode so we can fully observe ownership without a real Redis
        when(redis.opsForValue()).thenThrow(new RuntimeException("Redis down"));

        DistributedLockService service = new DistributedLockService(redis);
        String key = "lock:batch-b:mismatch-test";
        Duration ttl = Duration.ofSeconds(30);

        // Instance A acquires the lock
        String ownerA = service.tryLock(key, ttl);
        assertThat(ownerA).as("Instance A must acquire the lock").isNotNull();

        // Simulate: A stale holder (or a different caller) attempts to unlock with the wrong ID
        service.unlock(key, "stale-owner-that-does-not-match");

        // The lock must still be held — a third party cannot re-acquire it
        String ownerC = service.tryLock(key, ttl);
        assertThat(ownerC)
                .as("Lock must still be held by Instance A after mismatched unlock attempt")
                .isNull();

        // Instance A uses its real owner ID to release — lock must be freed
        service.unlock(key, ownerA);
        String ownerD = service.tryLock(key, ttl);
        assertThat(ownerD)
                .as("Lock must be acquirable after the real owner releases it")
                .isNotNull();
    }

    // -----------------------------------------------------------------------
    // 4. Consecutive acquisitions produce distinct owner IDs (in-memory fallback)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("consecutive tryLock acquisitions produce distinct owner IDs (no ID reuse)")
    void tryLock_consecutiveAcquisitionsHaveDistinctOwnerIds() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new RuntimeException("Redis down"));

        DistributedLockService service = new DistributedLockService(redis);
        String key = "lock:batch-b:distinct-id-test";
        Duration ttl = Duration.ofSeconds(30);

        String owner1 = service.tryLock(key, ttl);
        assertThat(owner1).as("first acquisition must succeed").isNotNull();

        service.unlock(key, owner1);

        String owner2 = service.tryLock(key, ttl);
        assertThat(owner2).as("second acquisition must succeed after release").isNotNull();

        assertThat(owner1)
                .as("each acquisition must produce a distinct owner ID (UUID — not reused)")
                .isNotEqualTo(owner2);

        service.unlock(key, owner2);
    }

    // -----------------------------------------------------------------------
    // 5. Concurrent: 20 threads race for the same key — exactly 1 wins,
    //    winner's owner ID is non-null and not the legacy "locked" string
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("20 concurrent tryLock calls yield exactly 1 acquisition with a non-'locked' owner ID")
    void tryLock_concurrent_exactlyOneWinsWithProperOwnerId() throws InterruptedException {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new RuntimeException("Redis down"));

        DistributedLockService service = new DistributedLockService(redis);
        String key = "lock:batch-b:concurrent-uuid-test";
        Duration ttl = Duration.ofSeconds(30);
        int threads = 20;

        CountDownLatch start    = new CountDownLatch(1);
        CountDownLatch done     = new CountDownLatch(threads);
        AtomicInteger acquired  = new AtomicInteger(0);
        String[] ownerIds       = new String[threads];

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    String ownerId = service.tryLock(key, ttl);
                    ownerIds[idx] = ownerId;
                    if (ownerId != null) {
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
                .as("All %d threads must complete within 5 s", threads)
                .isTrue();

        assertThat(acquired.get())
                .as("Exactly 1 of %d concurrent tryLock calls must succeed", threads)
                .isEqualTo(1);

        // Verify the winning owner ID is not the old "locked" sentinel value
        String winnerId = null;
        for (String id : ownerIds) {
            if (id != null) {
                winnerId = id;
                break;
            }
        }
        assertThat(winnerId)
                .as("winning owner ID must be non-null")
                .isNotNull()
                .as("winning owner ID must not be the legacy 'locked' string")
                .isNotEqualTo("locked");
    }
}
