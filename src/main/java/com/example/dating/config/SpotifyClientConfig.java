package com.example.dating.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Batch F (Scalability) — Async Spotify Sync and Blocking I/O
 *
 * <p>Provides a singleton {@link RestTemplate} for all Spotify API calls with:
 * <ul>
 *   <li>5 s connect timeout — prevents threads hanging on DNS/TCP setup</li>
 *   <li>10 s read timeout   — prevents threads blocking on slow Spotify responses</li>
 * </ul>
 *
 * <p>Before this change, {@link com.example.dating.services.impl.SpotifyServiceImpl}
 * created {@code new RestTemplate()} on every method call — no timeouts, no connection
 * pool reuse, no socket reuse.
 */
@Configuration
public class SpotifyClientConfig {

    @Bean
    public RestTemplate spotifyRestTemplate() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return new RestTemplate(factory);
    }
}