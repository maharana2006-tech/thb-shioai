package com.multiship.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Sprint 51 M-Ops (audit finding BP-M5) — per-request MDC context.
 *
 * <p>Before this landed, {@code MDC.put} was never called anywhere in
 * the codebase; {@link com.multiship.backend.service.fairness.FairTenantExecutor}
 * carefully snapshotted an empty map. Every log line was orphaned from
 * its request: to answer "what happened to order 12345?" ops had to
 * time-window-grep across every level of the app.
 *
 * <p>This filter puts three keys in MDC on every request:
 * <ul>
 *   <li>{@code requestId} — from the {@code X-Request-Id} header if
 *       supplied by an upstream (LB, gateway), else a fresh UUID so the
 *       app is a valid correlation source on its own.</li>
 *   <li>{@code tenant} — resolved from
 *       {@link JwtAuthenticationFilter.AuthDetails#clientCode()} when the
 *       caller's JWT / API key carries one; empty for platform operators.</li>
 *   <li>{@code userId} — {@code Authentication.getName()} — the subject
 *       of the JWT (or {@code apikey:<id>} for API-key callers).</li>
 * </ul>
 *
 * <p>Positioned AFTER {@code JwtAuthenticationFilter} + {@code ApiKeyAuthenticationFilter}
 * so the SecurityContext is already populated when we read it. Log lines
 * emitted by the auth filters themselves (rare) won't have the tenant /
 * userId keys — that's acceptable since successful auth is silent and
 * failed auth doesn't have a tenant yet anyway.
 *
 * <p>MDC is thread-local; the {@code finally} block clears the keys we
 * added so a pooled request thread reused for a subsequent request
 * starts clean. FairTenantExecutor propagates the snapshot to worker
 * threads (was already prepared for this filter — see line 204 of
 * FairTenantExecutor for the receiving side).
 */
public class MdcCorrelationFilter extends OncePerRequestFilter {

    static final String MDC_REQUEST_ID = "requestId";
    static final String MDC_TENANT = "tenant";
    static final String MDC_USER = "userId";

    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_REQUEST_ID, requestId);
        // Echo back so a caller can correlate their client-side log with
        // ours. Upstream LBs / gateways typically pass their own X-Request-Id
        // in; when they don't, this exposes the id we minted.
        response.setHeader(REQUEST_ID_HEADER, requestId);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(String.valueOf(auth.getPrincipal()))) {
            String name = auth.getName();
            if (name != null && !name.isBlank()) {
                MDC.put(MDC_USER, name);
            }
            Object details = auth.getDetails();
            if (details instanceof JwtAuthenticationFilter.AuthDetails ad
                    && ad.clientCode() != null && !ad.clientCode().isBlank()) {
                MDC.put(MDC_TENANT, ad.clientCode());
            } else if (auth.getPrincipal() instanceof ApiKeyPrincipal api
                    && api.getClientCode() != null && !api.getClientCode().isBlank()) {
                MDC.put(MDC_TENANT, api.getClientCode());
            }
        }

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_TENANT);
            MDC.remove(MDC_USER);
        }
    }
}
