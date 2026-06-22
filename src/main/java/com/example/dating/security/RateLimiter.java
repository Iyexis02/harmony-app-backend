package com.example.dating.security;

import java.time.Duration;

/**
 * Strategy interface for rate limiting.
 *
 * <p>Two implementations are registered via {@link com.example.dating.config.RateLimitConfig}:
 * <ul>
 *   <li>{@link RedisRateLimiter} — shared across all application instances; elected when
 *       a {@code RedisConnectionFactory} bean is present in the context.</li>
 *   <li>{@link CaffeineRateLimiter} — in-process fallback; elected when no
 *       {@code RateLimiter} bean has been registered yet (i.e., Redis is not configured).</li>
 * </ul>
 *
 * <p>Callers pass a composite string key (e.g. {@code "POST:/path:userId"}) together with
 * a capacity and window.  Implementations must be thread-safe.
 */
public interface RateLimiter {

    /**
     * Attempt to consume one token from the named bucket.
     *
     * @param key      unique bucket identifier
     * @param capacity maximum requests allowed in {@code window}
     * @param window   the time window for the capacity limit
     * @return a {@link ConsumeResult} indicating whether the request is allowed and,
     *         if not, how many seconds the caller should wait before retrying
     */
    ConsumeResult tryConsume(String key, int capacity, Duration window);

    /**
     * Immutable result of a single {@link #tryConsume} call.
     */
    record ConsumeResult(boolean consumed, long retryAfterSeconds) {

        /** Factory: request was allowed. */
        static ConsumeResult allowed() {
            return new ConsumeResult(true, 0L);
        }

        /** Factory: request was rejected — caller should wait {@code retryAfterSeconds}. */
        static ConsumeResult rejected(long retryAfterSeconds) {
            return new ConsumeResult(false, retryAfterSeconds);
        }
    }
}
