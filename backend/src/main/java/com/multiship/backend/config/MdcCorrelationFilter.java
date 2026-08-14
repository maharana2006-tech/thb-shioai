package com.multiship.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Sprint 51 BP-M5 — populate {@code MDC.requestId} for every request so
 * each log line carries the same correlation id from the first Tomcat
 * touchpoint to the response flush. Runs BEFORE auth filters so even
 * unauthenticated 401 flows carry a request id (auditors can trace
 * failed logins to their origin).
 *
 * <p>Accepts an inbound {@code X-Request-Id} header when the caller has
 * one — typically an API gateway / load balancer that stamps every
 * request. Missing / blank header → mint a fresh UUID so cross-service
 * tracing still works even from raw curl or a browser.
 *
 * <p>MDC is thread-local; the {@code finally} clears our key so a Tomcat
 * worker thread reused for the next request never inherits a stale id.
 * {@link com.multiship.backend.service.fairness.FairTenantExecutor} already
 * snapshots + restores MDC across executor hops, so batch workers see the
 * caller's id too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcCorrelationFilter extends OncePerRequestFilter {

    /** MDC key — kept short so log lines stay narrow. */
    public static final String MDC_REQUEST_ID = "requestId";

    /** Inbound header the API gateway / LB stamps. */
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = request.getHeader(HEADER_REQUEST_ID);
        if (!StringUtils.hasText(requestId)) {
            // 16-char prefix keeps log lines readable while retaining
            // enough entropy for cross-log correlation.
            requestId = UUID.randomUUID().toString().substring(0, 16);
        }
        try {
            MDC.put(MDC_REQUEST_ID, requestId);
            // Echo on the response so clients can log the same id and
            // report it in bug tickets — makes ops-side lookup trivial.
            response.setHeader(HEADER_REQUEST_ID, requestId);
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_REQUEST_ID);
        }
    }
}
