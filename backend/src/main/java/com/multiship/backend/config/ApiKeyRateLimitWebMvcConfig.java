package com.multiship.backend.config;

import com.multiship.backend.controller.ratelimit.ApiKeyRateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sprint 50 Tier 1 finding #15 — wires {@link ApiKeyRateLimitInterceptor}
 * onto the public v2 API mount point. Scoped tightly to
 * {@code /api/v2/external/**} so the internal admin / dashboard / v1
 * paths are unaffected. Internal per-tenant limiting is a follow-up
 * (needs a different key derivation — clientCode via JWT — and
 * different scope).
 */
@Configuration
@RequiredArgsConstructor
public class ApiKeyRateLimitWebMvcConfig implements WebMvcConfigurer {

    private final ApiKeyRateLimitInterceptor interceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor)
                .addPathPatterns("/api/v2/external/**");
    }
}
