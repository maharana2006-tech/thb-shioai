package com.multiship.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Sprint 51 BP-M5 — after the auth filters populate the
 * {@link SecurityContextHolder}, copy the caller's identity (tenant,
 * userId, apiKeyId) into MDC so log lines and Prometheus tags include
 * them. Runs AFTER {@link JwtAuthenticationFilter} +
 * {@code ApiKeyAuthenticationFilter} so the security context is stable
 * by the time we read it.
 *
 * <p>Wired into the chain via {@code SecurityConfig.addFilterAfter}
 * rather than a global {@code @Order} so its position is deterministic
 * relative to the security filter chain (Spring Security registers its
 * own DelegatingFilterProxy at a fixed order and a competing global
 * order can leapfrog the auth filters).
 */
@Component
public class MdcTenantFilter extends OncePerRequestFilter {

    public static final String MDC_TENANT = "tenant";
    public static final String MDC_USER_ID = "userId";
    public static final String MDC_API_KEY_ID = "apiKeyId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean populated = false;
        try {
            if (auth != null && auth.isAuthenticated()) {
                Object principal = auth.getPrincipal();
                if (principal instanceof ApiKeyPrincipal apiKey) {
                    MDC.put(MDC_API_KEY_ID, String.valueOf(apiKey.getApiKeyId()));
                    if (apiKey.getClientCode() != null && !apiKey.getClientCode().isBlank()) {
                        MDC.put(MDC_TENANT, apiKey.getClientCode());
                    }
                    populated = true;
                } else {
                    String name = auth.getName();
                    if (name != null && !name.isBlank()) {
                        MDC.put(MDC_USER_ID, name);
                        populated = true;
                    }
                    if (auth.getDetails() instanceof JwtAuthenticationFilter.AuthDetails details
                            && details.clientCode() != null
                            && !details.clientCode().isBlank()) {
                        MDC.put(MDC_TENANT, details.clientCode());
                        populated = true;
                    }
                }
            }
            chain.doFilter(request, response);
        } finally {
            if (populated) {
                MDC.remove(MDC_TENANT);
                MDC.remove(MDC_USER_ID);
                MDC.remove(MDC_API_KEY_ID);
            }
        }
    }
}
