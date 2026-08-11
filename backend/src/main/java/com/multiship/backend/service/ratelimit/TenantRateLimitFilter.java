package com.multiship.backend.service.ratelimit;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.dto.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Sprint 50 finding #15 (A) — servlet filter that gates write traffic
 * per tenant. Runs after {@code JwtAuthenticationFilter} + {@code
 * ApiKeyAuthenticationFilter} so the {@link SecurityContextHolder}
 * already carries the caller's authentication.
 *
 * <p>Only fires on POST/PUT/DELETE to a small allowlist of hot paths
 * — GETs and non-hot paths pass through untouched so a poll or a
 * settings read never eats a token. Platform operators
 * ({@link AccessScopePolicy#tenantOf(org.springframework.security.core.Authentication)}
 * empty) also pass through — they have no tenant to key on and their
 * traffic is trusted.
 *
 * <p>On denial the response mirrors
 * {@code SecurityConfig.writeJsonError}'s shape so integrators can
 * parse it with the same helper they use for 401/403 (errorCode
 * {@link ErrorCode#TENANT_RATE_LIMITED}), and adds the RFC 7231
 * {@code Retry-After} header in seconds.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantRateLimitFilter extends OncePerRequestFilter {

    /** HTTP methods that consume tokens. Reads are never rate-limited. */
    private static final Set<String> WATCHED_METHODS = Set.of("POST", "PUT", "DELETE");

    /**
     * Hot write paths. Everything else passes through. Kept deliberately
     * small — the goal is to protect the label + import pipelines that
     * fan out to carriers, not every settings CRUD endpoint.
     */
    private static final List<String> DEFAULT_WATCHED_PATHS = List.of(
            "/api/v1/orders/*/label",
            "/api/v1/orders/manual-label",
            "/api/v1/orders/multi-warehouse-label",
            "/api/v1/bulk-labels",
            "/api/v1/order-import/commit",
            "/api/v1/rate-shop",
            "/api/v2/external/shipments",
            "/api/v2/external/shipments/*/void",
            "/api/v2/external/rates",
            "/api/v1/external/**");

    private final TenantRateLimiter limiter;
    private final AccessScopePolicy accessScope;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /** Ops kill-switch — set to false to disable entirely (e.g. during incident). */
    @Value("${tenant.rate-limit.enabled:true}")
    private boolean enabled;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!enabled || !isWatched(request)) {
            chain.doFilter(request, response);
            return;
        }
        Optional<String> tenant = accessScope.tenantOf(
                SecurityContextHolder.getContext().getAuthentication());
        if (tenant.isEmpty()) {
            // Operator or unauthenticated — auth filter handles the latter;
            // operators are trusted and never keyed.
            chain.doFilter(request, response);
            return;
        }
        TenantRateLimiter.Outcome outcome = limiter.tryAcquire(tenant.get());
        if (outcome.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        writeRateLimitedResponse(response, tenant.get(), outcome.retryAfterSeconds());
    }

    private boolean isWatched(HttpServletRequest request) {
        if (!WATCHED_METHODS.contains(request.getMethod())) return false;
        String path = request.getRequestURI();
        // Strip context path so patterns match against the application-relative URI.
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && path.startsWith(ctx)) {
            path = path.substring(ctx.length());
        }
        for (String pattern : DEFAULT_WATCHED_PATHS) {
            if (pathMatcher.match(pattern, path)) return true;
        }
        return false;
    }

    private void writeRateLimitedResponse(HttpServletResponse response,
                                          String tenant,
                                          long retryAfterSeconds) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType("application/json");
        String message = String.format(
                "Tenant %s exceeded the per-minute request budget. Retry after %d seconds.",
                tenant, retryAfterSeconds);
        response.getWriter().write(String.format(
                "{\"status\":\"error\",\"code\":429,\"errorCode\":\"%s\",\"message\":\"%s\","
                        + "\"timestamp\":\"%s\",\"data\":null,\"errors\":null,\"retryAfterSeconds\":%d}",
                ErrorCode.TENANT_RATE_LIMITED.name(), message, Instant.now(), retryAfterSeconds));
    }
}
