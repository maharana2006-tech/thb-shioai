package com.multiship.backend.config;

import com.multiship.backend.model.ApiKey;
import com.multiship.backend.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Authenticates external callers by API key. The key may arrive as an
 * {@code X-API-Key} header or as {@code Authorization: Bearer msk_...}. Like the
 * JWT filter, it never rejects a request itself — it only populates the security
 * context when a valid key is present; downstream authorization decides access.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final ApiKeyService apiKeyService;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String token = resolveToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            apiKeyService.authenticate(token).ifPresent(key -> {
                ApiKeyPrincipal principal = new ApiKeyPrincipal(
                        key.getId(), key.getName(), key.getClientCode(), scopesOf(key));
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }

        chain.doFilter(request, response);
    }

    /** X-API-Key wins; otherwise an Authorization bearer token that looks like an API key. */
    private String resolveToken(HttpServletRequest request) {
        String headerKey = request.getHeader("X-API-Key");
        if (StringUtils.hasText(headerKey)) return headerKey.trim();

        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String bearer = authHeader.substring(7).trim();
            if (bearer.startsWith("msk_")) return bearer;
        }
        return null;
    }

    private static Set<String> scopesOf(ApiKey key) {
        if (!StringUtils.hasText(key.getScopes())) return Set.of();
        return Arrays.stream(key.getScopes().trim().split("\\s+")).collect(Collectors.toSet());
    }
}
