package com.example.dating.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Distributed locking service backed by Redis SETNX with TTL.
 *
 * <p>Provides cross-instance mutual exclusion so that operations like Spotify token
 * refresh and genre sync are serialised even when multiple application instances
 * run concurrently (rolling deploys, horizontal scaling).
 *
 * <p>Falls back to an in-memory {@link ConcurrentHashMap} when Redis is unavailable
 * at runtime. The fallback only serialises requests within the same JVM — equivalent
 * to the previous per-field {@code ConcurrentHashMap} behaviour — so application
 * availability is not impacted by a Redis outage.
 *
 * <p><b>Master Audit Batch B — Lock ownership tracking:</b>
 * {@link #tryLock(String, Duration)} now stores a unique UUID as the Redis value
 * (instead of the literal {@code "locked"}) and returns it as the owner ID.
 * {@link #unlock(String, String)} uses a Lua script to atomically delete the key
 * only when the stored value matches the caller's owner ID. This prevents a
 * lock whose TTL expired and was re-acquired by another instance from being
 * deleted by the original (now-stale) holder.
 *
 * <p>The TTL on every lock key ensures the lock is automatically released if the
 * holding instance crashes before calling {@link #unlock(String, String)}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DistributedLockService {

    private final StringRedisTemplate redisTemplate;

    /** In-memory fallback: maps lock key → owner ID. Used only when Redis is unreachable. */
    private final ConcurrentHashMap<String, String> localFallback = new ConcurrentHashMap<>();

    /**
     * Lua script for atomic conditional delete.
     *
     * <p>Deletes the key only when its current value matches the caller's owner ID.
     * Returns 1 if the key was deleted, 0 if absent or owned by a different holder.
     * The atomicity guarantee prevents a stale lock holder from deleting another
     * instance's lock after a TTL expiry and re-acquisition.
     */
    private static final RedisScript<Long> UNLOCK_SCRIPT = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "    return redis.call('del', KEYS[1]) " +
            "else " +
            "    return 0 " +
            "end",
            Long.class);

    /**
     * Attempt to acquire a distributed lock identified by {@code key}.
     *
     * <p>Uses Redis {@code SETNX} (SET if Not eXists) so the operation is atomic across
     * all instances. Stores a unique UUID as the key value so that
     * {@link #unlock(String, String)} can verify ownership before releasing the key.
     *
     * <p>Falls back to the in-memory map on any Redis exception (fail-safe).
     *
     * @param key unique lock identifier (e.g. {@code "lock:spotify:refresh:<userId>"})
     * @param ttl safety timeout; the lock is auto-released if the holder crashes
     * @return the owner ID to pass to {@link #unlock(String, String)} if acquired,
     *         or {@code null} if another holder already owns the lock
     */
    public String tryLock(String key, Duration ttl) {
        String ownerId = UUID.randomUUID().toString();
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, ownerId, ttl);
            if (acquired == null) {
                log.warn("Redis SETNX returned null for key '{}' — falling back to local lock", key);
                return localFallback.putIfAbsent(key, ownerId) == null ? ownerId : null;
            }
            return acquired ? ownerId : null;
        } catch (Exception e) {
            log.warn("Redis lock acquisition failed for key '{}' — falling back to local lock: {}",
                    key, e.getMessage());
            return localFallback.putIfAbsent(key, ownerId) == null ? ownerId : null;
        }
    }

    /**
     * Release the lock identified by {@code key}, only if this caller still owns it.
     *
     * <p>Executes a Lua script that atomically checks the stored value matches
     * {@code ownerId} before deleting the key. If the TTL expired and another instance
     * re-acquired the lock, the delete is skipped and a warning is logged — the
     * re-acquirer's lock is left intact.
     *
     * <p>Also cleans up the in-memory fallback using the two-argument
     * {@link ConcurrentHashMap#remove(Object, Object)} so only the matching owner entry
     * is removed; a different owner's entry is left untouched.
     *
     * @param key     lock identifier passed to {@link #tryLock(String, Duration)}
     * @param ownerId the owner ID returned by {@link #tryLock(String, Duration)};
     *                if {@code null} (lock was never acquired) this method is a no-op
     */
    public void unlock(String key, String ownerId) {
        if (ownerId == null) {
            return;
        }
        try {
            Long deleted = redisTemplate.execute(UNLOCK_SCRIPT, List.of(key), ownerId);
            if (deleted == null || deleted == 0L) {
                log.warn("Unlock for key '{}' with owner '{}' was a no-op — lock may have " +
                         "expired and been re-acquired by another instance", key, ownerId);
            }
        } catch (Exception e) {
            log.warn("Redis lock release failed for key '{}': {}", key, e.getMessage());
        }
        // Two-arg remove: only removes the entry if the stored value still matches ownerId.
        // Guards against accidentally releasing a lock re-acquired by another caller.
        localFallback.remove(key, ownerId);
    }
}