package com.multiship.backend.service.ratelimit;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.dto.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
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

    /**
     * Sprint 51 BS-M2 — AI-assist endpoints get their own (tighter) bucket.
     * OpenAI calls cost real money, so a runaway loop from one tenant needs
     * a lower ceiling than the general write budget. Charged against the
     * AI-specific bucket in {@link TenantRateLimiter} (10/min default vs.
     * the general 100/min). Per-tenant token metering is emitted from
     * {@code OpenAiClient} (Sprint 51 follow-up BS-M2) — see the
     * {@code openai_*} series on {@code /actuator/prometheus}.
     */
    private static final List<String> DEFAULT_AI_PATHS = List.of(
            "/api/v1/ai/**");

    private final TenantRateLimiter limiter;
    private final AccessScopePolicy accessScope;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * Sprint 51 BP-M4 — Micrometer wiring. Optional so tests that build the
     * filter directly still compile without a registry; production always
     * has the actuator's Prometheus meter registry bean.
     */
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    @org.springframework.beans.factory.annotation.Autowired
    public TenantRateLimitFilter(TenantRateLimiter limiter,
                                 AccessScopePolicy accessScope,
                                 ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.limiter = limiter;
        this.accessScope = accessScope;
        this.meterRegistryProvider = meterRegistryProvider;
    }

    /** Sprint 51 BP-M4 — back-compat overload for tests written before the
     *  meter-registry dependency landed. Production always goes through the
     *  three-arg constructor via Spring wiring. */
    public TenantRateLimitFilter(TenantRateLimiter limiter, AccessScopePolicy accessScope) {
        this(limiter, accessScope, null);
    }

    /** Ops kill-switch — set to false to disable entirely (e.g. during incident). */
    @Value("${tenant.rate-limit.enabled:true}")
    private boolean enabled;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }
        TenantRateLimiter.BucketType bucket = bucketFor(request);
        if (bucket == null) {
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
        TenantRateLimiter.Outcome outcome = limiter.tryAcquire(tenant.get(), bucket);
        if (outcome.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        // Sprint 51 BP-M4 — count every 429 so ops can alert on a
        // sustained rate-limit spike. Tenant is a label so alerting can
        // pinpoint the offending client without shipping a metric explosion
        // (limiter's own internal cap keeps the cardinality bounded).
        MeterRegistry registry = meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
        if (registry != null) {
            Counter.builder("tenant.rate_limit.blocked")
                    .tag("tenant", tenant.get())
                    .description("Requests rejected with 429 by the per-tenant rate limiter.")
                    .register(registry)
                    .increment();
        }
        writeRateLimitedResponse(response, tenant.get(), outcome.retryAfterSeconds());
    }

    /**
     * Which bucket to charge, or {@code null} when the path is out of scope.
     * AI paths use POST only (their controller has @PostMapping) so the
     * watched-methods gate below applies to both bucket types.
     */
    private TenantRateLimiter.BucketType bucketFor(HttpServletRequest request) {
        if (!WATCHED_METHODS.contains(request.getMethod())) return null;
        String path = request.getRequestURI();
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && path.startsWith(ctx)) {
            path = path.substring(ctx.length());
        }
        for (String pattern : DEFAULT_AI_PATHS) {
            if (pathMatcher.match(pattern, path)) return TenantRateLimiter.BucketType.AI;
        }
        for (String pattern : DEFAULT_WATCHED_PATHS) {
            if (pathMatcher.match(pattern, path)) return TenantRateLimiter.BucketType.DEFAULT;
        }
        return null;
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
