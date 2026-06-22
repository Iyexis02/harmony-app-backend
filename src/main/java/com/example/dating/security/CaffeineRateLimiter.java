package com.example.dating.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * In-process {@link RateLimiter} backed by Caffeine + bucket4j.
 *
 * <p>Used as the fallback when Redis is not configured. Works correctly for
 * single-instance deployments; in a multi-instance deployment each JVM
 * maintains independent buckets, so users can exceed limits by routing to
 * different instances — use {@link RedisRateLimiter} to close that gap.
 *
 * <p>The shared bucket store is capped at 200 000 entries and entries idle
 * for 30 minutes are evicted.
 */
public class CaffeineRateLimiter implements RateLimiter {

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(200_000)
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .build();

    @Override
    public ConsumeResult tryConsume(String key, int capacity, Duration window) {
        Bucket bucket = buckets.get(key, k ->
                Bucket.builder()
                        .addLimit(Bandwidth.classic(capacity, Refill.intervally(capacity, window)))
                        .build());

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            return ConsumeResult.allowed();
        }
        return ConsumeResult.rejected(TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
    }
}