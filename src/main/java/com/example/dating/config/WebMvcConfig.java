package com.example.dating.config;

import com.example.dating.security.AuthenticatedRateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC configuration — registers interceptors that must run after
 * Spring Security has already validated the JWT (i.e., authenticated-endpoint
 * concerns that need a resolved {@code userId}).
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthenticatedRateLimitInterceptor authenticatedRateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authenticatedRateLimitInterceptor)
                .addPathPatterns(
                        "/api/v1/matching/swipe",
                        "/api/v1/matching/score/**"
                );
    }
}
