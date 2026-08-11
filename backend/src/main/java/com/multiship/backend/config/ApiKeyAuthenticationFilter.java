package com.multiship.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Authenticates external callers by API key. The key may arrive as an
 * {@code X-API-Key} header or as {@code Authorization: Bearer msk_...}. Like the
 * JWT filter, it never rejects a request itself when the key is unknown — it
 * only populates the security context; downstream authorization decides.
 *
 * <p>Sprint 50 Tier 0.5 PR C — when the key exists but is expired or has
 * been rotated past its grace window, the filter writes a specific 401
 * body ({@code errorCode=API_KEY_EXPIRED} / {@code API_KEY_ROTATED}) so the
 * integrator can diagnose without a support ticket. For rotated-but-in-grace
 * keys the filter attaches {@code Deprecation} + {@code Sunset} headers
 * (RFC 8594).
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final ApiKeyService apiKeyService;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService) {
        this(apiKeyService, new ObjectMapper());
    }

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService, ObjectMapper objectMapper) {
        this.apiKeyService = apiKeyService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String token = resolveToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            ApiKeyService.AuthResult result = apiKeyService.authenticateDetailed(token);
            switch (result.kind()) {
                case AUTHORIZED -> {
                    ApiKey key = result.key();
                    ApiKeyPrincipal principal = new ApiKeyPrincipal(
                            key.getId(), key.getName(), key.getClientCode(), scopesOf(key));
                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    // Sprint 50 Tier 0.5 PR C — RFC 8594 deprecation signal for
                    // rotated keys inside the grace window.
                    if (result.deprecated() && result.sunsetAt() != null) {
                        response.setHeader("Deprecation", "true");
                        response.setHeader("Sunset", httpDate(result.sunsetAt()));
                    }
                }
                case EXPIRED -> {
                    writeError(response, "API_KEY_EXPIRED",
                            "API key expired. Rotate via POST /api/v1/api-keys/{id}/rotate.");
                    return;
                }
                case ROTATED_EXPIRED -> {
                    writeError(response, "API_KEY_ROTATED",
                            "API key was rotated and the 24h grace window has ended. "
                                    + "Use the new token issued at rotation time.");
                    return;
                }
                case INVALID -> {
                    // Fall through — Spring Security's entry point issues the generic 401.
                }
            }
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

    private void writeError(HttpServletResponse response, String errorCode, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("code", 401);
        body.put("errorCode", errorCode);
        body.put("message", message);
        body.put("timestamp", Instant.now().toString());
        body.put("data", null);
        body.put("errors", null);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private static String httpDate(LocalDateTime ldt) {
        // RFC 7231 IMF-fixdate format (Sun, 06 Nov 1994 08:49:37 GMT) — required by RFC 8594.
        return DateTimeFormatter.RFC_1123_DATE_TIME
                .withLocale(Locale.US)
                .format(ldt.atOffset(ZoneOffset.UTC));
    }
}
