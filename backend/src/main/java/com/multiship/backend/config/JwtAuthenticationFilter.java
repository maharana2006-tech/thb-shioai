package com.multiship.backend.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.filter.OncePerRequestFilter;

import com.multiship.backend.model.ApiKey;
import com.multiship.backend.repository.ApiKeyRepository;
import com.multiship.backend.repository.UserRepository;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Validates the Authorization bearer token as a signed JWT. Requests with a
 * missing, malformed, expired, or tampered token proceed unauthenticated and
 * are rejected downstream (401 by the entry point, or 403 by method security).
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    /** Sprint 46 — optional; when null, OAuth-issued API tokens still work
     *  but the principal is a plain UserDetails and downstream controllers
     *  that expect ApiKeyPrincipal see null. */
    private final ApiKeyRepository apiKeyService;
    /** Sprint 50 Tier 0.5 PR A — optional; when non-null and a token carries
     *  no clientCode claim, the filter falls back to a DB lookup here so
     *  pre-migration tokens keep working through the 24h JWT window. */
    private final UserRepository userRepository;

    /**
     * Sprint 50 Tier 0.5 PR A — 5-min cache on the username → clientCode
     * fallback lookup so a burst of pre-migration-token requests doesn't
     * pound the users table. Bounded at 10k entries; TTL keeps flipped-in
     * assignments visible within a window.
     */
    private final Cache<String, String> clientCodeFallbackCache =
            Caffeine.newBuilder()
                    .maximumSize(10_000)
                    .expireAfterWrite(Duration.ofMinutes(5))
                    .build();

    public JwtAuthenticationFilter(JwtService jwtService) {
        this(jwtService, null, null);
    }

    public JwtAuthenticationFilter(JwtService jwtService, ApiKeyRepository apiKeyRepository) {
        this(jwtService, apiKeyRepository, null);
    }

    public JwtAuthenticationFilter(JwtService jwtService, ApiKeyRepository apiKeyRepository,
                                    UserRepository userRepository) {
        this.jwtService = jwtService;
        this.apiKeyService = apiKeyRepository;
        this.userRepository = userRepository;
    }

    /**
     * Sprint 50 Tier 0.5 PR A — carrier for the clientCode resolved for the
     * current request. Attached to {@code Authentication.details}. Empty
     * during rollout means "legacy internal operator" (see PR E logic).
     */
    public record AuthDetails(String clientCode) {}

    /**
     * Sprint 50 Tier 0.5 PR A — DB fallback for pre-migration tokens whose
     * JWT payload predates the {@code clientCode} claim. Cached 5 min so a
     * burst of legacy-token requests doesn't hammer the users table.
     * Returns {@code null} for unknown username (legacy internal operator).
     */
    private String resolveClientCodeFromDb(String username) {
        String cached = clientCodeFallbackCache.getIfPresent(username);
        if (cached != null) {
            // "" sentinel = we looked and found no clientCode; skip re-hitting DB.
            return cached.isEmpty() ? null : cached;
        }
        Optional<com.multiship.backend.model.User> row = userRepository.findByUsername(username);
        String code = row.map(com.multiship.backend.model.User::getClientCode).orElse(null);
        clientCodeFallbackCache.put(username, code == null ? "" : code);
        return code;
    }

    /** Reads "apikey:<id>" subject and rehydrates an ApiKeyPrincipal. */
    private ApiKeyPrincipal apiKeyPrincipalFromToken(String subject) {
        try {
            Long id = Long.parseLong(subject.substring("apikey:".length()));
            ApiKey key = apiKeyService.findById(id).orElse(null);
            if (key == null || !key.isActive()) return null;
            Set<String> scopes = key.getScopes() == null ? Set.of()
                    : Set.of(key.getScopes().trim().split("\\s+"));
            return new ApiKeyPrincipal(key.getId(), key.getName(), key.getClientCode(), scopes);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Sprint 50 PR Q1 — cookie name auth writes on login. Kept in sync
     *  with {@code AuthServiceImpl.AUTH_COOKIE}. Duplicated here rather
     *  than referenced to avoid a config→service compile cycle. */
    private static final String AUTH_COOKIE_NAME = "multiship_token";

    /** Sprint 50 PR Q1 — accept the JWT from either the Authorization
     *  header (back-compat with pre-cookie clients + server-to-server
     *  callers) or the httpOnly cookie set on login. Header wins when
     *  both are present so a manual API test with a Bearer header still
     *  identifies the caller during the transitional window. */
    private String resolveToken(HttpServletRequest request) {
        String h = request.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) {
            return h.substring(7);
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (AUTH_COOKIE_NAME.equals(c.getName())
                        && c.getValue() != null
                        && !c.getValue().isBlank()) {
                    return c.getValue();
                }
            }
        }
        return null;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        // Note: no path is skipped. The filter never rejects a request by
        // itself — it only populates the security context when a valid token
        // is present. Open endpoints stay open via permitAll, and a token
        // sent to /api/v1/auth/signup still identifies the caller (used to
        // authorize ADMIN account creation).
        //
        // Sprint 50 PR Q1 — resolveToken() prefers the Authorization header
        // (back-compat with pre-cookie clients) and falls through to the
        // multiship_token httpOnly cookie set by AuthServiceImpl on login.
        // Both paths reach the same JWT-parse block below.
        String token = resolveToken(request);

        if (token != null) {
            // API keys (msk_...) are handled by ApiKeyAuthenticationFilter — don't
            // treat one as a JWT (parsing would fail and clear a valid context).
            if (token.startsWith("msk_")) {
                filterChain.doFilter(request, response);
                return;
            }

            try {
                Claims claims = jwtService.parseClaims(token);
                String username = claims.getSubject();
                String role = claims.get("role", String.class);
                // Sprint 50 Tier 0.5 PR A — new claim; MAY be null for
                // pre-migration tokens. Filter attaches it to
                // Authentication.details so downstream OrderAccessEvaluator
                // (PR E) reads it without a DB hit.
                String clientCode = claims.get("clientCode", String.class);
                if ((clientCode == null || clientCode.isBlank())
                        && username != null
                        && !username.startsWith("apikey:")
                        && userRepository != null) {
                    clientCode = resolveClientCodeFromDb(username);
                }

                if (username != null && role != null) {
                    var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
                    UserDetails principal;

                    // Sprint 46 — OAuth-issued tokens carry subject "apikey:<id>"
                    // when the caller authenticated via the client-credentials
                    // grant. Rehydrate an ApiKeyPrincipal so v1/v2 external
                    // controllers work uniformly whether the caller hit them
                    // with X-API-Key or a Bearer JWT from /oauth/token.
                    //
                    // Audit-fix #2: apiKeyPrincipalFromToken returns null when
                    // the ApiKey is missing, revoked (active=false), or the
                    // subject is malformed. Previously we constructed a
                    // UsernamePasswordAuthenticationToken with a null principal
                    // — which Spring marks authenticated=true and still lets
                    // ROLE_API pass method security. A revoked key's still-
                    // unexpired JWT would authenticate as if the key were
                    // live. Skip populating the context when the principal is
                    // null so the request proceeds unauthenticated (401).
                    if ("API".equalsIgnoreCase(role) && username.startsWith("apikey:") && apiKeyService != null) {
                        principal = apiKeyPrincipalFromToken(username);
                    } else {
                        // Controllers resolve the caller via @AuthenticationPrincipal
                        // UserDetails, so the principal must stay a UserDetails.
                        principal = User.withUsername(username)
                                .password("")
                                .authorities(authorities)
                                .build();
                    }

                    if (principal != null) {
                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(principal, null, authorities);
                        // Sprint 50 Tier 0.5 PR A — expose the resolved
                        // clientCode via Authentication.details so PR E's
                        // OrderAccessEvaluator + service-layer guards can
                        // read it without repeating the DB lookup.
                        if (clientCode != null && !clientCode.isBlank()) {
                            authentication.setDetails(new AuthDetails(clientCode));
                        }
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    }
                }
            } catch (JwtException | IllegalArgumentException ex) {
                // Invalid or expired token: leave the context unauthenticated.
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }
}
