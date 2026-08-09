package com.multiship.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Sprint 50 Tier 0.5 PR B — enforces {@link RequiresScope} on controller
 * handlers.
 *
 * <p>Runs after Spring Security has resolved the principal but before the
 * controller method is invoked. Skips the check for non-ApiKey callers
 * (operator JWTs) — scope is an ApiKey concept only. Emits a
 * machine-readable 403 body naming the required + granted scopes so an
 * integrator can diagnose without a support ticket.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyScopeInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod hm)) {
            return true;
        }
        RequiresScope req = findAnnotation(hm);
        if (req == null) {
            return true;  // no scope requirement declared
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return true;  // Spring Security's entry point will handle 401
        }

        Object principal = auth.getPrincipal();
        if (!(principal instanceof ApiKeyPrincipal apiKey)) {
            // Operator JWT (ADMIN / USER / TENANT) — scope enforcement is
            // an API-key concept only. Role gating via @PreAuthorize handles
            // those callers.
            return true;
        }

        String requiredToken = req.value().token();
        if (apiKey.hasScope(requiredToken)) {
            return true;
        }

        writeInsufficientScope(response, requiredToken, apiKey.getScopes(), apiKey.getKeyName());
        return false;
    }

    /** Method-level annotation wins over class-level (which wins over nothing). */
    private RequiresScope findAnnotation(HandlerMethod hm) {
        RequiresScope method = hm.getMethodAnnotation(RequiresScope.class);
        if (method != null) return method;
        return hm.getBeanType().getAnnotation(RequiresScope.class);
    }

    private void writeInsufficientScope(HttpServletResponse response, String required,
                                         Set<String> granted, String keyName) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");

        // Sort granted for stable output — an integrator diffing responses
        // over time shouldn't see spurious changes from Set iteration order.
        TreeSet<String> sortedGranted = new TreeSet<>(granted);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("code", 403);
        body.put("errorCode", "INSUFFICIENT_SCOPE");
        body.put("message", "API key is missing the '" + required + "' scope.");
        body.put("requiredScope", required);
        body.put("grantedScopes", List.copyOf(sortedGranted));
        body.put("timestamp", Instant.now().toString());
        body.put("data", null);
        body.put("errors", null);

        log.warn("API key '{}' denied — required scope '{}' not in granted {}",
                keyName, required, sortedGranted);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
