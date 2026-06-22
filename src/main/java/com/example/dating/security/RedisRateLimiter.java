package com.example.dating.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Distributed {@link RateLimiter} backed by Redis INCR + EXPIRE.
 *
 * <p>Algorithm (fixed-window counter):
 * <ol>
 *   <li>INCR the counter key — atomically increments and returns the new value.</li>
 *   <li>On the first increment (count == 1), set a TTL equal to {@code window} so the
 *       counter resets automatically at the end of each window.</li>
 *   <li>If the returned count exceeds {@code capacity}, the request is rejected.</li>
 * </ol>
 *
 * <p>All Redis operations are wrapped in a try-catch.  If Redis is unreachable the
 * limiter fails open (allows the request) so that application availability is not
 * impacted by a Redis outage.  Log a WARN so the issue is visible.
 *
 * <p>Key format: {@code rate:<caller-supplied-key>} (prefixed to avoid collisions
 * with other Redis data in the same database).
 */
@Slf4j
public class RedisRateLimiter implements RateLimiter {

    private final StringRedisTemplate redisTemplate;

    public RedisRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public ConsumeResult tryConsume(String key, int capacity, Duration window) {
        String redisKey = "rate:" + key;
        try {
            Long count = redisTemplate.opsForValue().increment(redisKey);

            if (count == null) {
                log.warn("Redis INCR returned null for key '{}' — allowing request (fail-open)", redisKey);
                return ConsumeResult.allowed();
            }

            // Only the first request in a new window sets the expiry — subsequent calls
            // leave the TTL untouched, which is correct for a fixed-window counter.
            if (count == 1) {
                redisTemplate.expire(redisKey, window);
            }

            if (count <= capacity) {
                return ConsumeResult.allowed();
            }

            // Compute a precise retry-after from the remaining TTL.
            Long ttlMillis = redisTemplate.getExpire(redisKey, TimeUnit.MILLISECONDS);
            long retryAfterSeconds = (ttlMillis != null && ttlMillis > 0)
                    ? TimeUnit.MILLISECONDS.toSeconds(ttlMillis) + 1
                    : window.getSeconds();

            return ConsumeResult.rejected(retryAfterSeconds);

        } catch (Exception e) {
            log.warn("Redis rate-limit check failed for key '{}' — allowing request (fail-open): {}",
                    redisKey, e.getMessage());
            return ConsumeResult.allowed();
        }
    }
}
