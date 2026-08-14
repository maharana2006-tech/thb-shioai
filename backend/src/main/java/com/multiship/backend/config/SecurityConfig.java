package com.multiship.backend.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.time.Instant;
import java.util.Arrays;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    // Sprint 50 PR Q2 — cookie flags mirrored from application.properties
    // so the CSRF cookie (which the SPA reads) uses the same Secure /
    // SameSite settings as the auth cookie itself. Otherwise browsers
    // may reject a Secure=false CSRF cookie when the auth cookie is
    // Secure=true, breaking the double-submit-cookie protocol.
    @Value("${cookie.secure:false}")
    private boolean cookieSecure;

    @Value("${cookie.samesite:Strict}")
    private String cookieSameSite;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Sprint 50 finding #15 (A) — {@link com.multiship.backend.service.ratelimit.TenantRateLimitFilter}
     * is a {@code @Component} so its {@code @Value} fields resolve, but
     * Spring Boot would otherwise auto-register it on the servlet container
     * BEFORE the security chain, which would run it before auth. Registering
     * it disabled here prevents that; the chain wires it in via
     * {@code addFilterAfter} so it runs after JwtAuthenticationFilter.
     */
    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<com.multiship.backend.service.ratelimit.TenantRateLimitFilter>
            tenantRateLimitFilterRegistration(
                    com.multiship.backend.service.ratelimit.TenantRateLimitFilter filter) {
        var reg = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    /**
     * Sprint 51 BP-M5 — same trick for {@link MdcTenantFilter}. It runs
     * inside the security chain via {@code addFilterAfter(JwtAuthenticationFilter)}
     * so the SecurityContext is populated by the time it reads. Disable
     * auto-registration on the servlet container so it doesn't ALSO run
     * once before the chain (empty MDC values, wasted CPU).
     */
    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<MdcTenantFilter>
            mdcTenantFilterRegistration(MdcTenantFilter filter) {
        var reg = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService,
                                                   com.multiship.backend.service.ApiKeyService apiKeyService,
                                                   com.multiship.backend.repository.ApiKeyRepository apiKeyRepository,
                                                   com.multiship.backend.repository.UserRepository userRepository,
                                                   com.multiship.backend.service.TokenRevocationService revocationService,
                                                   com.multiship.backend.service.ratelimit.TenantRateLimitFilter tenantRateLimitFilter,
                                                   MdcTenantFilter mdcTenantFilter)
            throws Exception {
        // Sprint 50 PR Q2 — CSRF via double-submit cookie. The
        // SPA reads the XSRF-TOKEN cookie (HttpOnly=false so JS can
        // read it) and echoes it as the X-XSRF-TOKEN header on every
        // state-changing request. Spring compares the two. Sync the
        // cookie Secure/SameSite with the auth-cookie flags so the
        // browser doesn't reject one and accept the other under
        // cross-origin dev.
        CookieCsrfTokenRepository csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepo.setCookieCustomizer(c -> c.secure(cookieSecure).sameSite(cookieSameSite).path("/"));

        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepo)
                        // Spring 6 default XorCsrfTokenRequestAttributeHandler
                        // BREACH-encodes the token, which forces the SPA to
                        // call the server to "resolve" it — but the SPA
                        // reads the raw cookie value. Use the plain handler
                        // so the raw value is what Spring compares.
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        // Endpoints that authenticate via API key / HMAC / OAuth
                        // client-credentials — CSRF is irrelevant there (the
                        // caller can't produce a browser's XSRF-TOKEN cookie
                        // and isn't logged in via a session cookie either).
                        .ignoringRequestMatchers(
                                "/api/v1/auth/login",
                                "/api/v1/auth/signup",
                                "/api/v1/auth/accept-invite",
                                "/api/v1/auth/verify-email",
                                "/api/v1/oauth/token",
                                "/api/v1/webhooks/carrier/**",
                                "/api/v1/external/**",
                                "/api/v2/external/**",
                                // Sprint 51 FE-M3 — client-side render errors
                                // are POSTed pre-login too; the endpoint is
                                // IP-rate-limited in the controller itself.
                                "/api/v1/client-errors"
                        )
                )
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Open endpoints
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // The container's internal forward to /error when a controller
                        // throws (e.g. ResponseStatusException) doesn't run
                        // JwtAuthenticationFilter — OncePerRequestFilter skips ERROR
                        // dispatches by default — so the security context is empty on
                        // that forward. Without this, anyRequest().authenticated() fails
                        // for /error and the entry point overwrites the real status (e.g.
                        // 503/422/502 from the AI endpoints) with a bogus 401, which the
                        // frontend treats as an expired session and force-logs-out.
                        .requestMatchers("/error").permitAll()
                        // Sprint 46 — OAuth 2.0 token endpoint is public; the
                        // caller authenticates with client credentials in the
                        // body, not with a Bearer token.
                        .requestMatchers("/api/v1/oauth/token").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // CSV template is static schema (headers + one dummy row) — safe
                        // to expose publicly so the browser can download it via a plain
                        // <a href>, no Bearer header to attach.
                        .requestMatchers(HttpMethod.GET, "/api/v1/orders/import/template.csv").permitAll()
                        // Sprint 36 — carrier webhook receiver: no JWT (carriers can't
                        // produce our tokens). Signature verification per carrier via
                        // HMAC-SHA256 in the request header.
                        .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/carrier/**").permitAll()
                        // Sprint 51 FE-M3 — client-error telemetry: no auth
                        // (crashes happen on the login page too). IP-rate-
                        // limited by the controller.
                        .requestMatchers(HttpMethod.POST, "/api/v1/client-errors").permitAll()
                        // Admin-only credential management, decided before the
                        // request body is even parsed (403 beats 400).
                        .requestMatchers("/api/v1/carriers/connect", "/api/v1/carriers/disconnect").hasRole("ADMIN")
                        .requestMatchers("/api/v1/api-keys/**").hasRole("ADMIN")
                        // Public shipping API for external apps — API key (ROLE_API); ADMIN allowed for testing.
                        .requestMatchers("/api/v1/external/**").hasAnyRole("API", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/orders/*/label").hasAnyRole("ADMIN", "USER")
                        // All other requests need authentication
                        .anyRequest().authenticated()
                )
                .exceptionHandling(handling -> handling
                        // Missing/invalid token -> 401, authenticated but wrong role -> 403.
                        .authenticationEntryPoint((request, response, ex) ->
                                writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED,
                                        "UNAUTHORIZED", "Authentication required. Please sign in again."))
                        .accessDeniedHandler((request, response, ex) ->
                                writeJsonError(response, HttpServletResponse.SC_FORBIDDEN,
                                        "FORBIDDEN", "You do not have permission to perform this action."))
                )
                .addFilterBefore(new ApiKeyAuthenticationFilter(apiKeyService), UsernamePasswordAuthenticationFilter.class)
                // Sprint 50 Tier 0.5 PR A — UserRepository injected for the
                // pre-migration-token clientCode fallback lookup (cached 5m).
                // Sprint 51 T2 — TokenRevocationService drives per-request
                // tv + jti checks so a revoked token can never authenticate.
                .addFilterBefore(new JwtAuthenticationFilter(jwtService, apiKeyRepository, userRepository, revocationService),
                        UsernamePasswordAuthenticationFilter.class)
                // Sprint 51 BP-M5 — MDC tenant filter runs AFTER auth so the
                // SecurityContext already carries the caller. Populates MDC
                // {tenant, userId, apiKeyId} for the logging pattern +
                // Prometheus tags before any controller runs.
                .addFilterAfter(mdcTenantFilter, JwtAuthenticationFilter.class)
                // Sprint 50 finding #15 (A) — must run AFTER the two auth filters
                // above so the SecurityContext already carries the caller's
                // authentication when the rate check reads the tenant.
                .addFilterAfter(tenantRateLimitFilter, JwtAuthenticationFilter.class)
                // CsrfTokenRequestAttributeHandler defers resolving the
                // CsrfToken — the XSRF-TOKEN cookie is only written when
                // something actually calls CsrfToken.getToken() during the
                // request. Without this filter the cookie may never get
                // set during normal browsing, so the SPA's echoed
                // X-XSRF-TOKEN header is always empty and every
                // state-changing request 403s as "no permission" even for
                // an ADMIN. Force the resolve on every request so the
                // cookie is always present after the first response.
                .addFilterAfter(csrfCookieFilter(), CsrfFilter.class);

        return http.build();
    }

    private OncePerRequestFilter csrfCookieFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                                             HttpServletResponse response,
                                             jakarta.servlet.FilterChain filterChain)
                    throws jakarta.servlet.ServletException, java.io.IOException {
                CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
                if (csrfToken != null) {
                    csrfToken.getToken();
                }
                filterChain.doFilter(request, response);
            }
        };
    }

    private static void writeJsonError(HttpServletResponse response, int status, String errorCode, String message)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(String.format(
                "{\"status\":\"error\",\"code\":%d,\"errorCode\":\"%s\",\"message\":\"%s\",\"timestamp\":\"%s\",\"data\":null,\"errors\":null}",
                status, errorCode, message, Instant.now()));
    }

    /**
     * Sprint 50 PR N post-audit finding #17 — CORS origins are now
     * env-driven. Prior code hard-coded private-LAN patterns
     * ({@code http://192.168.*:*} etc.) with {@code allowCredentials=true},
     * so any device on the same corporate LAN could stand up a page that
     * fetched the app with cookies. In prod that's a real cross-origin
     * risk — the LAN-wide patterns should ONLY be enabled in dev/staging.
     *
     * <p>Env vars:
     * <ul>
     *   <li>{@code CORS_ALLOWED_ORIGIN_PATTERNS} — comma-separated list;
     *       defaults to localhost + private-LAN for backward-compat on
     *       existing dev machines. Prod deploy MUST set this to the
     *       app's known FQDN(s) only (e.g. {@code https://app.example.com}).</li>
     * </ul>
     */
    @org.springframework.beans.factory.annotation.Value("${cors.allowed-origin-patterns:"
            + "http://localhost:*,"
            + "http://127.0.0.1:*,"
            + "http://[::1]:*,"
            + "http://192.168.*:*,"
            + "http://10.*:*,"
            + "http://172.16.*:*,http://172.17.*:*,http://172.18.*:*,http://172.19.*:*,"
            + "http://172.2*.*:*,http://172.30.*:*,http://172.31.*:*"
            + "}")
    private String corsAllowedOriginPatterns;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(
                Arrays.stream(corsAllowedOriginPatterns.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList());
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // Sprint 50 PR Q2 — X-XSRF-TOKEN added for the double-submit CSRF
        // header the SPA echoes on state-changing requests.
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "Cache-Control", "Idempotency-Key", "X-API-Key", "X-XSRF-TOKEN"));
        // Headers the browser must expose to JS via response.headers.get().
        // Content-Disposition is critical for binary downloads (order-import
        // template.xlsm, packing-slip PDF, template preview PDF, bulk-label
        // ZIP, etc.) — without it, fetch-based downloads fall back to a
        // default filename that doesn't match the file's actual type, and
        // Excel refuses to open the .xlsm with "file format or extension
        // is not valid" because the browser saved a .xlsm as .xlsx.
        configuration.setExposedHeaders(Arrays.asList(
                "Content-Disposition", "Content-Length", "Content-Type"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
