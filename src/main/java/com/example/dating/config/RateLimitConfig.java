package com.example.dating.config;

import com.example.dating.security.CaffeineRateLimiter;
import com.example.dating.security.RateLimiter;
import com.example.dating.security.RedisRateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Registers exactly one {@link RateLimiter} bean:
 *
 * <ul>
 *   <li><b>Redis</b> — when a {@link RedisConnectionFactory} is present in the context
 *       (i.e., {@code spring-boot-starter-data-redis} is on the classpath and a Redis
 *       host is reachable).  Buckets are stored in Redis so limits are enforced across
 *       all application instances.</li>
 *   <li><b>Caffeine fallback</b> — when no {@code RateLimiter} bean has been registered
 *       yet.  Used in local development or test environments where Redis is not available.
 *       Limits are per-instance only.</li>
 * </ul>
 */
@Configuration
public class RateLimitConfig {

    @Bean
    @ConditionalOnBean(RedisConnectionFactory.class)
    public RateLimiter redisRateLimiter(StringRedisTemplate redisTemplate) {
        return new RedisRateLimiter(redisTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(RateLimiter.class)
    public RateLimiter inMemoryRateLimiter() {
        return new CaffeineRateLimiter();
    }
}
