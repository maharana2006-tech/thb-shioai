package com.multiship.backend.controller.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.ApiKeyPrincipal;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.service.ratelimit.ApiKeyRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDateTime;

/**
 * Sprint 50 Tier 1 finding #15 — HandlerInterceptor that gates every
 * public v2 API request through {@link ApiKeyRateLimiter}. On breach it
 * writes a 429 response with the {@code Retry-After} header + a standard
 * {@link ApiResponse} body carrying {@link ErrorCode#API_KEY_RATE_LIMITED}.
 *
 * <p>Interceptor is preferred over Filter here because we need Spring
 * Security's SecurityContext populated already — the interceptor runs
 * AFTER SecurityFilterChain, so {@code SecurityContextHolder} carries
 * the {@link ApiKeyPrincipal}. Wired only to {@code /api/v2/external/**}
 * in {@link ApiKeyRateLimitWebMvcConfig}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyRateLimitInterceptor implements HandlerInterceptor {

    private final ApiKeyRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        Long apiKeyId = resolveApiKeyId();
        ApiKeyRateLimiter.Decision decision = rateLimiter.tryAcquire(apiKeyId);

        // Even allowed responses carry the rate-limit headers so
        // integrators can adapt their pacing in real time. Sprint 50 PR L
        // — omit X-RateLimit-Remaining when Redis is down / count is
        // unknown; emitting the full-budget number would trick integrators
        // into hammering the API during an outage. Limit stays; only
        // Remaining is conditionally set.
        response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
        if (decision.countKnown()) {
            response.setHeader("X-RateLimit-Remaining",
                    String.valueOf(Math.max(0, decision.limit() - decision.currentCount())));
        }

        if (decision.allowed()) {
            return true;
        }

        // Denied → 429 + Retry-After + standard ApiResponse body.
        log.info("Rate-limit denial for apiKey={} on {} {} (count={} > limit={})",
                apiKeyId, request.getMethod(), request.getRequestURI(),
                decision.currentCount(), decision.limit());
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiResponse<Object> body = ApiResponse.builder()
                .status("ERROR")
                .code(HttpStatus.TOO_MANY_REQUESTS.value())
                .errorCode(ErrorCode.API_KEY_RATE_LIMITED.name())
                .message("Rate limit exceeded (" + decision.limit()
                        + "/min per API key). Retry after "
                        + decision.retryAfterSeconds() + "s.")
                .timestamp(LocalDateTime.now())
                .build();
        response.getWriter().write(objectMapper.writeValueAsString(body));
        return false;
    }

    private static Long resolveApiKeyId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof ApiKeyPrincipal principal) {
            return principal.getApiKeyId();
        }
        return null;
    }
}
