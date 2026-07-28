package com.multiship.backend.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
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

import java.io.IOException;
import java.util.List;
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

    public JwtAuthenticationFilter(JwtService jwtService) {
        this(jwtService, null);
    }

    public JwtAuthenticationFilter(JwtService jwtService, ApiKeyRepository apiKeyRepository) {
        this.jwtService = jwtService;
        this.apiKeyService = apiKeyRepository;
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
        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);

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
